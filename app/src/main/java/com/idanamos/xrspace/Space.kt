package com.idanamos.xrspace

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.atan
import kotlin.math.tan

/** Shared state between the phone controller (main thread) and the renderer (GL thread). */
class Space {
    val windows = CopyOnWriteArrayList<XWindow>()
    @Volatile var focused: XWindow? = null

    @Volatile var distance = 2.4f
    @Volatile var curve = 0.4f
    @Volatile var fovY = 23.5f
    /** true = screens stay fixed in space (3DoF), false = screens locked to the head. */
    @Volatile var anchored = true

    @Volatile var cursorYaw = 0f
    @Volatile var cursorPitch = 0f

    // Current view orientation (degrees), written by the renderer
    @Volatile var viewYaw = 0f
    @Volatile var viewPitch = 0f
    @Volatile var aspect = 16f / 9f

    @Volatile var tracker: ImuTracker? = null

    fun headTracking(): Boolean {
        val t = tracker
        return anchored && t != null && t.active
    }

    fun fovX(): Float =
        Math.toDegrees(2.0 * atan(tan(Math.toRadians(fovY / 2.0)) * aspect)).toFloat()

    fun hit(yaw: Float, pitch: Float): Hit? {
        val dir = Geo.dirFromAngles(yaw, pitch)
        var best: Hit? = null
        for (w in windows) {
            if (w.released) continue
            val h = w.raycast(dir) ?: continue
            if (best == null || h.dist < best.dist) best = h
        }
        return best
    }

    fun cursorHit(): Hit? = hit(cursorYaw, cursorPitch)
}
