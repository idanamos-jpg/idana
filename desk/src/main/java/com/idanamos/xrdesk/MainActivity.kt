package com.idanamos.xrdesk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Phone side: virtual-monitor size & distance, app launcher and touchpad for the desktop in the glasses. */
class MainActivity : Activity(), PadView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var dm: DisplayManager

    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var preview: PreviewView
    private lateinit var readout: TextView
    private lateinit var sizeSeek: SeekBar
    private lateinit var sizeLabel: TextView
    private lateinit var distSeek: SeekBar
    private lateinit var distLabel: TextView
    private lateinit var heightSeek: SeekBar
    private lateinit var fovSeek: SeekBar
    private lateinit var fovLabel: TextView
    private val aspectBtns = mutableListOf<Button>()
    private val slotBtns = mutableListOf<Button>()
    private lateinit var appsBox: LinearLayout
    private var appFilter = ""

    private lateinit var deskPage: View
    private lateinit var controlPage: View
    private lateinit var tabDesk: Button
    private lateinit var tabControl: Button

    private val onChange: () -> Unit = { refresh() }

    private val bg = Color.rgb(11, 14, 22)
    private val card = Color.rgb(22, 27, 40)
    private val accent = Color.rgb(63, 160, 255)
    private val dim = Color.rgb(160, 175, 200)

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Desk.init(this)
        dm = getSystemService(DisplayManager::class.java)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(buildUi())
        Desk.listen(onChange)
        Apps.load(this)
        dm.registerDisplayListener(displayListener, main)
        refresh()
        if (Glasses.display(this) != null && DesktopActivity.instance == null) main.postDelayed({ Glasses.startDesktop(this) }, 400)
    }

    override fun onDestroy() {
        Desk.unlisten(onChange)
        dm.unregisterDisplayListener(displayListener)
        DeskAccessibilityService.instance?.removeCursor()
        super.onDestroy()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            refresh()
            main.postDelayed({ if (Glasses.display(this@MainActivity) != null) Glasses.startDesktop(this@MainActivity) }, 900)
        }
        override fun onDisplayRemoved(displayId: Int) {
            cursorShown = false
            Glasses.windows.clear()
            refresh()
        }
        override fun onDisplayChanged(displayId: Int) { refresh() }
    }

    // ---------------------------------------------------------------- UI building helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int = 14) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun text(s: String, size: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun btn(s: String, color: Int = Color.rgb(36, 44, 64), onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
        background = round(color, 12)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        minHeight = dp(44); minimumHeight = dp(44)
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }

    private fun row(vararg v: View, equal: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in v) addView(x, if (equal) lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) else lp(ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun section(title: String, vararg content: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(card, 18)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(text(title, 16f, Color.WHITE, true), lp())
        for (c in content) addView(c, lp())
    }

    private fun seek(max: Int, onChange: (Int) -> Unit, onStop: () -> Unit = {}) = SeekBar(this).apply {
        this.max = max
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { onStop() }
        })
    }

    private fun labeled(label: TextView, seek: SeekBar, minus: () -> Unit, plus: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label, lp())
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btn("−") { minus() }, lp(dp(52)))
            addView(seek, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn("+") { plus() }, lp(dp(52)))
        }, lp())
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(10), dp(28), dp(10), dp(8))
        }
        root.addView(text("XR Desk", 24f, Color.WHITE, true).apply { setPadding(dp(6), 0, dp(6), 0) }, lp())
        status = text("", 14f, dim).apply { setPadding(dp(6), 0, dp(6), 0) }
        root.addView(status, lp())

        tabDesk = btn("🖥 שולחן עבודה") { selectTab(false) }
        tabControl = btn("🖱 שליטה") { selectTab(true) }
        root.addView(row(tabDesk, tabControl), lp())

        val content = FrameLayout(this)
        deskPage = buildDeskPage()
        controlPage = buildControlPage()
        content.addView(deskPage)
        content.addView(controlPage)
        root.addView(content, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        selectTab(false)
        return root
    }

    private fun selectTab(control: Boolean) {
        deskPage.visibility = if (control) View.GONE else View.VISIBLE
        controlPage.visibility = if (control) View.VISIBLE else View.GONE
        tabDesk.background = round(if (control) Color.rgb(36, 44, 64) else accent, 12)
        tabControl.background = round(if (control) accent else Color.rgb(36, 44, 64), 12)
        if (control) ensureCursor()
    }

    private fun buildDeskPage(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        startBtn = btn("▶ הצג שולחן עבודה במשקפיים", Color.rgb(20, 120, 80)) { Glasses.startDesktop(this) }
        v.addView(startBtn, lp())

        // --- Size & distance
        preview = PreviewView(this)
        readout = text("", 13f, dim)

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in Desk.presets) presets.addView(btn(p.label) { Desk.applyPreset(p); autoApply() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        val presetScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presets) }

        // size: 10″..150″ in 1″ steps
        sizeLabel = text("", 14f)
        sizeSeek = seek((Desk.MAX_DIAG - Desk.MIN_DIAG).toInt(), { p -> Desk.diagIn = Desk.MIN_DIAG + p; Desk.changed() }) { autoApply() }
        val sizeRow = labeled(sizeLabel, sizeSeek,
            { Desk.diagIn = (Desk.diagIn - 1f).coerceAtLeast(Desk.MIN_DIAG); Desk.changed(); autoApply() },
            { Desk.diagIn = (Desk.diagIn + 1f).coerceAtMost(Desk.MAX_DIAG); Desk.changed(); autoApply() })

        val aspectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Desk.aspects.forEachIndexed { i, a ->
            val b = btn(a.label) { Desk.aspect = i; Desk.changed(); autoApply() }
            aspectBtns += b
            aspectRow.addView(b, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // distance: 0.40..5.00 m in 5 cm steps
        distLabel = text("", 14f)
        distSeek = seek(((Desk.MAX_DIST - Desk.MIN_DIST) / 0.05f).toInt(), { p -> Desk.distM = Desk.MIN_DIST + p * 0.05f; Desk.changed() }) { autoApply() }
        val distRow = labeled(distLabel, distSeek,
            { Desk.distM = (Desk.distM - 0.1f).coerceAtLeast(Desk.MIN_DIST); Desk.changed(); autoApply() },
            { Desk.distM = (Desk.distM + 0.1f).coerceAtMost(Desk.MAX_DIST); Desk.changed(); autoApply() })

        // height: -100..100
        heightSeek = seek(200, { p -> Desk.height = (p - 100) / 100f; Desk.changed() }) { autoApply() }
        val heightRow = labeled(text("↕ גובה המסך", 14f), heightSeek,
            { Desk.height = (Desk.height - 0.1f).coerceAtLeast(-1f); Desk.changed(); autoApply() },
            { Desk.height = (Desk.height + 0.1f).coerceAtMost(1f); Desk.changed(); autoApply() })

        val auto = CheckBox(this).apply {
            text = "התאם חלונות פתוחים אוטומטית"; setTextColor(Color.WHITE); isChecked = Desk.autoApply
            setOnCheckedChangeListener { _, c -> Desk.autoApply = c; Desk.changed() }
        }
        val apply = btn("⟳ סדר את החלונות לפי הגודל החדש") { Glasses.reapply(this) }

        v.addView(section("גודל ומרחק", preview, readout, presetScroll, sizeRow, aspectRow, distRow, heightRow, auto, apply), lp())

        // --- New window layout
        val slotRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Desk.Slot.values().forEach { s ->
            val b = btn(s.label) { Desk.defaultSlot = s; Desk.changed() }
            slotBtns += b
            slotRow.addView(b, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        v.addView(section("פריסה לחלון חדש", slotRow,
            text("לחלון שכבר פתוח: פותחים אותו שוב מהרשימה אחרי שבוחרים פריסה.", 12f, dim)), lp())

        // --- Apps
        val search = EditText(this).apply {
            hint = "חיפוש אפליקציה"; setHintTextColor(dim); setTextColor(Color.WHITE)
            background = round(Color.rgb(30, 36, 52), 10); setPadding(dp(12), dp(8), dp(12), dp(8))
            imeOptions = EditorInfo.IME_ACTION_DONE; isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { appFilter = s?.toString() ?: ""; fillApps() }
            })
        }
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        v.addView(section("אפליקציות", text("הקשה: פתיחה במשקפיים · לחיצה ארוכה: הצמדה לשורת המשימות", 12f, dim), search, appsBox), lp())

        // --- Advanced
        fovLabel = text("", 14f)
        fovSeek = seek(30, { p -> Desk.fovDiag = 40f + p; Desk.changed() }) { autoApply() }
        v.addView(section("מתקדם",
            labeled(fovLabel, fovSeek,
                { Desk.fovDiag = (Desk.fovDiag - 1f).coerceAtLeast(40f); Desk.changed() },
                { Desk.fovDiag = (Desk.fovDiag + 1f).coerceAtMost(70f); Desk.changed() }),
            text("Air / Air 2 / Air 2 Pro: 46°.  Air 2 Ultra: 52°.", 12f, dim),
            btn("🪟 חלונות לא מקבלים את הגודל? הסבר") { freeformHelp() },
            btn("♿ הפעלת שליטה מהטלפון (נגישות)") { askAccessibility(force = true) }
        ), lp())

        return ScrollView(this).apply { addView(v) }
    }

    private fun fillApps() {
        if (!::appsBox.isInitialized) return
        appsBox.removeAllViews()
        val f = appFilter.trim().lowercase()
        val list = Apps.list.filter { f.isEmpty() || it.label.lowercase().contains(f) }
        if (list.isEmpty()) { appsBox.addView(text(if (Apps.list.isEmpty()) "טוען…" else "לא נמצאו אפליקציות", 13f, dim)); return }
        val pinned = Apps.pinned()
        val sorted = list.sortedByDescending { it.pkg in pinned }
        for (a in sorted) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(Color.rgb(30, 36, 52), 10)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                addView(ImageView(this@MainActivity).apply { setImageBitmap(a.icon) }, LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(text(a.label, 15f).apply { setPadding(dp(12), 0, dp(12), 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (a.pkg in pinned) addView(text("📌", 14f))
                if (Glasses.windows.any { it.pkg == a.pkg }) addView(text(" ●", 14f, accent))
                setOnClickListener { openApp(a.pkg) }
                setOnLongClickListener { Apps.togglePin(a.pkg); true }
            }
            appsBox.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
    }

    private lateinit var typeBox: EditText
    private lateinit var pad: PadView

    private fun buildControlPage(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        v.addView(row(
            btn("‹ חזרה") { a11y()?.back() ?: askAccessibility() },
            btn("⊞ תפריט") { DesktopActivity.instance?.toggleMenu() ?: Glasses.startDesktop(this) },
            btn("🖥 שולחן עבודה") { Glasses.startDesktop(this) }
        ), lp())
        typeBox = EditText(this).apply {
            hint = "הקלדה לאפליקציה במשקפיים ↵"; setHintTextColor(dim); setTextColor(Color.WHITE)
            background = round(Color.rgb(30, 36, 52), 10); setPadding(dp(12), dp(8), dp(12), dp(8))
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ ->
                val d = Glasses.display(this@MainActivity)
                val s = a11y()
                if (d != null && s != null) {
                    if (text.isNotEmpty()) s.typeText(d.displayId, text.toString())
                    s.enter(d.displayId)
                } else askAccessibility()
                setText(""); true
            }
        }
        v.addView(row(typeBox, btn("⌫") {
            val d = Glasses.display(this); val s = a11y()
            if (d != null && s != null) s.backspace(d.displayId, 1) else askAccessibility()
        }, equal = false).apply { (getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f } }, lp())
        pad = PadView(this, this)
        v.addView(pad, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val sens = seek(30, { p -> pad.sensitivity = 0.6f + p * 0.1f; Desk.prefs().edit().putFloat("sens", pad.sensitivity).apply() })
        pad.sensitivity = Desk.prefs().getFloat("sens", 1.6f)
        sens.progress = ((pad.sensitivity - 0.6f) / 0.1f).toInt()
        v.addView(row(text("מהירות סמן", 13f, dim), sens, equal = false).apply {
            (getChildAt(1).layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        }, lp())
        return v
    }

    // ---------------------------------------------------------------- state -> UI

    private fun refresh() {
        if (!::status.isInitialized) return
        val d = Glasses.display(this)
        val (w, h) = Glasses.size(d)
        status.text = when {
            d == null -> "⚪ המשקפיים לא מחוברים"
            Glasses.isSbs(d) -> "🟠 המשקפיים במצב 3D (${w}×${h}). לחיצה ארוכה על כפתור הבהירות מחזירה ל-2D."
            else -> "🟢 משקפיים מחוברים · ${w}×${h}" + if (DesktopActivity.instance != null) " · שולחן עבודה פעיל" else ""
        }
        startBtn.isEnabled = d != null
        startBtn.alpha = if (d != null) 1f else 0.5f

        preview.dispW = w; preview.dispH = h
        preview.invalidate()
        val g = Desk.geometry(w, h)
        readout.text = buildString {
            append("מסך ${"%.0f".format(g.widthCm)}×${"%.0f".format(g.heightCm)} ס״מ · ")
            append("זווית ${"%.0f".format(g.degW)}°×${"%.0f".format(g.degH)}° · ")
            append("${g.rect.width().toInt()}×${g.rect.height().toInt()} פיקסלים")
            if (g.clipped) append("\n⚠ המסך גדול משדה הראייה — מוצג בגודל המרבי. הרחיקו אותו או הקטינו.")
        }
        sizeLabel.text = "⤢ גודל המסך: ${"%.0f".format(Desk.diagIn)}″ (אלכסון)"
        sizeSeek.progress = (Desk.diagIn - Desk.MIN_DIAG).toInt()
        distLabel.text = "↔ מרחק: ${"%.2f".format(Desk.distM)} מ׳"
        distSeek.progress = ((Desk.distM - Desk.MIN_DIST) / 0.05f + 0.5f).toInt()
        heightSeek.progress = (Desk.height * 100f + 100f).toInt()
        fovLabel.text = "שדה ראייה של המשקפיים: ${"%.0f".format(Desk.fovDiag)}°"
        fovSeek.progress = (Desk.fovDiag - 40f).toInt()
        aspectBtns.forEachIndexed { i, b -> b.background = round(if (i == Desk.aspect) accent else Color.rgb(36, 44, 64), 12) }
        slotBtns.forEachIndexed { i, b -> b.background = round(if (i == Desk.defaultSlot.ordinal) accent else Color.rgb(36, 44, 64), 12) }
        fillAppsDebounced()
    }

    private var appsSig = ""
    private fun fillAppsDebounced() {
        val sig = "${Apps.list.size}|${Apps.pinned()}|${Glasses.windows.map { it.pkg }}|$appFilter"
        if (sig == appsSig) return
        appsSig = sig
        fillApps()
    }

    private val applyRunnable = Runnable { Glasses.reapply(this) }
    private fun autoApply() {
        if (!Desk.autoApply || Glasses.windows.isEmpty()) return
        main.removeCallbacks(applyRunnable)
        main.postDelayed(applyRunnable, 350)
    }

    // ---------------------------------------------------------------- apps

    private var askedFreeform = false

    private fun openApp(pkg: String) {
        if (DesktopActivity.instance == null) Glasses.startDesktop(this)
        val b = Glasses.launch(this, pkg) ?: return
        if (a11y() == null) askAccessibility()
        // If the system ignored the requested size, explain how to enable resizable windows
        main.postDelayed({
            if (askedFreeform) return@postDelayed
            val d = Glasses.display(this) ?: return@postDelayed
            val w = a11y()?.appWindowWidth(d.displayId) ?: return@postDelayed
            if (w > b.width() * 1.05f) { askedFreeform = true; freeformHelp() }
        }, 1500)
    }

    private fun freeformHelp() {
        AlertDialog.Builder(this)
            .setTitle("חלונות בגודל משתנה")
            .setMessage(
                "כדי שהאפליקציות ייפתחו בגודל ובמרחק שבחרת (ולא על כל מסך המשקפיים), צריך לאפשר פעם אחת חלונות בגודל משתנה:\n\n" +
                    "1. הגדרות ← אפשרויות מפתח (Developer options).\n" +
                    "2. מפעילים \"אפשר חלונות בגודל משתנה\" (Enable freeform windows) ו-\"אלץ פעילויות להיות ניתנות לשינוי גודל\".\n" +
                    "3. אם יש: \"אלץ מצב שולחן עבודה\" (Force desktop mode).\n" +
                    "4. מפעילים מחדש את הטלפון.\n\n" +
                    "אם אפשרויות מפתח לא מופיעות: הגדרות ← אודות הטלפון ← פרטי תוכנה ← הקשה 7 פעמים על \"מספר Build\".\n\n" +
                    "אם סמסונג מציעה DeX כשמחברים את המשקפיים — בוחרים שיקוף מסך / סוגרים את ההצעה."
            )
            .setPositiveButton("אפשרויות מפתח") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                catch (_: Exception) { Glasses.toast(this, "לא ניתן לפתוח את ההגדרות") }
            }
            .setNegativeButton("סגור", null)
            .show()
    }

    // ---------------------------------------------------------------- touchpad -> glasses

    private fun a11y(): DeskAccessibilityService? = DeskAccessibilityService.instance

    private var cx = 960f
    private var cy = 540f
    private var cursorShown = false
    private var sx = 0f
    private var sy = 0f

    private fun ensureCursor() {
        val d = Glasses.display(this) ?: return
        val s = a11y() ?: run { askAccessibility(); return }
        if (!cursorShown) {
            val (w, h) = Glasses.size(d)
            val r = Desk.geometry(w, h).rect
            cx = cx.coerceIn(r.left, r.right - 1f)
            cy = cy.coerceIn(r.top, r.bottom - 1f)
            s.showCursor(d, cx.toInt(), cy.toInt())
            cursorShown = true
        }
    }

    override fun padDown() { ensureCursor() }

    override fun padMove(dx: Float, dy: Float) {
        val d = Glasses.display(this) ?: return
        val (w, h) = Glasses.size(d)
        cx = (cx + dx).coerceIn(0f, w - 1f)
        cy = (cy + dy).coerceIn(0f, h - 1f)
        a11y()?.moveCursor(cx.toInt(), cy.toInt())
    }

    override fun padClick() {
        val d = Glasses.display(this) ?: return
        a11y()?.tap(d.displayId, cx, cy) ?: askAccessibility()
    }

    override fun padScrollStart() {
        val d = Glasses.display(this) ?: return
        sx = cx; sy = cy
        a11y()?.dragStart(d.displayId, sx, sy)
    }

    override fun padScroll(dx: Float, dy: Float) {
        val d = Glasses.display(this) ?: return
        val (w, h) = Glasses.size(d)
        sx = (sx + dx).coerceIn(1f, w - 2f)
        sy = (sy + dy).coerceIn(1f, h - 2f)
        a11y()?.dragTo(sx, sy)
    }

    override fun padScrollEnd() { a11y()?.dragEnd() }

    private var askedA11y = false

    private fun askAccessibility(force: Boolean = false) {
        if (askedA11y && !force) return
        askedA11y = true
        AlertDialog.Builder(this)
            .setTitle("שליטה מהטלפון")
            .setMessage(
                "כדי להזיז סמן, ללחוץ, לגלול ולהקליד בשולחן העבודה שבמשקפיים, מפעילים פעם אחת את השירות " +
                    "\"XR Desk – שליטה במשקפיים\" בהגדרות נגישות.\n\n" +
                    "אם ההפעלה חסומה (אפליקציה שהותקנה מקובץ): פרטי אפליקציה ← ⋮ למעלה ← \"אפשר הגדרות מוגבלות\", ואז שוב לנגישות.\n\n" +
                    "אפשר גם לחבר עכבר Bluetooth — הוא עובד ישירות על המשקפיים."
            )
            .setPositiveButton("הגדרות נגישות") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNeutralButton("פרטי אפליקציה") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("אחר כך", null)
            .show()
    }
}
