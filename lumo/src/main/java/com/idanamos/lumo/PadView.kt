package com.idanamos.lumo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Phone screen as a trackpad for the glasses:
 *  1 finger  -> move pointer, tap -> click
 *  2 fingers -> scroll (drag)
 */
@SuppressLint("ViewConstructor")
class PadView(ctx: Context, private val l: Listener) : View(ctx) {

    interface Listener {
        fun padDown()
        fun padMove(dx: Float, dy: Float)
        fun padClick()
        fun padScrollStart()
        fun padScroll(dx: Float, dy: Float)
        fun padScrollEnd()
    }

    var sensitivity = 1.6f

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var multi = false
    private var scrolling = false
    private var lcx = 0f
    private var lcy = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 220, 230, 255); textAlign = Paint.Align.CENTER
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(90, 120, 170, 255)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(4f, 4f, width - 4f, height - 4f, 36f, 36f, border)
        val lines = listOf("משטח מגע", "אצבע אחת: סמן · הקשה: לחיצה", "שתי אצבעות: גלילה")
        paint.textSize = (width / 24f).coerceIn(26f, 46f)
        var y = height / 2f - (lines.size - 1) * paint.textSize * 0.75f
        for (s in lines) { canvas.drawText(s, width / 2f, y, paint); y += paint.textSize * 1.5f }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                l.padDown()
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                downTime = e.eventTime; moved = false; multi = false; scrolling = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                multi = true
                lcx = (e.getX(0) + e.getX(1)) / 2f
                lcy = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    if (e.pointerCount >= 2) {
                        val cx = (e.getX(0) + e.getX(1)) / 2f
                        val cy = (e.getY(0) + e.getY(1)) / 2f
                        if (!scrolling && hypot(cx - lcx, cy - lcy) > slop) { scrolling = true; l.padScrollStart() }
                        if (scrolling) l.padScroll((cx - lcx) * sensitivity, (cy - lcy) * sensitivity)
                        if (scrolling || hypot(cx - lcx, cy - lcy) > slop) { lcx = cx; lcy = cy }
                    }
                } else {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) moved = true
                    if (moved) l.padMove(dx * sensitivity, dy * sensitivity)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (scrolling) { scrolling = false; l.padScrollEnd() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrolling) { scrolling = false; l.padScrollEnd() }
                if (e.actionMasked == MotionEvent.ACTION_UP && !multi && !moved && e.eventTime - downTime < 350) l.padClick()
                multi = false
            }
        }
        return true
    }
}
