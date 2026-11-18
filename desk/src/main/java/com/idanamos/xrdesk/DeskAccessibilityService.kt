package com.idanamos.xrdesk

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lets the phone touchpad control a regular app that runs full-screen on the glasses display:
 * draws a pointer on that display and performs taps / swipes / text entry there.
 * Requires the user to enable it in Accessibility settings.
 */
class DeskAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: DeskAccessibilityService? = null
        private const val TAG = "XRDeskA11y"
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeCursor()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeCursor()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ------------------------------------------------------------------ pointer overlay

    private class Dot(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 180, 255) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
        }
        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r * 0.55f, fill)
            canvas.drawCircle(r, r, r * 0.55f, ring)
        }
    }

    private var cursorView: View? = null
    private var cursorWm: WindowManager? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private val dotSize = 40

    fun showCursor(display: Display, x: Int, y: Int) {
        removeCursor()
        try {
            val ctx: Context = if (Build.VERSION.SDK_INT >= 30)
                createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            else createDisplayContext(display)
            val wm = ctx.getSystemService(WindowManager::class.java)
            val p = WindowManager.LayoutParams(
                dotSize, dotSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.LEFT
            p.x = x - dotSize / 2
            p.y = y - dotSize / 2
            val v = Dot(ctx)
            wm.addView(v, p)
            cursorView = v; cursorWm = wm; cursorParams = p
        } catch (e: Exception) {
            Log.w(TAG, "cursor", e)
        }
    }

    fun moveCursor(x: Int, y: Int) {
        val v = cursorView ?: return
        val p = cursorParams ?: return
        p.x = x - dotSize / 2
        p.y = y - dotSize / 2
        try { cursorWm?.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    fun removeCursor() {
        val v = cursorView ?: return
        try { cursorWm?.removeView(v) } catch (_: Exception) {}
        cursorView = null; cursorWm = null; cursorParams = null
    }

    // ------------------------------------------------------------------ gestures

    private fun gesture(displayId: Int, stroke: GestureDescription.StrokeDescription): GestureDescription {
        val b = GestureDescription.Builder().addStroke(stroke)
        if (Build.VERSION.SDK_INT >= 30) b.setDisplayId(displayId)
        return b.build()
    }

    fun tap(displayId: Int, x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y + 0.5f) }
        try {
            dispatchGesture(gesture(displayId, GestureDescription.StrokeDescription(path, 0, 60)), null, null)
        } catch (e: Exception) { Log.w(TAG, "tap", e) }
    }

    // Live drag (used for scrolling) built from continued strokes
    private var stroke: GestureDescription.StrokeDescription? = null
    private var dragDisplay = 0
    private var sx = 0f
    private var sy = 0f
    private var tx = 0f
    private var ty = 0f
    private var busy = false
    private var ending = false

    fun dragStart(displayId: Int, x: Float, y: Float) {
        dragDisplay = displayId
        sx = x; sy = y; tx = x; ty = y
        ending = false
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y + 0.5f) }
        val s = GestureDescription.StrokeDescription(path, 0, 30, true)
        stroke = s
        sx += 0.5f; sy += 0.5f
        dispatch(s)
    }

    fun dragTo(x: Float, y: Float) {
        tx = x; ty = y
        if (!busy) pump()
    }

    fun dragEnd() {
        ending = true
        if (!busy) pump()
    }

    private fun pump() {
        val s = stroke ?: return
        val ex = if (tx == sx && ty == sy) tx + 0.5f else tx
        val ey = if (tx == sx && ty == sy) ty + 0.5f else ty
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val cont = !ending
        val next = try { s.continueStroke(path, 0, 40, cont) } catch (e: Exception) { stroke = null; return }
        sx = ex; sy = ey
        stroke = if (cont) next else null
        dispatch(next)
    }

    private fun dispatch(s: GestureDescription.StrokeDescription) {
        busy = true
        val cb = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { after() }
            override fun onCancelled(gestureDescription: GestureDescription?) { stroke = null; after() }
        }
        val ok = try { dispatchGesture(gesture(dragDisplay, s), cb, main) } catch (e: Exception) { false }
        if (!ok) { busy = false; stroke = null }
    }

    private fun after() {
        busy = false
        if (stroke != null && (tx != sx || ty != sy || ending)) pump()
    }

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }

    /** Widest application window currently on [displayId], or null if unknown. */
    fun appWindowWidth(displayId: Int): Int? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val list = windowsOnAllDisplays.get(displayId) ?: return null
            list.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .maxOfOrNull { w -> android.graphics.Rect().also { w.getBoundsInScreen(it) }.width() }
        } catch (e: Exception) { null }
    }

    /** Packages that have an application window on [displayId], or null if unknown. */
    fun packagesOn(displayId: Int): Set<String>? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val list = windowsOnAllDisplays.get(displayId) ?: return emptySet()
            list.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root?.packageName?.toString() }
                .toSet()
        } catch (e: Exception) { null }
    }

    fun home() { performGlobalAction(GLOBAL_ACTION_HOME) }

    // ------------------------------------------------------------------ text

    private fun focusedInput(displayId: Int): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val all = windowsOnAllDisplays
                val list = all.get(displayId)
                if (list != null) {
                    for (w in list) {
                        val f = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (f != null) return f
                    }
                }
            } catch (_: Exception) {}
        }
        return findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private fun currentText(n: AccessibilityNodeInfo): String =
        if (n.isShowingHintText) "" else (n.text?.toString() ?: "")

    private fun setText(n: AccessibilityNodeInfo, t: String) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun typeText(displayId: Int, t: String) {
        val n = focusedInput(displayId) ?: return
        setText(n, currentText(n) + t)
    }

    fun backspace(displayId: Int, count: Int) {
        val n = focusedInput(displayId) ?: return
        setText(n, currentText(n).dropLast(count))
    }

    fun enter(displayId: Int) {
        val n = focusedInput(displayId) ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }
}
