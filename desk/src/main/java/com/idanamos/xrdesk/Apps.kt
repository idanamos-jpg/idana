package com.idanamos.xrdesk

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/** Installed launchable apps, with icons pre-rendered to bitmaps (safe to draw from several views). */
object Apps {

    class App(val pkg: String, val label: String, val icon: Bitmap)

    @Volatile var list: List<App> = emptyList()
        private set
    private var byPkg: Map<String, App> = emptyMap()
    private var loading = false
    private val main = Handler(Looper.getMainLooper())

    private val defaultPins = listOf(
        "com.android.chrome", "com.sec.android.app.sbrowser", "com.google.android.youtube",
        "com.netflix.mediaclient", "com.google.android.gm", "com.whatsapp",
        "com.microsoft.office.outlook", "com.google.android.apps.docs"
    )

    fun find(pkg: String): App? = byPkg[pkg]

    fun load(ctx: Context) {
        if (loading) return
        loading = true
        val app = ctx.applicationContext
        thread(name = "apps") {
            val pm = app.packageManager
            val res = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
            val size = 128
            val out = res.asSequence()
                .map { it.activityInfo }
                .filter { it.packageName != app.packageName }
                .distinctBy { it.packageName }
                .mapNotNull { ai ->
                    try {
                        val d = ai.loadIcon(pm)
                        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                        val c = Canvas(b)
                        d.setBounds(0, 0, size, size)
                        d.draw(c)
                        App(ai.packageName, ai.loadLabel(pm).toString(), b)
                    } catch (e: Exception) { null }
                }
                .sortedBy { it.label.lowercase() }
                .toList()
            main.post {
                list = out
                byPkg = out.associateBy { it.pkg }
                loading = false
                Desk.ping()
            }
        }
    }

    fun pinned(): List<String> {
        val p = Desk.store()
        val s = p.getString("pinned", null)
        if (s == null) return defaultPins.filter { byPkg.containsKey(it) }.take(6)
        return s.split(',').filter { it.isNotBlank() }
    }

    fun isPinned(pkg: String) = pinned().contains(pkg)

    fun togglePin(pkg: String) {
        val cur = pinned().toMutableList()
        if (!cur.remove(pkg)) cur.add(pkg)
        Desk.store().edit().putString("pinned", cur.joinToString(",")).apply()
        Desk.ping()
    }
}
