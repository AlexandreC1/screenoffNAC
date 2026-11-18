package dev.screenoff.recorder.service

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import dev.screenoff.recorder.Diag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Receives the camera's preview stream on a GPU texture and reads small RGBA
 * frames back from it. Nothing is shown on screen.
 *
 * Preview + video recording is the stream combination almost every phone
 * supports, unlike video + image analysis. So this one feed drives both the
 * live view and motion detection while a clip is being recorded.
 *
 * Everything runs on its own thread. [onFrame] is called on that thread with
 * a buffer that is only valid during the call, rows top to bottom.
 */
class FrameTap(
    private val onFrame: (rgba: ByteBuffer, width: Int, height: Int) -> Unit,
) : Preview.SurfaceProvider {

    /** Minimum time between frames handed to [onFrame]. Changed from any thread. */
    @Volatile var intervalMs: Long = 200L

    /** Degrees to rotate a frame clockwise for it to look upright. */
    @Volatile var rotationDegrees: Int = 0
        private set

    private val thread = HandlerThread("frame-tap").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var outW = 0
    private var outH = 0
    private var pixels: ByteBuffer? = null
    private var current: SurfaceTexture? = null
    private var lastFrameAt = 0L
    private val matrix = FloatArray(16)

    override fun onSurfaceRequested(request: SurfaceRequest) {
        handler.post {
            try {
                attach(request)
            } catch (t: Throwable) {
                Diag.log("frame tap could not attach", t)
                request.willNotProvideSurface()
            }
        }
    }

    private fun attach(request: SurfaceRequest) {
        val res = request.resolution
        ensureEgl()
        val w = 640
        val h = (640L * res.height / res.width).toInt().let { it - it % 2 }.coerceAtLeast(2)
        if (w != outW || h != outH) resizePbuffer(w, h)

        current?.release()
        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(res.width, res.height)
        st.setOnFrameAvailableListener({ onFrameAvailable(it) }, handler)
        current = st
        val surface = Surface(st)
        request.setTransformationInfoListener(executor) { info -> rotationDegrees = info.rotationDegrees }
        request.provideSurface(surface, executor) {
            surface.release()
            if (current === st) {
                st.release()
                current = null
            }
        }
        Diag.log("frame tap attached: camera ${res.width}x${res.height}, frames ${outW}x$outH")
    }

    private fun onFrameAvailable(st: SurfaceTexture) {
        if (st !== current) return
        try {
            st.updateTexImage() // Always consume, or the camera stalls.
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < intervalMs) return
            lastFrameAt = now
            st.getTransformMatrix(matrix)
            draw()
            val buf = requireNotNull(pixels)
            buf.clear()
            GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            onFrame(buf, outW, outH)
        } catch (t: Throwable) {
            Diag.log("frame tap failed on a frame", t)
        }
    }

    private fun ensureEgl() {
        if (display != EGL14.EGL_NO_DISPLAY) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        eglConfig = configs[0]
        context = EGL14.eglCreateContext(
            display, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        resizePbuffer(640, 360)
        program = buildProgram()
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun resizePbuffer(w: Int, h: Int) {
        if (pbuffer != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
        }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, eglConfig, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0,
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }
        outW = w
        outH = h
        pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    }

    private fun draw() {
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, matrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX_FLIPPED)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    fun release() {
        handler.post {
            current?.release()
            current = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            thread.quitSafely()
        }
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTex * aTex).xy;
            }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """

        fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v)
                position(0)
            }

        val QUAD: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        // glReadPixels returns the bottom row first, so the picture is drawn upside down
        // to come out top row first.
        val TEX_FLIPPED: FloatBuffer = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    }
}
