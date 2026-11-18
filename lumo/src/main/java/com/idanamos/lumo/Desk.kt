package com.idanamos.lumo

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The virtual monitor: its physical size (diagonal + aspect) and how far away it is.
 * Shared by the phone controller and the desktop shown on the glasses.
 *
 * The glasses show a fixed 1920×1080 image across a ~46° diagonal field of view, so a monitor of
 * width W at distance D covers 2·atan(W/2D) degrees — that is turned into a rectangle of pixels.
 * Everything outside that rectangle is black, and black is transparent on XREAL glasses.
 */
object Desk {

    class Aspect(val label: String, val ratio: Float)
    class Preset(val label: String, val diagIn: Float, val aspect: Int, val distM: Float)

    enum class Slot(val label: String) { FULL("מלא"), RIGHT("חצי ימין"), LEFT("חצי שמאל"), CENTER("מרכז") }

    val aspects = listOf(
        Aspect("16:9", 16f / 9f),
        Aspect("16:10", 1.6f),
        Aspect("21:9", 21f / 9f),
        Aspect("32:9", 32f / 9f)
    )

    val presets = listOf(
        Preset("💻 נייד 15″", 15f, 1, 0.6f),
        Preset("🖥 מסך 27″", 27f, 0, 0.9f),
        Preset("🖥 רחב 34″", 34f, 2, 1.0f),
        Preset("🖥 אולטרה 49″", 49f, 3, 1.2f),
        Preset("📺 טלוויזיה 65″", 65f, 0, 2.2f),
        Preset("🎬 קולנוע 150″", 150f, 0, 5f)
    )

    const val MIN_DIAG = 10f
    const val MAX_DIAG = 150f
    const val MIN_DIST = 0.4f
    const val MAX_DIST = 5f

    private lateinit var prefs: SharedPreferences

    var diagIn = 27f
    var aspect = 0
    var distM = 0.9f
    /** -1 = bottom of the view, 0 = centered, 1 = top */
    var height = 0f
    /** Diagonal field of view of the glasses. Air / Air 2 / Air 2 Pro ≈ 46°, Air 2 Ultra ≈ 52°. */
    var fovDiag = 46f
    var defaultSlot = Slot.FULL
    var autoApply = true

    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("desk", Context.MODE_PRIVATE)
        diagIn = prefs.getFloat("diag", 27f).coerceIn(MIN_DIAG, MAX_DIAG)
        aspect = prefs.getInt("aspect", 0).coerceIn(0, aspects.size - 1)
        distM = prefs.getFloat("dist", 0.9f).coerceIn(MIN_DIST, MAX_DIST)
        height = prefs.getFloat("height", 0f).coerceIn(-1f, 1f)
        fovDiag = prefs.getFloat("fov", 46f).coerceIn(30f, 70f)
        defaultSlot = Slot.values().getOrElse(prefs.getInt("slot", 0)) { Slot.FULL }
        autoApply = prefs.getBoolean("autoApply", true)
    }

    fun store(): SharedPreferences = prefs

    /** Settings changed: save and redraw everywhere. */
    fun changed() {
        prefs.edit()
            .putFloat("diag", diagIn).putInt("aspect", aspect).putFloat("dist", distM)
            .putFloat("height", height).putFloat("fov", fovDiag)
            .putInt("slot", defaultSlot.ordinal).putBoolean("autoApply", autoApply)
            .apply()
        ping()
    }

    /** Redraw everywhere without saving. */
    fun ping() { listeners.toList().forEach { it() } }

    fun applyPreset(p: Preset) {
        diagIn = p.diagIn; aspect = p.aspect; distM = p.distM; height = 0f
        changed()
    }

    class Geo(
        /** Where the monitor sits on the glasses display, in display pixels. */
        val rect: RectF,
        /** Angular size of the monitor as you see it. */
        val degW: Float,
        val degH: Float,
        /** Monitor is larger than the field of view and was shrunk to fit. */
        val clipped: Boolean,
        val widthCm: Float,
        val heightCm: Float
    )

    fun geometry(w: Int, h: Int): Geo {
        val r = aspects[aspect].ratio
        val diagM = diagIn * 0.0254f
        val hM = diagM / sqrt(r * r + 1f)
        val wM = hM * r
        // Focal length in pixels for the glasses' field of view
        val f = (hypot(w.toFloat(), h.toFloat()) / 2f) / tan(Math.toRadians(fovDiag / 2.0)).toFloat()
        var pw = f * wM / distM
        var ph = f * hM / distM
        var clipped = false
        val s = minOf(w / pw, h / ph)
        if (s < 1f) { pw *= s; ph *= s; clipped = true }
        val left = (w - pw) / 2f
        val top = (h - ph) / 2f - height * (h - ph) / 2f
        val degW = Math.toDegrees(2.0 * atan(wM / 2.0 / distM)).toFloat()
        val degH = Math.toDegrees(2.0 * atan(hM / 2.0 / distM)).toFloat()
        return Geo(RectF(left, top, left + pw, top + ph), degW, degH, clipped, wM * 100f, hM * 100f)
    }

    fun taskbarH(g: Geo): Float = (g.rect.height() * 0.07f).coerceIn(26f, 72f)

    /** Desktop area above the taskbar, where app windows go. */
    fun workArea(g: Geo): RectF = RectF(g.rect.left, g.rect.top, g.rect.right, g.rect.bottom - taskbarH(g))

    /** Window bounds (display pixels) for an app placed in [slot]. */
    fun bounds(slot: Slot, w: Int, h: Int): Rect {
        val a = workArea(geometry(w, h))
        val gap = (a.height() * 0.006f).coerceAtLeast(2f)
        val r = when (slot) {
            Slot.FULL -> a
            Slot.RIGHT -> RectF(a.centerX() + gap, a.top, a.right, a.bottom)
            Slot.LEFT -> RectF(a.left, a.top, a.centerX() - gap, a.bottom)
            Slot.CENTER -> RectF(
                a.left + a.width() * 0.15f, a.top + a.height() * 0.1f,
                a.right - a.width() * 0.15f, a.bottom - a.height() * 0.1f
            )
        }
        return Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
    }

    fun summary(): String {
        val d = "%.0f".format(diagIn)
        return "$d″ · ${aspects[aspect].label} · ${"%.2f".format(distM).trimEnd('0').trimEnd('.')} מ׳"
    }
}
