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

    // Smart Glasses / AR mode: separate set of minimal panels on true black
    @Volatile var arMode = false
    /** Instantly blank the glasses (everything black = see-through). */
    @Volatile var hidden = false
    /** Global brightness multiplier for content (additive display: brightness == opacity). */
    @Volatile var brightness = 1f
    /** In AR mode the pointer is only drawn briefly after it moves. */
    @Volatile var cursorShownUntil = 0L

    fun visible(w: XWindow): Boolean = !w.released && w.ar == arMode
    fun visibleWindows(): List<XWindow> = windows.filter { visible(it) }

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
            if (!visible(w)) continue
            val h = w.raycast(dir) ?: continue
            if (best == null || h.dist < best.dist) best = h
        }
        return best
    }

    fun cursorHit(): Hit? = hit(cursorYaw, cursorPitch)
}
