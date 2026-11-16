package com.idanamos.xrspace

import android.app.Presentation
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Display
import android.view.WindowManager

/** Full-screen GL scene shown on the glasses (external display). */
class GlassesPresentation(private val act: MainActivity, display: Display) : Presentation(act, display) {

    val glView: GLSurfaceView = GLSurfaceView(context)
    val renderer = SpaceRenderer(act.space) { w -> act.runOnUiThread { w.recreateSurface() } }

    init {
        glView.setEGLContextClientVersion(2)
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(glView)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDisplayRemoved() {
        act.onGlassesRemoved(this)
        super.onDisplayRemoved()
    }
}
