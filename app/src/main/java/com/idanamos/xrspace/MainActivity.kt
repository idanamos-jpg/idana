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
        if (launcher) {
            space.windows.firstOrNull { it.title == "__home__" || it.web?.webView?.url == LAUNCHER_URL }?.let {
                space.focused = it; return
            }
        }
        val w = XWindow(nextId++, XWindow.Kind.WEB, 1280, 800)
        w.widthM = if (launcher) 1.5f else 1.4f
        try {
            w.web = WebWindowHost(this, w, url, launcher || url == LAUNCHER_URL)
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
        val base = if (space.windows.isEmpty()) 0f else space.viewYaw
        val aw = angularWidth(w)
        for (k in 0..14) {
            val step = ((k + 1) / 2) * (aw + 3f)
            val yaw = base + if (k % 2 == 1) -step else step
            val free = space.windows.none { o ->
                abs(Geo.wrap180(o.yaw - yaw)) < (aw + angularWidth(o)) / 2f + 1f && abs(o.pitch - space.viewPitch) < 20f
            }
            if (free) { w.yaw = Geo.wrap180(yaw); w.pitch = 0f; return }
        }
        w.yaw = base; w.pitch = 0f
    }

    fun closeWindow(w: XWindow) {
        if (!space.windows.remove(w)) return
        w.released = true
        if (space.focused === w) space.focused = space.windows.firstOrNull()
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
        val list = space.windows.sortedByDescending { it.yaw }
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
        for (ri in list.sortedBy { it.loadLabel(packageManager).toString().lowercase() }) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            arr.put(JSONObject().put("pkg", pkg).put("label", ri.loadLabel(packageManager).toString()))
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

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(34, 44, 62))
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(6) }
    }

    private fun row(vararg views: View): View {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        views.forEach { ll.addView(it) }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(ll)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
    }

    private fun seek(label: String, max: Int, value: Int, onChange: (Int) -> Unit): Pair<View, SeekBar> {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val tv = TextView(this).apply {
            text = label; setTextColor(Color.rgb(200, 210, 230)); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val sb = SeekBar(this).apply {
            this.max = max; progress = value
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        ll.addView(tv); ll.addView(sb)
        return ll to sb
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 16, 24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        statusText = TextView(this).apply {
            setTextColor(Color.rgb(170, 200, 255)); textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(statusText)

        stage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) }
            setBackgroundColor(Color.BLACK)
        }
        touchpad = TouchpadView(this, this)
        stage.addView(touchpad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stage)

        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        controls.addView(row(
            btn("⌂ בית") { openWeb(LAUNCHER_URL, launcher = true) },
            btn("◎ מרכוז") { recenter() },
            btn("⇄ חלון הבא") { focusNext() },
            btn("‹ אחורה") { space.focused?.web?.back() },
            btn("✕ סגור חלון") { space.focused?.let { closeWindow(it) } },
            btn("📱 שיקוף") { requestMirror() }
        ))
        anchorBox = CheckBox(this).apply {
            text = "מסך קבוע במקום"
            setTextColor(Color.WHITE)
            isChecked = space.anchored
            setOnCheckedChangeListener { _, c -> space.anchored = c; prefs.edit().putBoolean("anchored", c).apply() }
        }
        controls.addView(row(
            anchorBox,
            btn("A+") { space.focused?.web?.zoom(10) },
            btn("A−") { space.focused?.web?.zoom(-10) },
            btn("🖥 מחשב/נייד") {
                val on = space.focused?.web?.toggleDesktop()
                if (on != null) toast(if (on) "מצב מחשב" else "מצב נייד")
            },
            btn("⚙ הגדרות") { showSettings() }
        ))

        val (distRow, _) = seek("מרחק", 40, ((space.distance - 1f) / 0.1f).toInt()) {
            space.distance = 1f + it * 0.1f; prefs.edit().putFloat("distance", space.distance).apply()
        }
        val (curveRow, _) = seek("עיקול", 100, (space.curve * 100).toInt()) {
            space.curve = it / 100f; prefs.edit().putFloat("curve", space.curve).apply()
        }
        val (sizeRow, sb) = seek("גודל חלון", 100, 30) { p ->
            space.focused?.let { it.widthM = 0.4f + p * 0.04f }
        }
        sizeSeek = sb
        controls.addView(distRow)
        controls.addView(curveRow)
        controls.addView(sizeRow)

        input = EditText(this).apply {
            hint = "טקסט להקלדה בחלון, או כתובת / חיפוש"
            setHintTextColor(Color.rgb(120, 130, 150))
            setTextColor(Color.WHITE)
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnEditorActionListener { _, _, _ -> sendText(); true }
        }
        controls.addView(input)
        controls.addView(row(
            btn("הקלד") { sendText() },
            btn("⌫") { space.focused?.web?.backspace() },
            btn("↵ Enter") { space.focused?.web?.enter() },
            btn("🌐 פתח כתובת/חיפוש") {
                val t = input.text.toString()
                val f = space.focused
                if (f?.web != null && f.web?.webView?.url != LAUNCHER_URL) f.web?.openInput(t) else openWeb(urlFor(t))
                input.setText("")
            },
            btn("＋ חלון חדש") { val t = input.text.toString(); openWeb(if (t.isBlank()) "https://www.google.com" else urlFor(t)); input.setText("") }
        ))
        root.addView(controls)
        setContentView(root)
        syncSizeSeek()
    }

    private fun urlFor(t: String): String {
        val s = t.trim()
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            !s.contains(' ') && s.contains('.') -> "https://$s"
            else -> "https://www.google.com/search?q=" + android.net.Uri.encode(s)
        }
    }

    private fun sendText() {
        val t = input.text.toString()
        if (t.isEmpty()) return
        space.focused?.web?.typeText(t)
        input.setText("")
    }

    private fun syncSizeSeek() {
        if (!::sizeSeek.isInitialized) return
        val f = space.focused ?: return
        sizeSeek.progress = ((f.widthM - 0.4f) / 0.04f).toInt().coerceIn(0, 100)
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        val g = if (glasses != null) "משקפיים: מחוברים ✓" else "משקפיים: לא מחוברים (תצוגה מקדימה בטלפון)"
        val mode = if (space.headTracking()) "· מסך קבוע במקום" else "· נעול לראש"
        statusText.text = "$g   ·   $imuStatus   $mode   ·   חלונות: ${space.windows.size}"
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
