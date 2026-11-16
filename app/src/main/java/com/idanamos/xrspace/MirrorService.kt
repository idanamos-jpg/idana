package com.idanamos.xrspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** Foreground service that mirrors the phone screen (MediaProjection) into a window. */
class MirrorService : Service() {

    class Params(val code: Int, val data: Intent, val win: XWindow, val dpi: Int)

    companion object {
        @Volatile var pending: Params? = null
        @Volatile var onEnded: (() -> Unit)? = null
        private const val CHANNEL = "mirror"
    }

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var ended = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = pending
        pending = null
        if (p == null) { stopSelf(); return START_NOT_STICKY }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "שיקוף מסך", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("XR Space")
            .setContentText("משקף את מסך הטלפון למשקפיים")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(p.code, p.data)
            if (mp == null) { finish(); return START_NOT_STICKY }
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { finish() }
            }, Handler(Looper.getMainLooper()))
            vd = mp.createVirtualDisplay(
                "xr-mirror", p.win.pxW, p.win.pxH, p.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, p.win.surface, null, null
            )
            p.win.vd = vd
        } catch (e: Exception) {
            Log.w("XRSpace", "mirror", e)
            finish()
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        if (ended) return
        ended = true
        try { vd?.release() } catch (_: Exception) {}
        vd = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) {}
        onEnded?.invoke()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }
}
