package com.idanamos.xrspace

import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.opengl.Matrix
import android.view.Surface
import kotlin.math.cos
import kotlin.math.sin

/** A floating screen in the virtual space. Content is produced into [surface] by a VirtualDisplay. */
class XWindow(val id: Int, val kind: Kind, val pxW: Int, val pxH: Int) {
    enum class Kind { WEB, MIRROR }

    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    @Volatile var widthM = 1.4f
    @Volatile var title = ""
    /** Belongs to Smart Glasses (AR) mode instead of the regular workspace. */
    @Volatile var ar = false
    /** Per-window brightness multiplier (0..1). */
    @Volatile var brightness = 1f
    /** Dark mode: invert luminance so white pages become black (= see-through). */
    @Volatile var invert = false

    @Volatile var surfaceTexture: SurfaceTexture = newTexture()
    @Volatile var surface: Surface = Surface(surfaceTexture)
    @Volatile var frameAvailable = false
    @Volatile var hasFrame = false
    @Volatile var released = false
    @Volatile var surfaceBroken = false

    // GL-thread state
    var texId = 0
    @Volatile var attachedTo: Any? = null
    val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Interleaved x,y,z,u,v triangle strip in window-local space. */
    @Volatile var mesh: FloatArray? = null
    private var meshKey = ""

    var web: WebWindowHost? = null
    @Volatile var vd: VirtualDisplay? = null

    val heightM: Float get() = widthM * pxH / pxW

    private fun newTexture(): SurfaceTexture {
        val st = SurfaceTexture(false)
        st.setDefaultBufferSize(pxW, pxH)
        st.setOnFrameAvailableListener { frameAvailable = true }
        return st
    }

    /** Replace the surface (used when the GL context that owned the texture died). Main thread. */
    fun recreateSurface() {
        if (released) return
        val oldSurface = surface
        val oldSt = surfaceTexture
        val st = newTexture()
        val s = Surface(st)
        surfaceTexture = st
        surface = s
        hasFrame = false
        attachedTo = null
        try { vd?.surface = s } catch (_: Exception) {}
        surfaceBroken = false
        try { oldSurface.release() } catch (_: Exception) {}
        try { oldSt.release() } catch (_: Exception) {}
        web?.webView?.invalidate()
    }

    fun ensureMesh(distance: Float, curve: Float) {
        val key = "$distance|$curve|$widthM"
        if (key == meshKey && mesh != null) return
        val cols = 48
        val w = widthM
        val h = heightM
        val out = FloatArray((cols + 1) * 2 * 5)
        val k = curve.coerceIn(0f, 1f)
        val rc = if (k < 0.01f) 0f else distance / k
        var o = 0
        for (i in 0..cols) {
            val u = i.toFloat() / cols
            val x: Float
            val z: Float
            if (rc == 0f) {
                x = (u - 0.5f) * w
                z = -distance
            } else {
                val th = (u - 0.5f) * w / rc
                x = rc * sin(th)
                z = -distance + rc * (1f - cos(th))
            }
            // bottom
            out[o++] = x; out[o++] = -h / 2f; out[o++] = z; out[o++] = u; out[o++] = 0f
            // top
            out[o++] = x; out[o++] = h / 2f; out[o++] = z; out[o++] = u; out[o++] = 1f
        }
        mesh = out
        meshKey = key
    }

    /** Ray from the eye (origin) along world direction [dir]. */
    fun raycast(dir: FloatArray): Hit? {
        val m = mesh ?: return null
        val d = Geo.toLocal(dir, yaw, pitch)
        val verts = m.size / 5
        val cols = verts / 2 - 1
        var best: Hit? = null
        for (i in 0 until cols) {
            val b0 = (i * 2) * 5
            val t0 = (i * 2 + 1) * 5
            val b1 = ((i + 1) * 2) * 5
            val t1 = ((i + 1) * 2 + 1) * 5
            for (tri in 0..1) {
                val a = if (tri == 0) b0 else t0
                val b = if (tri == 0) t0 else t1
                val c = b1
                val r = Geo.rayTri(d, m, a, b, c) ?: continue
                if (best != null && r[0] >= best.dist) continue
                val w0 = 1f - r[1] - r[2]
                val u = w0 * m[a + 3] + r[1] * m[b + 3] + r[2] * m[c + 3]
                val v = w0 * m[a + 4] + r[1] * m[b + 4] + r[2] * m[c + 4]
                best = Hit(this, u, v, r[0])
            }
        }
        return best
    }
}

class Hit(val window: XWindow, val u: Float, val v: Float, val dist: Float) {
    val px: Float get() = u * window.pxW
    val py: Float get() = (1f - v) * window.pxH
}
