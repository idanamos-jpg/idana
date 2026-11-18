package com.idanamos.lumo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The desktop drawn on the glasses. Only the virtual-monitor rectangle is lit;
 * everything around it is black (= see-through on XREAL).
 * Taps arrive from the phone touchpad (accessibility gestures) or from a Bluetooth mouse.
 */
@SuppressLint("ViewConstructor")
class DeskView(ctx: Context) : View(ctx) {

    private class Hit(val r: RectF, val action: () -> Unit)

    private val hits = mutableListOf<Hit>()
    var menuOpen = false
    private var page = 0
    private var pages = 1

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val date = SimpleDateFormat("EEE d.M", Locale("he"))

    private val tick = object : Runnable {
        override fun run() {
            Glasses.prune(context)
            invalidate()
            postDelayed(this, 3000)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        hits.clear()
        c.drawColor(Color.BLACK)
        if (width == 0 || height == 0) return
        val g = Desk.geometry(width, height)
        val r = g.rect
        val rad = r.height() * 0.018f

        // Wallpaper
        p.shader = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            intArrayOf(Color.rgb(16, 30, 62), Color.rgb(40, 22, 72), Color.rgb(8, 46, 70)), null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(r, rad, rad, p)
        p.shader = RadialGradient(
            r.centerX(), r.top + r.height() * 0.35f, r.width() * 0.45f,
            Color.argb(60, 90, 160, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
        stroke.color = Color.argb(110, 120, 170, 255)
        stroke.strokeWidth = (r.height() * 0.003f).coerceAtLeast(1.5f)
        c.drawRoundRect(r, rad, rad, stroke)

        // Watermark
        val work = Desk.workArea(g)
        txt.textAlign = Paint.Align.CENTER
        txt.textSize = (r.height() * 0.06f).coerceAtLeast(12f)
        txt.alpha = 70
        c.drawText("Lumo", work.centerX(), work.centerY(), txt)
        txt.textSize = (r.height() * 0.03f).coerceAtLeast(9f)
        c.drawText(Desk.summary(), work.centerX(), work.centerY() + txt.textSize * 1.8f, txt)
        txt.alpha = 255

        // Tap on empty desktop closes the menu
        hits += Hit(RectF(work)) { if (menuOpen) { menuOpen = false; invalidate() } }

        drawTaskbar(c, g, rad)
        if (menuOpen) drawMenu(c, g, work, rad)
    }

    private fun drawTaskbar(c: Canvas, g: Desk.Geo, rad: Float) {
        val r = g.rect
        val tb = Desk.taskbarH(g)
        val bar = RectF(r.left, r.bottom - tb, r.right, r.bottom)
        p.color = Color.argb(225, 10, 12, 20)
        c.save()
        c.clipRect(bar)
        c.drawRoundRect(RectF(bar.left, bar.top - rad * 2, bar.right, bar.bottom), rad, rad, p)
        c.restore()

        val icon = tb * 0.7f
        val pad = (tb - icon) / 2f
        val top = bar.top + pad

        // Start button (right side, Hebrew layout)
        var x = bar.right - pad * 2 - icon
        val sr = RectF(x, top, x + icon, top + icon)
        p.color = if (menuOpen) Color.rgb(63, 160, 255) else Color.argb(60, 255, 255, 255)
        c.drawRoundRect(sr, icon * 0.22f, icon * 0.22f, p)
        p.color = Color.WHITE
        val q = icon * 0.17f
        val s = icon * 0.26f
        for (ix in 0..1) for (iy in 0..1) {
            val l = sr.left + q + ix * (s + q * 0.5f)
            val t = sr.top + q + iy * (s + q * 0.5f)
            c.drawRoundRect(l, t, l + s, t + s, s * 0.2f, s * 0.2f, p)
        }
        hits += Hit(sr) { menuOpen = !menuOpen; page = 0; invalidate() }
        x -= pad * 3

        // Clock + date (left side)
        txt.textAlign = Paint.Align.LEFT
        txt.textSize = tb * 0.34f
        val now = Date()
        c.drawText(clock.format(now), bar.left + pad * 3, bar.top + tb * 0.47f, txt)
        txt.textSize = tb * 0.24f
        txt.alpha = 170
        c.drawText(date.format(now), bar.left + pad * 3, bar.top + tb * 0.8f, txt)
        txt.alpha = 255
        val leftLimit = bar.left + pad * 3 + tb * 2.2f

        // Pinned apps, then other open windows
        val open = Glasses.windows.map { it.pkg }
        val items = (Apps.pinned() + open).distinct()
        for (pkg in items) {
            val app = Apps.find(pkg) ?: continue
            if (x - icon < leftLimit) break
            x -= icon
            val ir = RectF(x, top, x + icon, top + icon)
            c.drawBitmap(app.icon, null, ir, bmp)
            if (pkg in open) {
                p.color = Color.rgb(63, 160, 255)
                val w = icon * 0.35f
                c.drawRoundRect(ir.centerX() - w / 2, bar.bottom - pad * 0.8f, ir.centerX() + w / 2,
                    bar.bottom - pad * 0.3f, 3f, 3f, p)
            }
            hits += Hit(ir) {
                menuOpen = false
                val slot = Glasses.windows.firstOrNull { it.pkg == pkg }?.slot ?: Desk.defaultSlot
                Glasses.launch(context, pkg, slot)
            }
            x -= pad * 1.6f
        }
    }

    private fun drawMenu(c: Canvas, g: Desk.Geo, work: RectF, rad: Float) {
        val maxW = work.height() * 1.7f
        val mw = minOf(work.width() * 0.8f, maxW)
        val m = RectF(work.centerX() - mw / 2, work.top + work.height() * 0.06f,
            work.centerX() + mw / 2, work.bottom - work.height() * 0.03f)
        p.color = Color.argb(240, 18, 22, 34)
        c.drawRoundRect(m, rad * 1.5f, rad * 1.5f, p)
        stroke.color = Color.argb(80, 120, 170, 255)
        c.drawRoundRect(m, rad * 1.5f, rad * 1.5f, stroke)
        hits += Hit(m) {} // swallow taps on the panel background

        val padM = m.height() * 0.05f
        txt.textAlign = Paint.Align.RIGHT
        txt.textSize = m.height() * 0.055f
        c.drawText("כל האפליקציות", m.right - padM, m.top + padM + txt.textSize, txt)

        val apps = Apps.list
        val gridTop = m.top + padM * 2 + txt.textSize
        val gridBottom = m.bottom - m.height() * 0.11f
        val rows = 3
        val cellH = (gridBottom - gridTop) / rows
        val cols = maxOf(4, ((m.width() - padM * 2) / (cellH * 1.05f)).toInt())
        val cellW = (m.width() - padM * 2) / cols
        val per = rows * cols
        pages = maxOf(1, ceil(apps.size / per.toFloat()).toInt())
        page = page.coerceIn(0, pages - 1)

        val iconS = minOf(cellW, cellH) * 0.55f
        txt.textAlign = Paint.Align.CENTER
        txt.textSize = cellH * 0.14f
        for (i in 0 until per) {
            val idx = page * per + i
            if (idx >= apps.size) break
            val a = apps[idx]
            val col = i % cols
            val row = i / cols
            // right-to-left order
            val cx = m.right - padM - cellW * (col + 0.5f)
            val cy = gridTop + cellH * row
            val ir = RectF(cx - iconS / 2, cy + cellH * 0.08f, cx + iconS / 2, cy + cellH * 0.08f + iconS)
            c.drawBitmap(a.icon, null, ir, bmp)
            val label = TextUtils.ellipsize(a.label, txt, cellW * 0.92f, TextUtils.TruncateAt.END).toString()
            c.drawText(label, cx, ir.bottom + txt.textSize * 1.3f, txt)
            val cell = RectF(cx - cellW / 2, cy, cx + cellW / 2, cy + cellH)
            hits += Hit(cell) {
                menuOpen = false
                invalidate()
                Glasses.launch(context, a.pkg, Desk.defaultSlot)
            }
        }
        if (apps.isEmpty()) c.drawText("טוען אפליקציות…", m.centerX(), m.centerY(), txt)

        // Page navigation
        if (pages > 1) {
            val ny = m.bottom - m.height() * 0.055f
            txt.textSize = m.height() * 0.05f
            c.drawText("${page + 1} / $pages", m.centerX(), ny + txt.textSize * 0.35f, txt)
            val bw = m.height() * 0.12f
            txt.textSize = m.height() * 0.08f
            val prev = RectF(m.centerX() + bw * 1.2f, ny - bw / 2, m.centerX() + bw * 2.2f, ny + bw / 2)
            val next = RectF(m.centerX() - bw * 2.2f, ny - bw / 2, m.centerX() - bw * 1.2f, ny + bw / 2)
            c.drawText("›", prev.centerX(), prev.centerY() + txt.textSize * 0.35f, txt)
            c.drawText("‹", next.centerX(), next.centerY() + txt.textSize * 0.35f, txt)
            hits += Hit(prev) { page = (page - 1).coerceAtLeast(0); invalidate() }
            hits += Hit(next) { page = (page + 1).coerceAtMost(pages - 1); invalidate() }
        }
    }

    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                if (menuOpen && abs(dx) > width * 0.04f && abs(dx) > abs(e.y - downY)) {
                    // Swipe left = next page (RTL)
                    page = if (dx < 0) (page + 1).coerceAtMost(pages - 1) else (page - 1).coerceAtLeast(0)
                    invalidate()
                } else {
                    hits.asReversed().firstOrNull { it.r.contains(e.x, e.y) }?.action?.invoke()
                }
            }
        }
        return true
    }
}
