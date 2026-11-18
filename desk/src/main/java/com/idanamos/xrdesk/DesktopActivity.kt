package com.idanamos.xrdesk

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/** Runs on the glasses display: black surroundings + the virtual monitor with wallpaper, taskbar and start menu. */
class DesktopActivity : Activity() {

    companion object {
        @Volatile var instance: DesktopActivity? = null
    }

    lateinit var desk: DeskView
        private set

    private val onChange: () -> Unit = { if (::desk.isInitialized) desk.invalidate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Desk.init(this)
        if (Apps.list.isEmpty()) Apps.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        desk = DeskView(this)
        setContentView(desk)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        Desk.listen(onChange)
        instance = this
    }

    override fun onDestroy() {
        Desk.unlisten(onChange)
        if (instance === this) instance = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (desk.menuOpen) { desk.menuOpen = false; desk.invalidate() }
        // The desktop never closes itself on back.
    }

    fun toggleMenu() {
        desk.menuOpen = !desk.menuOpen
        desk.invalidate()
    }
}
