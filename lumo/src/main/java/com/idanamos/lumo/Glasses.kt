package com.idanamos.lumo

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.widget.Toast

/** The XREAL display, and the desktop + app windows on it. */
object Glasses {

    class Win(val pkg: String, var slot: Desk.Slot, var time: Long)

    /** App windows we opened on the glasses, oldest first. */
    val windows = mutableListOf<Win>()

    fun display(ctx: Context): Display? {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
            ?: dm.displays.firstOrNull {
                it.displayId != Display.DEFAULT_DISPLAY && it.isValid && (it.flags and Display.FLAG_PRIVATE) == 0
            }
    }

    /** Landscape pixel size of the display. */
    fun size(d: Display?): Pair<Int, Int> {
        if (d == null) return Pair(1920, 1080)
        val m = d.mode
        return Pair(maxOf(m.physicalWidth, m.physicalHeight), minOf(m.physicalWidth, m.physicalHeight))
    }

    /** Glasses switched to 3D side-by-side (3840×1080): apps would be stretched across both eyes. */
    fun isSbs(d: Display?): Boolean {
        val (w, h) = size(d)
        return d != null && w.toFloat() / h > 3f
    }

    /** Open (or bring to front) the desktop on the glasses. */
    fun startDesktop(ctx: Context): Boolean {
        val d = display(ctx) ?: run { toast(ctx, "המשקפיים לא מחוברים"); return false }
        val (w, h) = size(d)
        val i = Intent(ctx, DesktopActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val o = ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).setLaunchBounds(Rect(0, 0, w, h))
        return try {
            ctx.startActivity(i, o.toBundle()); true
        } catch (e: Exception) {
            toast(ctx, "המערכת לא אפשרה לפתוח על מסך המשקפיים"); false
        }
    }

    /** Open [pkg] on the glasses inside the virtual monitor, at [slot]. Returns the bounds used. */
    fun launch(ctx: Context, pkg: String, slot: Desk.Slot = Desk.defaultSlot): Rect? {
        val d = display(ctx) ?: run { toast(ctx, "המשקפיים לא מחוברים"); return null }
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: run { toast(ctx, "לא ניתן לפתוח"); return null }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val (w, h) = size(d)
        val b = Desk.bounds(slot, w, h)
        val o = ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).setLaunchBounds(b)
        try {
            ctx.startActivity(i, o.toBundle())
        } catch (e: Exception) {
            toast(ctx, "המערכת לא אפשרה לפתוח את האפליקציה במשקפיים")
            return null
        }
        windows.removeAll { it.pkg == pkg }
        windows += Win(pkg, slot, System.currentTimeMillis())
        Desk.ping()
        return b
    }

    /** Re-open all windows so they fit the new monitor size. */
    fun reapply(ctx: Context) {
        for (w in windows.toList()) launch(ctx, w.pkg, w.slot)
    }

    /** Drop windows the user closed (needs the accessibility service to know). */
    fun prune(ctx: Context) {
        val svc = DeskAccessibilityService.instance ?: return
        val d = display(ctx) ?: return
        val open = svc.packagesOn(d.displayId) ?: return
        val now = System.currentTimeMillis()
        if (windows.removeAll { now - it.time > 5000 && it.pkg !in open }) Desk.ping()
    }

    fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
}
