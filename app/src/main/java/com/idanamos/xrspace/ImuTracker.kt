package com.idanamos.xrspace

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads the XREAL Air / Air 2 / Air 2 Pro IMU over USB HID and fuses gyro + accelerometer
 * into a head orientation (yaw / pitch / roll, degrees).
 * Protocol per the open-source ar-drivers-rs project (MIT).
 */
class ImuTracker(private val ctx: Context, private val status: (String) -> Unit) {

    companion object {
        const val VID = 0x3318
        const val ACTION_USB_PERMISSION = "com.idanamos.xrspace.USB_PERMISSION"
        private const val TAG = "XRSpaceIMU"
        private const val CALIB_SECONDS = 1.2
    }

    @Volatile var active = false
        private set
    @Volatile var connected = false
        private set

    @Volatile var yaw = 0f
        private set
    @Volatile var pitch = 0f
        private set
    @Volatile var roll = 0f
        private set

    @Volatile var invertYaw = false
    @Volatile var invertPitch = false
    @Volatile var invertRoll = false
    @Volatile var gravityCorrection = true

    @Volatile private var running = false
    private var thread: Thread? = null
    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var receiverRegistered = false

    // Fusion state (IMU thread only)
    private var qw = 1.0; private var qx = 0.0; private var qy = 0.0; private var qz = 0.0
    private var bx = 0.0; private var by = 0.0; private var bz = 0.0
    @Volatile private var calibrate = true
    @Volatile private var recenterRequested = false
    private var calibTime = 0.0
    private var calibN = 0
    private val gSum = DoubleArray(3)
    private val aSum = DoubleArray(3)
    private var yawOffset = 0.0
    private var lastTs = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) start()
                else status("לא אושרה גישת USB למשקפיים")
            }
        }
    }

    fun start() {
        if (running) return
        if (!receiverRegistered) {
            val f = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(receiver, f)
            receiverRegistered = true
        }
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = um.deviceList.values.firstOrNull { it.vendorId == VID }
        if (dev == null) {
            status("חיישן תנועה: המשקפיים לא זוהו ב-USB")
            return
        }
        if (!um.hasPermission(dev)) {
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            um.requestPermission(dev, pi)
            status("חיישן תנועה: ממתין לאישור גישה")
            return
        }
        open(um, dev)
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        try { iface?.let { conn?.releaseInterface(it) } } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        conn = null
        active = false
        connected = false
    }

    fun recalibrate() { calibrate = true; active = false }
    fun recenter() { recenterRequested = true }

    private fun open(um: UsbManager, dev: UsbDevice) {
        val c = um.openDevice(dev)
        if (c == null) { status("חיישן תנועה: לא ניתן לפתוח את המכשיר"); return }
        val ifaces = (0 until dev.interfaceCount).map { dev.getInterface(it) }
        val i = ifaces.firstOrNull { it.id == 3 && inEp(it) != null }
            ?: ifaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID && it.id != 4 && it.id != 0 && inEp(it) != null }
        if (i == null) { c.close(); status("חיישן תנועה: ממשק IMU לא נמצא"); return }
        if (!c.claimInterface(i, true)) { c.close(); status("חיישן תנועה: לא ניתן לתפוס את הממשק"); return }
        conn = c
        iface = i
        val epIn = inEp(i)!!
        val epOut = outEp(i)
        running = true
        connected = true
        calibrate = true
        status("חיישן תנועה: מכייל — השאר את הראש יציב")
        thread = Thread({ loop(c, i, epIn, epOut) }, "imu").also { it.start() }
    }

    private fun inEp(i: UsbInterface): UsbEndpoint? = (0 until i.endpointCount).map { i.getEndpoint(it) }
        .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

    private fun outEp(i: UsbInterface): UsbEndpoint? = (0 until i.endpointCount).map { i.getEndpoint(it) }
        .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }

    private fun imuCommand(id: Int, data: ByteArray): ByteArray {
        val p = ByteArray(64)
        val len = data.size + 3
        p[0] = 0xaa.toByte()
        p[5] = (len and 0xff).toByte()
        p[6] = ((len shr 8) and 0xff).toByte()
        p[7] = id.toByte()
        System.arraycopy(data, 0, p, 8, data.size)
        val crc = CRC32()
        crc.update(p, 5, len)
        val v = crc.value
        p[1] = (v and 0xff).toByte()
        p[2] = ((v shr 8) and 0xff).toByte()
        p[3] = ((v shr 16) and 0xff).toByte()
        p[4] = ((v shr 24) and 0xff).toByte()
        return p
    }

    private fun send(c: UsbDeviceConnection, i: UsbInterface, out: UsbEndpoint?, pkt: ByteArray) {
        val r = if (out != null) c.bulkTransfer(out, pkt, pkt.size, 500)
        else c.controlTransfer(0x21, 0x09, 0x0200, i.id, pkt, pkt.size, 500)
        if (r < 0) Log.w(TAG, "send failed")
    }

    private fun loop(c: UsbDeviceConnection, i: UsbInterface, epIn: UsbEndpoint, out: UsbEndpoint?) {
        val buf = ByteArray(maxOf(epIn.maxPacketSize, 128))
        try {
            send(c, i, out, imuCommand(0x19, byteArrayOf(1)))
            var misses = 0
            var goodPackets = 0
            while (running) {
                val n = c.bulkTransfer(epIn, buf, buf.size, 200)
                if (n <= 0) {
                    misses++
                    if (misses % 10 == 0) send(c, i, out, imuCommand(0x19, byteArrayOf(1)))
                    if (misses == 25 && goodPackets == 0) status("חיישן תנועה: אין נתונים מהמשקפיים")
                    continue
                }
                misses = 0
                if (n >= 42 && buf[0].toInt() == 1 && buf[1].toInt() == 2) {
                    goodPackets++
                    parse(buf)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "imu loop", e)
            status("חיישן תנועה: נותק")
        } finally {
            active = false
            connected = false
        }
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)
    private fun u64(b: ByteArray, o: Int): Long = u32(b, o) or (u32(b, o + 4) shl 32)
    private fun i24(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or (b[o + 2].toInt() shl 16)

    private fun parse(b: ByteArray) {
        val ts = u64(b, 4)
        val gMul = u16(b, 12).toDouble()
        val gDiv = u32(b, 14).toDouble()
        val aMul = u16(b, 27).toDouble()
        val aDiv = u32(b, 29).toDouble()
        if (gDiv == 0.0 || aDiv == 0.0) return
        val gs = gMul / gDiv
        val ascale = aMul / aDiv * 9.81
        val rawGx = i24(b, 18) * gs
        val rawGy = i24(b, 21) * gs
        val rawGz = i24(b, 24) * gs
        val rawAx = i24(b, 33) * ascale
        val rawAy = i24(b, 36) * ascale
        val rawAz = i24(b, 39) * ascale
        // Axis remap to OpenGL frame (x right, y up, z back), as in ar-drivers-rs
        val gx = Math.toRadians(-rawGx)
        val gy = Math.toRadians(rawGz)
        val gz = Math.toRadians(rawGy)
        val ax = -rawAx
        val ay = rawAz
        val az = rawAy

        var dt = if (lastTs == 0L) 0.001 else (ts - lastTs) / 1e9
        lastTs = ts
        if (dt <= 0.0 || dt > 0.05) dt = 0.001
        step(gx, gy, gz, ax, ay, az, dt)
    }

    private fun step(gx: Double, gy: Double, gz: Double, ax: Double, ay: Double, az: Double, dt: Double) {
        if (calibrate) {
            if (calibN == 0) { gSum.fill(0.0); aSum.fill(0.0); calibTime = 0.0 }
            gSum[0] += gx; gSum[1] += gy; gSum[2] += gz
            aSum[0] += ax; aSum[1] += ay; aSum[2] += az
            calibN++
            calibTime += dt
            if (calibTime >= CALIB_SECONDS) {
                bx = gSum[0] / calibN; by = gSum[1] / calibN; bz = gSum[2] / calibN
                setFromGravity(aSum[0] / calibN, aSum[1] / calibN, aSum[2] / calibN)
                calibN = 0
                calibrate = false
                recenterRequested = true
                active = true
                status("חיישן תנועה: פעיל")
            }
            return
        }

        val wx = gx - bx
        val wy = gy - by
        val wz = gz - bz
        val wm = sqrt(wx * wx + wy * wy + wz * wz)
        // Slowly track gyro bias while the head is still
        if (wm < 0.02) {
            bx += (gx - bx) * 0.0005; by += (gy - by) * 0.0005; bz += (gz - bz) * 0.0005
        }
        // q = q * dq(body angular velocity)
        val ang = wm * dt
        if (ang > 1e-12) {
            val h = ang / 2
            val s = sin(h) / wm
            mulRight(cos(h), wx * s, wy * s, wz * s)
        }
        // Gravity correction (pitch / roll only)
        if (gravityCorrection) {
            val an = sqrt(ax * ax + ay * ay + az * az)
            if (an > 7.5 && an < 12.0) {
                val v = rotate(ax / an, ay / an, az / an)
                // axis = v x up, up = (0,1,0)
                val ex = -v[2]
                val ez = v[0]
                val k = 1.5 * dt
                mulLeft(1.0, ex * k / 2, 0.0, ez * k / 2)
            }
        }
        normalize()

        // Euler (YXZ) from rotation matrix
        val r02 = 2 * (qx * qz + qw * qy)
        val r12 = 2 * (qy * qz - qw * qx)
        val r22 = 1 - 2 * (qx * qx + qy * qy)
        val r10 = 2 * (qx * qy + qw * qz)
        val r11 = 1 - 2 * (qx * qx + qz * qz)
        val p = Math.toDegrees(asin((-r12).coerceIn(-1.0, 1.0)))
        val y = Math.toDegrees(atan2(r02, r22))
        val r = Math.toDegrees(atan2(r10, r11))
        if (recenterRequested) { yawOffset = y; recenterRequested = false }
        var yy = Geo.wrap180((y - yawOffset).toFloat())
        var pp = p.toFloat()
        var rr = r.toFloat()
        if (invertYaw) yy = -yy
        if (invertPitch) pp = -pp
        if (invertRoll) rr = -rr
        yaw = yy; pitch = pp; roll = rr
    }

    private fun setFromGravity(ax: Double, ay: Double, az: Double) {
        val n = sqrt(ax * ax + ay * ay + az * az)
        if (n < 1e-6) { qw = 1.0; qx = 0.0; qy = 0.0; qz = 0.0; return }
        val vx = ax / n; val vy = ay / n; val vz = az / n
        // shortest rotation taking v to up (0,1,0)
        val d = vy
        if (d < -0.9999) { qw = 0.0; qx = 1.0; qy = 0.0; qz = 0.0; return }
        // cross(v, up) = (-vz, 0, vx)
        qw = 1 + d; qx = -vz; qy = 0.0; qz = vx
        normalize()
    }

    private fun mulRight(bw: Double, bx_: Double, by_: Double, bz_: Double) {
        val w = qw * bw - qx * bx_ - qy * by_ - qz * bz_
        val x = qw * bx_ + qx * bw + qy * bz_ - qz * by_
        val y = qw * by_ - qx * bz_ + qy * bw + qz * bx_
        val z = qw * bz_ + qx * by_ - qy * bx_ + qz * bw
        qw = w; qx = x; qy = y; qz = z
    }

    private fun mulLeft(aw: Double, ax_: Double, ay_: Double, az_: Double) {
        val w = aw * qw - ax_ * qx - ay_ * qy - az_ * qz
        val x = aw * qx + ax_ * qw + ay_ * qz - az_ * qy
        val y = aw * qy - ax_ * qz + ay_ * qw + az_ * qx
        val z = aw * qz + ax_ * qy - ay_ * qx + az_ * qw
        qw = w; qx = x; qy = y; qz = z
    }

    private fun normalize() {
        val n = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (n > 1e-12) { qw /= n; qx /= n; qy /= n; qz /= n }
    }

    /** Rotate body-frame vector into world frame by q. */
    private fun rotate(x: Double, y: Double, z: Double): DoubleArray {
        val r00 = 1 - 2 * (qy * qy + qz * qz); val r01 = 2 * (qx * qy - qw * qz); val r02 = 2 * (qx * qz + qw * qy)
        val r10 = 2 * (qx * qy + qw * qz); val r11 = 1 - 2 * (qx * qx + qz * qz); val r12 = 2 * (qy * qz - qw * qx)
        val r20 = 2 * (qx * qz - qw * qy); val r21 = 2 * (qy * qz + qw * qx); val r22 = 1 - 2 * (qx * qx + qy * qy)
        return doubleArrayOf(r00 * x + r01 * y + r02 * z, r10 * x + r11 * y + r12 * z, r20 * x + r21 * y + r22 * z)
    }

    @Suppress("unused")
    private fun near(a: Double, b: Double) = abs(a - b) < 1e-6
}
