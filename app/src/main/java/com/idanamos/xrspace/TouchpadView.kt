package com.idanamos.xrspace

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Phone screen as a trackpad:
 *  1 finger move  -> move pointer
 *  tap            -> click
 *  long press     -> grab and move the window under the pointer
 *  2 fingers drag -> scroll
 *  pinch          -> resize window under the pointer
 */
@SuppressLint("ViewConstructor")
class TouchpadView(ctx: Context, private val c: MainActivity) : View(ctx) {

    var sensitivity = 0.045f
    var showHint = true

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var dragMode = false
    private var multi = false
    private var dragTarget: XWindow? = null

    private var twoMode = 0 // 0 undecided, 1 scroll, 2 pinch, -1 done
    private var pinchStartDist = 0f
    private var pinchStartWidth = 0f
    private var pinchTarget: XWindow? = null
    private var startCx = 0f
    private var startCy = 0f
    private var lastCx = 0f
    private var lastCy = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 220, 230, 255)
        textAlign = Paint.Align.CENTER
        textSize = 38f
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(90, 120, 170, 255)
    }

    private val longPress = Runnable {
        if (!moved && !multi) {
            dragMode = true
            dragTarget = c.space.cursorHit()?.window ?: c.space.focused
            dragTarget?.let { c.space.focused = it }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(4f, 4f, width - 4f, height - 4f, 36f, 36f, border)
        if (!showHint) return
        val lines = if (dragMode) listOf("מזיז חלון…") else listOf(
            "משטח מגע",
            "אצבע אחת: הזזת הסמן · הקשה: לחיצה",
            "לחיצה ארוכה וגרירה: הזזת חלון",
            "שתי אצבעות: גלילה · צביטה: שינוי גודל"
        )
        paint.textSize = (width / 26f).coerceIn(26f, 46f)
        var y = height / 2f - (lines.size - 1) * paint.textSize * 0.75f
        for (l in lines) {
            canvas.drawText(l, width / 2f, y, paint)
            y += paint.textSize * 1.5f
        }
    }

    private fun dist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                c.ensureCursorVisible()
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                downTime = e.eventTime
                moved = false; dragMode = false; multi = false; twoMode = 0
                postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2 && !dragMode) {
                    multi = true
                    removeCallbacks(longPress)
                    twoMode = 0
                    pinchStartDist = dist(e).coerceAtLeast(1f)
                    startCx = (e.getX(0) + e.getX(1)) / 2f
                    startCy = (e.getY(0) + e.getY(1)) / 2f
                    lastCx = startCx; lastCy = startCy
                    pinchTarget = c.space.cursorHit()?.window ?: c.space.focused
                    pinchStartWidth = pinchTarget?.widthM ?: 1f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    if (e.pointerCount >= 2 && twoMode >= 0) {
                        val d = dist(e)
                        val cx = (e.getX(0) + e.getX(1)) / 2f
                        val cy = (e.getY(0) + e.getY(1)) / 2f
                        if (twoMode == 0) {
                            if (abs(d - pinchStartDist) > slop * 3) twoMode = 2
                            else if (hypot(cx - startCx, cy - startCy) > slop) {
                                twoMode = 1
                                c.scrollStart()
                            }
                        }
                        if (twoMode == 2) {
                            pinchTarget?.let {
                                it.widthM = (pinchStartWidth * d / pinchStartDist).coerceIn(0.3f, 6f)
                                c.onWindowResized(it)
                            }
                        } else if (twoMode == 1) {
                            c.scrollBy(cx - lastCx, cy - lastCy)
                        }
                        lastCx = cx; lastCy = cy
                    }
                } else {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                        moved = true
                        if (!dragMode) removeCallbacks(longPress)
                    }
                    if (moved || dragMode) c.moveCursor(dx * sensitivity, dy * sensitivity, if (dragMode) dragTarget else null)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (twoMode == 1) c.scrollEnd()
                if (multi) twoMode = -1
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (twoMode == 1) c.scrollEnd()
                if (!multi && !moved && !dragMode && e.eventTime - downTime < 350) c.click()
                dragMode = false; multi = false; dragTarget = null; twoMode = 0
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (twoMode == 1) c.scrollEnd()
                dragMode = false; multi = false; dragTarget = null; twoMode = 0
                invalidate()
            }
        }
        return true
    }
}
