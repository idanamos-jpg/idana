package com.idanamos.xrspace

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Rational
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan

class MainActivity : Activity() {

    companion object {
        const val LAUNCHER_URL = "file:///android_asset/launcher.html"
        const val HUD_URL = "file:///android_asset/hud.html"
        private const val REQ_MIRROR = 41
    }

    val space = Space()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var dm: DisplayManager
    lateinit var tracker: ImuTracker

    private var glasses: GlassesPresentation? = null
    private var preview: GLSurfaceView? = null
    private var previewRenderer: SpaceRenderer? = null

    private lateinit var stage: FrameLayout
    private lateinit var touchpad: TouchpadView
    private lateinit var controls: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var input: EditText
    private lateinit var sizeSeek: SeekBar
    private lateinit var anchorBox: CheckBox

    private var imuStatus = "חיישן תנועה: לא מחובר"
    private var nextId = 1
    private var mirrorWindow: XWindow? = null
    private var pendingLaunch: String? = null
    private var scrollWin: XWindow? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("xrspace", MODE_PRIVATE)
        dm = getSystemService(DisplayManager::class.java)

        space.distance = prefs.getFloat("distance", 2.4f)
        space.curve = prefs.getFloat("curve", 0.4f)
        space.fovY = prefs.getFloat("fovY", 23.5f)
        space.anchored = prefs.getBoolean("anchored", true)
        space.brightness = prefs.getFloat("brightness", 1f)

        tracker = ImuTracker(this) { msg -> runOnUiThread { imuStatus = msg; updateStatus() } }
        tracker.invertYaw = prefs.getBoolean("invYaw", false)
        tracker.invertPitch = prefs.getBoolean("invPitch", false)
        tracker.invertRoll = prefs.getBoolean("invRoll", false)
        tracker.gravityCorrection = prefs.getBoolean("gravity", true)
        space.tracker = tracker

        buildUi()

        MirrorService.onEnded = { runOnUiThread { onMirrorEnded() } }

        dm.registerDisplayListener(displayListener, main)
        openWeb(LAUNCHER_URL, launcher = true)
        updateDisplays()
        tracker.start()

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            setPictureInPictureParams(
                PictureInPictureParams.Builder().setAspectRatio(Rational(4, 3)).setAutoEnterEnabled(true).build()
            )
        }
        // Status refresher
        main.post(object : Runnable {
            override fun run() { updateStatus(); main.postDelayed(this, 1000) }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tracker.start()
        updateDisplays()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT < 31) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        controls.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        statusText.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        touchpad.showHint = !isInPictureInPictureMode
        touchpad.invalidate()
    }

    override fun onDestroy() {
        dm.unregisterDisplayListener(displayListener)
        tracker.stop()
        for (w in space.windows.toList()) closeWindow(w)
        stopService(Intent(this, MirrorService::class.java))
        try { glasses?.dismiss() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- displays

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { updateDisplays(); main.postDelayed({ tracker.start() }, 1500) }
        override fun onDisplayRemoved(displayId: Int) { updateDisplays() }
        override fun onDisplayChanged(displayId: Int) {}
    }

    private fun findGlassesDisplay(): Display? =
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY &&
                (it.flags and Display.FLAG_PRIVATE) == 0 &&
                !it.name.startsWith("xr-")
        }

    private fun updateDisplays() {
        val d = findGlassesDisplay()
        val current = glasses
        if (d != null && (current == null || current.display.displayId != d.displayId)) {
            showOnGlasses(d)
        } else if (d == null && current != null) {
            detach(current.glView, current.renderer)
            try { current.dismiss() } catch (_: Exception) {}
            glasses = null
            showPreview()
        } else if (d == null && preview == null) {
            showPreview()
        }
        updateStatus()
    }

    private fun detach(view: GLSurfaceView, renderer: SpaceRenderer) {
        val latch = CountDownLatch(1)
        view.queueEvent { renderer.detachAll(); latch.countDown() }
        try { latch.await(700, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
    }

    private fun showOnGlasses(d: Display) {
        preview?.let { p ->
            previewRenderer?.let { detach(p, it) }
            stage.removeView(p)
        }
        preview = null
        previewRenderer = null
        glasses?.let { g ->
            detach(g.glView, g.renderer)
            try { g.dismiss() } catch (_: Exception) {}
        }
        try {
            val g = GlassesPresentation(this, d)
            g.show()
            glasses = g
        } catch (e: Exception) {
            glasses = null
            toast("לא ניתן להציג במשקפיים: ${e.message}")
            showPreview()
        }
    }

    private fun showPreview() {
        if (preview != null) return
        val r = SpaceRenderer(space) { w -> runOnUiThread { w.recreateSurface() } }
        val v = GLSurfaceView(this)
        v.setEGLContextClientVersion(2)
        v.preserveEGLContextOnPause = true
        v.setRenderer(r)
        stage.addView(v, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        preview = v
        previewRenderer = r
    }

    /** Called (main thread) when the glasses are unplugged. */
    fun onGlassesRemoved(p: GlassesPresentation) {
        detach(p.glView, p.renderer)
        if (glasses === p) glasses = null
        main.post { updateDisplays() }
    }

    private fun activeRenderer(): Pair<GLSurfaceView, SpaceRenderer>? {
        glasses?.let { return it.glView to it.renderer }
        val p = preview ?: return null
        val r = previewRenderer ?: return null
        return p to r
    }

    // ---------------------------------------------------------------- windows

    fun openWeb(url: String, launcher: Boolean = false) {
        if (space.arMode && !launcher && url != LAUNCHER_URL) { openArPanel(url); return }
        if (space.arMode) setArMode(false)
        if (launcher) {
            space.windows.firstOrNull { it.title == "__home__" || it.web?.webView?.url == LAUNCHER_URL }?.let {
                space.focused = it; return
            }
        }
        val home = launcher || url == LAUNCHER_URL
        // Home: wide panoramic screen (2400x1000, CSS 1200x500). Others: 1280x800.
        val w = if (home) XWindow(nextId++, XWindow.Kind.WEB, 2400, 1000) else XWindow(nextId++, XWindow.Kind.WEB, 1280, 800)
        w.widthM = if (home) 3.0f else 1.7f
        try {
            w.web = if (home) WebWindowHost(this, w, url, true, 320) else WebWindowHost(this, w, url, false)
        } catch (e: Exception) {
            toast("שגיאה בפתיחת חלון: ${e.message}")
            return
        }
        place(w)
        space.windows.add(w)
        space.focused = w
        syncSizeSeek()
    }

    private fun angularWidth(w: XWindow): Float =
        Math.toDegrees(2.0 * atan(w.widthM / 2.0 / space.distance)).toFloat()

    private fun place(w: XWindow) {
        val base = if (space.visibleWindows().isEmpty()) 0f else space.viewYaw
        val aw = angularWidth(w)
        for (k in 0..14) {
            val step = ((k + 1) / 2) * (aw + 3f)
            val yaw = base + if (k % 2 == 1) -step else step
            val free = space.visibleWindows().none { o ->
                abs(Geo.wrap180(o.yaw - yaw)) < (aw + angularWidth(o)) / 2f + 1f && abs(o.pitch - space.viewPitch) < 20f
            }
            if (free) { w.yaw = Geo.wrap180(yaw); w.pitch = 0f; return }
        }
        w.yaw = base; w.pitch = 0f
    }

    fun closeWindow(w: XWindow) {
        if (!space.windows.remove(w)) return
        w.released = true
        if (space.focused === w) space.focused = space.visibleWindows().firstOrNull()
        if (w.kind == XWindow.Kind.MIRROR) {
            mirrorWindow = null
            stopService(Intent(this, MirrorService::class.java))
        }
        w.web?.destroy()
        w.web = null
        try { w.vd?.release() } catch (_: Exception) {}
        val ar = activeRenderer()
        if (ar != null && w.attachedTo === ar.second) {
            ar.second.queueRelease(w)
        } else {
            try { w.surface.release() } catch (_: Exception) {}
            try { w.surfaceTexture.release() } catch (_: Exception) {}
        }
        syncSizeSeek()
    }

    private fun focusNext() {
        val list = space.visibleWindows().sortedByDescending { it.yaw }
        if (list.isEmpty()) return
        val i = list.indexOf(space.focused)
        val n = list[(i + 1) % list.size]
        space.focused = n
        if (!space.headTracking()) { space.cursorYaw = n.yaw; space.cursorPitch = n.pitch }
        syncSizeSeek()
    }

    // ---------------------------------------------------------------- pointer / input

    fun ensureCursorVisible() {
        val dy = abs(Geo.wrap180(space.cursorYaw - space.viewYaw))
        val dp = abs(space.cursorPitch - space.viewPitch)
        if (dy > space.fovX() / 2f - 1f || dp > space.fovY / 2f - 1f) {
            space.cursorYaw = space.viewYaw
            space.cursorPitch = space.viewPitch
        }
    }

    fun moveCursor(dYaw: Float, dPitch: Float, drag: XWindow?) {
        space.cursorShownUntil = System.currentTimeMillis() + 3000
        space.cursorYaw = Geo.wrap180(space.cursorYaw - dYaw)
        space.cursorPitch = (space.cursorPitch - dPitch).coerceIn(-80f, 80f)
        if (drag != null) {
            drag.yaw = Geo.wrap180(drag.yaw - dYaw)
            drag.pitch = (drag.pitch - dPitch).coerceIn(-70f, 70f)
        }
        if (!space.headTracking()) {
            // Head-locked: keep pointer inside the view
            val hx = space.fovX() / 2f
            val hy = space.fovY / 2f
            val ref = space.focused
            if (drag == null && ref != null) {
                space.cursorYaw = space.viewYaw + Geo.wrap180(space.cursorYaw - space.viewYaw).coerceIn(-hx, hx)
                space.cursorPitch = space.viewPitch + (space.cursorPitch - space.viewPitch).coerceIn(-hy, hy)
            }
        }
    }

    fun click() {
        val h = space.cursorHit() ?: return
        if (space.focused !== h.window) resetLive()
        space.focused = h.window
        syncSizeSeek()
        when (h.window.kind) {
            XWindow.Kind.WEB -> h.window.web?.tap(h.px, h.py)
            XWindow.Kind.MIRROR -> toast("חלון שיקוף: השליטה דרך מסך הטלפון")
        }
    }

    fun scrollStart() {
        val h = space.cursorHit()
        val w = h?.window ?: return
        if (w.kind != XWindow.Kind.WEB) return
        scrollWin = w
        w.web?.dragStart(h.px, h.py)
    }

    fun scrollBy(dx: Float, dy: Float) {
        scrollWin?.web?.dragBy(dx * 1.3f, dy * 1.3f)
    }

    fun scrollEnd() {
        scrollWin?.web?.dragEnd()
        scrollWin = null
    }

    fun onWindowResized(w: XWindow) {
        if (w === space.focused) syncSizeSeek()
    }

    private fun recenter() {
        tracker.recenter()
        space.cursorYaw = 0f
        space.cursorPitch = 0f
        if (!tracker.active) toast("מרכוז")
    }

    // ---------------------------------------------------------------- mirror / apps

    fun requestMirror() {
        if (space.arMode) setArMode(false)
        mirrorWindow?.let { space.focused = it; return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MIRROR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MIRROR) return
        if (resultCode != RESULT_OK || data == null) {
            pendingLaunch = null
            toast("שיקוף המסך בוטל")
            return
        }
        val metrics = resources.displayMetrics
        var w: Int
        var h: Int
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            w = metrics.widthPixels; h = metrics.heightPixels
        }
        val scale = minOf(1f, 1600f / maxOf(w, h))
        w = ((w * scale).toInt() / 2) * 2
        h = ((h * scale).toInt() / 2) * 2
        val win = XWindow(nextId++, XWindow.Kind.MIRROR, w, h)
        win.title = "שיקוף טלפון"
        win.widthM = if (w > h) 1.4f else 0.9f
        place(win)
        space.windows.add(win)
        space.focused = win
        mirrorWindow = win
        MirrorService.pending = MirrorService.Params(resultCode, data, win, (metrics.densityDpi * scale).toInt().coerceAtLeast(120))
        startForegroundService(Intent(this, MirrorService::class.java))
        syncSizeSeek()
        pendingLaunch?.let { pkg ->
            pendingLaunch = null
            main.postDelayed({ doLaunch(pkg) }, 700)
        }
    }

    private fun onMirrorEnded() {
        mirrorWindow?.let { closeWindow(it) }
        mirrorWindow = null
    }

    fun launchApp(pkg: String) {
        if (mirrorWindow == null) {
            pendingLaunch = pkg
            requestMirror()
        } else doLaunch(pkg)
    }

    private fun doLaunch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: run { toast("לא ניתן לפתוח"); return }
        mirrorWindow?.let { space.focused = it }
        enterPip()
        main.postDelayed({
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { toast("לא ניתן לפתוח") }
        }, 250)
    }

    private fun enterPip() {
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(4, 3)).build())
        } catch (_: Exception) {}
    }

    fun appsJson(): String {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL)
        val arr = JSONArray()
        val seen = HashSet<String>()
        val favs = favorites()
        val items = list.map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
        for ((pkg, label) in sortApps(items, { it.first }, { it.second })) {
            if (pkg == packageName || !seen.add(pkg)) continue
            arr.put(JSONObject().put("pkg", pkg).put("label", (if (favs.contains(pkg)) "⭐" else "") + label))
        }
        return arr.toString()
    }

    fun appIcon(pkg: String): String = try {
        val d = packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, 96, 96)
        d.draw(c)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) { "" }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val cBtn = Color.rgb(32, 42, 60)
    private val cPanel = Color.rgb(18, 24, 36)
    private val cAccent = Color.rgb(47, 127, 224)

    private fun rounded(color: Int, r: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    /** Large, easy-to-hit button (usable without looking while wearing the glasses). */
    private fun btn(text: String, color: Int = cBtn, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        background = rounded(color)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(62), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
    }

    private fun gridRow(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        views.forEach { addView(it) }
    }

    private fun seek(label: String, max: Int, value: Int, onChange: (Int) -> Unit): Pair<View, SeekBar> {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val tv = TextView(this).apply {
            text = label; setTextColor(Color.rgb(210, 220, 240)); textSize = 16f
            layoutParams = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val sb = SeekBar(this).apply {
            this.max = max; progress = value
            minimumHeight = dp(40)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        ll.addView(tv); ll.addView(sb)
        return ll to sb
    }

    // ---- tabs

    private enum class Tab(val icon: String, val label: String) {
        CONTROL("🎮", "שליטה"), HOME("⌂", "בית"), KEYS("⌨", "מקלדת"), DISPLAY("☀", "תצוגה")
    }

    private var tab = Tab.CONTROL
    private lateinit var panel: FrameLayout
    private val tabViews = HashMap<Tab, View>()
    private val tabButtons = HashMap<Tab, TextView>()

    private fun selectTab(t: Tab, openKeyboard: Boolean = true) {
        tab = t
        panel.removeAllViews()
        panel.addView(tabViews[t]!!)
        for ((k, v) in tabButtons) {
            v.background = rounded(if (k == t) cAccent else cBtn, 12)
        }
        // Home tab needs room for the app grid: give it ~55% of the screen
        val big = t == Tab.HOME
        stage.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, if (big) 0.8f else 1f).apply { topMargin = dp(6) }
        controls.layoutParams = if (big) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.layoutParams = if (big) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (t == Tab.KEYS && openKeyboard) showKeyboard()
        if (t != Tab.KEYS && ::liveInput.isInitialized && liveInput.hasFocus()) hideKeyboard()
        if (t == Tab.HOME) loadPhoneApps()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 13, 20))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        statusText = TextView(this).apply {
            setTextColor(Color.rgb(160, 190, 240)); textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
        }
        root.addView(statusText)

        stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        touchpad = TouchpadView(this, this)
        stage.addView(touchpad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stage)

        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel = FrameLayout(this).apply {
            background = rounded(cPanel, 18)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        controls.addView(panel)

        tabViews[Tab.CONTROL] = buildControlTab()
        tabViews[Tab.HOME] = buildHomeTab()
        tabViews[Tab.KEYS] = buildKeysTab()
        tabViews[Tab.DISPLAY] = buildDisplayTab()

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)).apply { topMargin = dp(6) }
        }
        for (t in Tab.values()) {
            val tv = TextView(this).apply {
                text = "${t.icon}\n${t.label}"
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
                setOnClickListener { selectTab(t) }
            }
            tabButtons[t] = tv
            nav.addView(tv)
        }
        controls.addView(nav)
        root.addView(controls)
        setContentView(root)
        selectTab(Tab.CONTROL)
        syncSizeSeek()
    }

    private fun buildControlTab(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modeBtn = btn("") { setArMode(!space.arMode) }
        hideBtn = btn("") { setHidden(!space.hidden) }
        updateModeButtons()
        v.addView(gridRow(modeBtn, hideBtn))
        v.addView(gridRow(
            btn("◎ מרכוז") { recenter() },
            btn("⇄ חלון הבא") { focusNext() },
            btn("‹ אחורה") { space.focused?.web?.back() }
        ))
        v.addView(gridRow(
            btn("⌂ בית במשקפיים") { openWeb(LAUNCHER_URL, launcher = true) },
            btn("📱 שיקוף טלפון") { requestMirror() },
            btn("✕ סגור חלון", Color.rgb(90, 36, 40)) { space.focused?.let { closeWindow(it) } }
        ))
        anchorBox = CheckBox(this).apply {
            text = "מסך קבוע במקום (מעקב ראש)"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = space.anchored
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnCheckedChangeListener { _, c -> space.anchored = c; prefs.edit().putBoolean("anchored", c).apply() }
        }
        v.addView(anchorBox)
        return v
    }

    private fun buildKeysTab(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        liveInput = EditText(this).apply {
            hint = "מה שתקליד כאן נכתב ישר בחלון שבמשקפיים"
            setHintTextColor(Color.rgb(120, 140, 170))
            setTextColor(Color.WHITE)
            textSize = 18f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = rounded(Color.rgb(28, 40, 62), 12)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            setOnEditorActionListener { _, _, _ -> space.focused?.web?.enter(); resetLive(); true }
            addTextChangedListener(liveWatcher)
        }
        resetLive()
        v.addView(liveInput)
        v.addView(gridRow(
            btn("⌫ מחק") { space.focused?.web?.backspace() },
            btn("↵ Enter") { space.focused?.web?.enter(); resetLive() },
            btn("⌨ פתח מקלדת") { showKeyboard() }
        ))
        v.addView(gridRow(
            btn("A+ הגדל טקסט") { space.focused?.web?.zoom(10) },
            btn("A− הקטן טקסט") { space.focused?.web?.zoom(-10) },
            btn("🖥 מחשב/נייד") {
                val on = space.focused?.web?.toggleDesktop()
                if (on != null) toast(if (on) "מצב מחשב" else "מצב נייד")
            }
        ))
        return v
    }

    private fun buildDisplayTab(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (brightRow, _) = seek("☀ בהירות", 100, (space.brightness * 100).toInt()) {
            space.brightness = (it.coerceAtLeast(5)) / 100f
            prefs.edit().putFloat("brightness", space.brightness).apply()
        }
        val (sizeRow, sb) = seek("⤢ גודל חלון", 100, 30) { p ->
            space.focused?.let { it.widthM = 0.4f + p * 0.04f }
        }
        sizeSeek = sb
        val (distRow, _) = seek("↔ מרחק", 40, ((space.distance - 1f) / 0.1f).toInt()) {
            space.distance = 1f + it * 0.1f; prefs.edit().putFloat("distance", space.distance).apply()
        }
        val (curveRow, _) = seek("◠ עיקול", 100, (space.curve * 100).toInt()) {
            space.curve = it / 100f; prefs.edit().putFloat("curve", space.curve).apply()
        }
        v.addView(brightRow); v.addView(sizeRow); v.addView(distRow); v.addView(curveRow)
        v.addView(gridRow(btn("⚙ הגדרות חיישן ומתקדם") { showSettings() }))
        return v
    }

    // ---- Home tab: launcher on the phone

    private class AppItem(val pkg: String, val label: String, val icon: Drawable?)
    private val phoneApps = ArrayList<AppItem>()
    private lateinit var appsAdapter: BaseAdapter
    private var appsLoaded = false

    private val defaultFavs = listOf(
        "com.whatsapp", "com.waze", "com.google.android.apps.maps", "com.openai.chatgpt",
        "com.google.android.gm", "com.google.android.youtube", "com.android.chrome",
        "com.google.android.calendar", "com.spotify.music", "com.google.android.apps.docs"
    )

    private fun favorites(): List<String> {
        val raw = prefs.getString("favs", null) ?: return defaultFavs
        return raw.split(',').filter { it.isNotBlank() }
    }

    private fun toggleFavorite(pkg: String): Boolean {
        val f = favorites().toMutableList()
        val added = if (f.remove(pkg)) false else { f.add(pkg); true }
        prefs.edit().putString("favs", f.joinToString(",")).apply()
        return added
    }

    /** Favorites first (in favorite order), then alphabetical. */
    private fun <T> sortApps(items: List<T>, pkg: (T) -> String, label: (T) -> String): List<T> {
        val favs = favorites()
        return items.sortedWith(compareBy<T> { val i = favs.indexOf(pkg(it)); if (i < 0) Int.MAX_VALUE else i }
            .thenBy { label(it).lowercase() })
    }

    private fun loadPhoneApps() {
        if (appsLoaded) return
        appsLoaded = true
        Thread {
            val pm = packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val seen = HashSet<String>()
            val list = ArrayList<AppItem>()
            for (ri in pm.queryIntentActivities(i, PackageManager.MATCH_ALL)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
                list.add(AppItem(pkg, ri.loadLabel(pm).toString(), icon))
            }
            val sorted = sortApps(list, { it.pkg }, { it.label })
            runOnUiThread {
                phoneApps.clear(); phoneApps.addAll(sorted)
                appsAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun buildHomeTab(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        input = EditText(this).apply {
            hint = "🔎 חיפוש או כתובת אתר"
            setHintTextColor(Color.rgb(120, 130, 150))
            setTextColor(Color.WHITE)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(Color.rgb(28, 40, 62), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            setOnEditorActionListener { _, _, _ -> openFromInput(); true }
        }
        val go = btn("פתח") { openFromInput() }
        go.layoutParams = LinearLayout.LayoutParams(dp(90), dp(52)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        v.addView(gridRow(input, go))

        // Sites strip
        val sites = listOf(
            "🔎 Google" to "https://www.google.com", "▶️ YouTube" to "https://m.youtube.com",
            "✉️ Gmail" to "https://mail.google.com", "💬 WhatsApp" to "https://web.whatsapp.com",
            "🗺️ מפות" to "https://www.google.com/maps", "🤖 ChatGPT" to "https://chatgpt.com",
            "📁 Drive" to "https://drive.google.com", "📅 יומן" to "https://calendar.google.com",
            "📰 ynet" to "https://www.ynet.co.il"
        )
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, url) in sites) {
            strip.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = rounded(cBtn, 20)
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                setOnClickListener { openWeb(url) }
            })
        }
        v.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })

        v.addView(TextView(this).apply {
            text = "אפליקציות · ⭐ מועדפים ראשונים · לחיצה ארוכה = הוספה/הסרה ממועדפים"
            textSize = 12f
            setTextColor(Color.rgb(140, 160, 190))
            setPadding(dp(8), dp(6), dp(8), dp(2))
        })

        appsAdapter = object : BaseAdapter() {
            override fun getCount() = phoneApps.size
            override fun getItem(position: Int) = phoneApps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(2), dp(8), dp(2), dp(6))
                    addView(ImageView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)) })
                    addView(TextView(this@MainActivity).apply {
                        textSize = 12f; setTextColor(Color.WHITE); maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setPadding(0, dp(4), 0, 0)
                    })
                }
                val a = phoneApps[position]
                (cell.getChildAt(0) as ImageView).setImageDrawable(a.icon)
                val fav = favorites().contains(a.pkg)
                (cell.getChildAt(1) as TextView).text = (if (fav) "⭐" else "") + a.label
                return cell
            }
        }
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(84)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = appsAdapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnItemClickListener { _, _, pos, _ -> launchApp(phoneApps[pos].pkg) }
            setOnItemLongClickListener { _, _, pos, _ ->
                val a = phoneApps[pos]
                toast(if (toggleFavorite(a.pkg)) "⭐ נוסף למועדפים: ${a.label}" else "הוסר מהמועדפים: ${a.label}")
                val sorted = sortApps(phoneApps.toList(), { it.pkg }, { it.label })
                phoneApps.clear(); phoneApps.addAll(sorted)
                appsAdapter.notifyDataSetChanged()
                true
            }
        }
        v.addView(grid)
        v.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return v
    }

    private fun urlFor(t: String): String {
        val s = t.trim()
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            !s.contains(' ') && s.contains('.') -> "https://$s"
            else -> "https://www.google.com/search?q=" + android.net.Uri.encode(s)
        }
    }

    private fun openFromInput() {
        val t = input.text.toString()
        if (t.isBlank()) return
        val f = space.focused
        if (f?.web != null && f.web?.webView?.url != LAUNCHER_URL) f.web?.openInput(t) else openWeb(urlFor(t))
        input.setText("")
    }

    // ---------------------------------------------------------------- live keyboard

    private lateinit var liveInput: EditText
    private var liveGuard = false
    private val sentinel = "\u200B"

    private val liveWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (liveGuard || s == null) return
            val web = space.focused?.web ?: return
            if (before > 0) web.backspace(before)
            if (count > 0) {
                val ins = s.subSequence(start, start + count).toString().replace(sentinel, "")
                if (ins.isNotEmpty()) web.typeText(ins)
            }
        }
        override fun afterTextChanged(e: Editable?) {
            if (liveGuard || e == null) return
            if (e.isEmpty() || e[0] != '\u200B') {
                liveGuard = true
                e.insert(0, sentinel)
                liveGuard = false
            }
        }
    }

    private fun resetLive() {
        if (!::liveInput.isInitialized) return
        liveGuard = true
        liveInput.setText(sentinel)
        liveInput.setSelection(liveInput.text.length)
        liveGuard = false
    }

    fun showKeyboard() {
        if (isInPictureInPictureMode || !::liveInput.isInitialized) return
        if (tab != Tab.KEYS) selectTab(Tab.KEYS, openKeyboard = false)
        resetLive()
        liveInput.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        liveInput.post { imm.showSoftInput(liveInput, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(liveInput.windowToken, 0)
        liveInput.clearFocus()
    }

    private fun syncSizeSeek() {
        if (!::sizeSeek.isInitialized) return
        val f = space.focused ?: return
        sizeSeek.progress = ((f.widthM - 0.4f) / 0.04f).toInt().coerceIn(0, 100)
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val g = if (glasses != null) "משקפיים: מחוברים ✓" else "משקפיים: לא מחוברים (תצוגה מקדימה בטלפון)"
        val mode = (if (space.arMode) "· 🕶 משקפיים חכמים " else "") + if (space.headTracking()) "· קבוע במקום" else "· נעול לראש"
        statusText.text = "$g   ·   $imuStatus   $mode   ·   חלונות: ${space.visibleWindows().size}"
    }

    private fun showSettings() {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun box(label: String, value: Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply {
            text = label; isChecked = value
            setOnCheckedChangeListener { _, c -> set(c) }
        }
        ll.addView(box("הפוך כיוון ימינה/שמאלה", tracker.invertYaw) { tracker.invertYaw = it; prefs.edit().putBoolean("invYaw", it).apply() })
        ll.addView(box("הפוך כיוון למעלה/למטה", tracker.invertPitch) { tracker.invertPitch = it; prefs.edit().putBoolean("invPitch", it).apply() })
        ll.addView(box("הפוך הטיית ראש", tracker.invertRoll) { tracker.invertRoll = it; prefs.edit().putBoolean("invRoll", it).apply() })
        ll.addView(box("תיקון לפי כוח הכבידה", tracker.gravityCorrection) { tracker.gravityCorrection = it; prefs.edit().putBoolean("gravity", it).apply() })
        val (fovRow, _) = seek("שדה ראייה", 30, (space.fovY - 15f).toInt()) {
            space.fovY = 15f + it; prefs.edit().putFloat("fovY", space.fovY).apply()
        }
        ll.addView(fovRow)
        val (sensRow, _) = seek("רגישות סמן", 100, (touchpad.sensitivity * 1000).toInt()) {
            touchpad.sensitivity = (it.coerceAtLeast(5)) / 1000f
        }
        ll.addView(sensRow)
        AlertDialog.Builder(this)
            .setTitle("הגדרות")
            .setView(ll)
            .setPositiveButton("סגור", null)
            .setNeutralButton("כיול חיישן") { _, _ -> tracker.recalibrate(); toast("השאר את הראש יציב לשנייה") }
            .setNegativeButton("חבר חיישן מחדש") { _, _ -> tracker.stop(); tracker.start() }
            .show()
    }

    // ---------------------------------------------------------------- Smart Glasses (AR) mode

    private lateinit var modeBtn: Button
    private lateinit var hideBtn: Button
    private var focusedWork: XWindow? = null
    private var focusedAr: XWindow? = null

    private fun updateModeButtons() {
        if (!::modeBtn.isInitialized) return
        modeBtn.text = if (space.arMode) "🖥 עבור למצב מסכים" else "🕶 עבור למשקפיים חכמים"
        modeBtn.background = rounded(if (space.arMode) Color.rgb(20, 110, 80) else Color.rgb(60, 50, 120))
        hideBtn.text = if (space.hidden) "👁 הצג תוכן" else "🙈 הסתר הכל"
        hideBtn.background = rounded(if (space.hidden) Color.rgb(160, 45, 45) else cBtn)
    }

    fun setArMode(on: Boolean) {
        if (space.arMode == on) return
        if (on) focusedWork = space.focused else focusedAr = space.focused
        space.arMode = on
        resetLive()
        if (on && space.windows.none { it.ar && !it.released }) openHud()
        space.focused = (if (on) focusedAr else focusedWork)?.takeIf { space.visible(it) }
            ?: space.visibleWindows().firstOrNull()
        space.cursorYaw = space.viewYaw
        space.cursorPitch = space.viewPitch
        syncSizeSeek()
        updateModeButtons()
        updateStatus()
    }

    fun setHidden(h: Boolean) {
        space.hidden = h
        updateModeButtons()
    }

    /** Minimal info panel (time / date / battery) at the lower-right edge of the view. */
    private fun openHud() {
        val w = XWindow(nextId++, XWindow.Kind.WEB, 840, 360)
        w.ar = true
        w.widthM = 0.62f
        // Lower-right edge of the current view, leaving the centre free
        w.yaw = Geo.wrap180(space.viewYaw - 14f)
        w.pitch = (space.viewPitch - 7f).coerceIn(-60f, 60f)
        try {
            w.web = WebWindowHost(this, w, HUD_URL, true, 320)
        } catch (e: Exception) {
            toast("שגיאה בפתיחת חלונית: ${e.message}")
            return
        }
        w.web?.webView?.setBackgroundColor(Color.BLACK)
        space.windows.add(w)
        space.focused = w
    }

    /** Generic compact AR panel for a web page, shown in dark mode. */
    private fun openArPanel(url: String) {
        val w = XWindow(nextId++, XWindow.Kind.WEB, 900, 1100)
        w.ar = true
        w.invert = true
        w.widthM = 0.7f
        // Left edge of the current view
        w.yaw = Geo.wrap180(space.viewYaw + 15f)
        w.pitch = space.viewPitch.coerceIn(-60f, 60f)
        try {
            w.web = WebWindowHost(this, w, url, false)
        } catch (e: Exception) {
            toast("שגיאה בפתיחת חלונית: ${e.message}")
            return
        }
        space.windows.add(w)
        space.focused = w
        syncSizeSeek()
    }

    fun batteryLevel(): Int =
        getSystemService(android.os.BatteryManager::class.java)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun isCharging(): Boolean = getSystemService(android.os.BatteryManager::class.java).isCharging

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
