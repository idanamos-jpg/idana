package com.idanamos.xrspace

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Renders all windows as (optionally curved) screens around the viewer, plus the pointer. */
class SpaceRenderer(
    private val space: Space,
    private val onSurfaceBroken: (XWindow) -> Unit
) : GLSurfaceView.Renderer {

    private var progTex = 0
    private var progSolid = 0
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private var buf: FloatBuffer = alloc(2048)
    private val pendingRelease = ConcurrentLinkedQueue<XWindow>()

    private var lockYaw = 0f
    private var lockPitch = 0f

    fun queueRelease(w: XWindow) { pendingRelease.add(w) }

    private fun alloc(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        progTex = buildProgram(VS, FS_TEX)
        progSolid = buildProgram(VS, FS_SOLID)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        // A brand new context: anything that was attached to us before is gone.
        for (w in space.windows) if (w.attachedTo === this) {
            w.attachedTo = null
            w.surfaceBroken = true
            onSurfaceBroken(w)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        space.aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    /** Must run on the GL thread. Detaches every texture so another GL context can take over. */
    fun detachAll() {
        for (w in space.windows) {
            if (w.attachedTo === this) {
                try { w.surfaceTexture.detachFromGLContext() } catch (e: Exception) { Log.w(TAG, "detach", e) }
                w.attachedTo = null
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        // Release closed windows
        while (true) {
            val w = pendingRelease.poll() ?: break
            if (w.attachedTo === this) {
                try { w.surfaceTexture.detachFromGLContext() } catch (_: Exception) {}
                w.attachedTo = null
            }
            try { w.surface.release() } catch (_: Exception) {}
            try { w.surfaceTexture.release() } catch (_: Exception) {}
        }

        // View orientation
        var yaw: Float
        var pitch: Float
        var roll = 0f
        val t = space.tracker
        if (space.headTracking() && t != null) {
            yaw = t.yaw; pitch = t.pitch; roll = t.roll
        } else {
            val f = space.focused
            val ty = f?.yaw ?: 0f
            val tp = f?.pitch ?: 0f
            lockYaw += Geo.wrap180(ty - lockYaw) * 0.15f
            lockPitch += (tp - lockPitch) * 0.15f
            yaw = lockYaw; pitch = lockPitch
        }
        space.viewYaw = yaw
        space.viewPitch = pitch

        Matrix.setRotateM(view, 0, -roll, 0f, 0f, 1f)
        Matrix.rotateM(view, 0, -pitch, 1f, 0f, 0f)
        Matrix.rotateM(view, 0, -yaw, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, space.fovY, space.aspect, 0.05f, 100f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val focused = space.focused
        for (w in space.windows) {
            if (w.released) continue
            w.ensureMesh(space.distance, space.curve)
            val mesh = w.mesh ?: continue

            if (w.attachedTo == null && !w.surfaceBroken) attach(w)
            if (w.attachedTo === this && w.frameAvailable) {
                w.frameAvailable = false
                try {
                    w.surfaceTexture.updateTexImage()
                    w.surfaceTexture.getTransformMatrix(w.texMatrix)
                    w.hasFrame = true
                } catch (e: Exception) {
                    Log.w(TAG, "updateTexImage", e)
                }
            }

            Matrix.setRotateM(model, 0, w.yaw, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, w.pitch, 1f, 0f, 0f)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

            if (w.attachedTo === this && w.hasFrame) {
                GLES20.glUseProgram(progTex)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, w.texId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(progTex, "sTex"), 0)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progTex, "uTex"), 1, false, w.texMatrix, 0)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(progTex, "uFocus"),
                    if (w === focused && space.windows.size > 1) 1f else 0f)
                drawStrip(progTex, mesh)
            } else {
                GLES20.glUseProgram(progSolid)
                GLES20.glUniform4f(GLES20.glGetUniformLocation(progSolid, "uColor"), 0.12f, 0.14f, 0.18f, 1f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(progSolid, "uCircle"), 0f)
                drawStrip(progSolid, mesh)
            }
        }

        // Pointer
        val hit = space.cursorHit()
        val dist = (hit?.dist ?: space.distance) * 0.97f
        val s = dist * 0.011f
        val quad = floatArrayOf(
            -s, -s, -dist, 0f, 0f,
            -s, s, -dist, 0f, 1f,
            s, -s, -dist, 1f, 0f,
            s, s, -dist, 1f, 1f
        )
        Matrix.setRotateM(model, 0, space.cursorYaw, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, space.cursorPitch, 1f, 0f, 0f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(progSolid)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(progSolid, "uColor"),
            if (hit != null) 0.3f else 0.85f, if (hit != null) 0.7f else 0.85f, 1f, 1f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progSolid, "uCircle"), 1f)
        drawStrip(progSolid, quad)
    }

    private fun attach(w: XWindow) {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        try {
            w.surfaceTexture.attachToGLContext(tex)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            w.texId = tex
            w.attachedTo = this
            w.frameAvailable = true
        } catch (e: Exception) {
            Log.w(TAG, "attachToGLContext failed, recreating surface", e)
            GLES20.glDeleteTextures(1, ids, 0)
            w.surfaceBroken = true
            onSurfaceBroken(w)
        }
    }

    private fun drawStrip(prog: Int, data: FloatArray) {
        if (buf.capacity() < data.size) buf = alloc(data.size)
        buf.clear()
        buf.put(data)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aUV = GLES20.glGetAttribLocation(prog, "aUV")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvp, 0)
        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        buf.position(3)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 20, buf)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, data.size / 5)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUV)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val TAG = "XRSpace"

        private const val VS = """
            uniform mat4 uMVP;
            uniform mat4 uTex;
            attribute vec4 aPos;
            attribute vec4 aUV;
            varying vec2 vUV;
            varying vec2 vRaw;
            void main() {
                gl_Position = uMVP * aPos;
                vUV = (uTex * aUV).xy;
                vRaw = aUV.xy;
            }
        """

        private const val FS_TEX = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTex;
            uniform float uFocus;
            varying vec2 vUV;
            varying vec2 vRaw;
            void main() {
                vec4 c = texture2D(sTex, vUV);
                float e = min(min(vRaw.x, 1.0 - vRaw.x), min(vRaw.y, 1.0 - vRaw.y));
                if (uFocus > 0.5 && e < 0.005) c = vec4(0.25, 0.62, 1.0, 1.0);
                gl_FragColor = vec4(c.rgb, 1.0);
            }
        """

        private const val FS_SOLID = """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uCircle;
            varying vec2 vUV;
            varying vec2 vRaw;
            void main() {
                if (uCircle > 0.5) {
                    float d = distance(vRaw, vec2(0.5));
                    if (d > 0.5) discard;
                    if (d > 0.36) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
                }
                gl_FragColor = uColor;
            }
        """
    }
}
