package dev.mkzk.manifold.hub.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

// Not in EGL14: tells the driver the surface feeds a video encoder.
private const val EGL_RECORDABLE_ANDROID = 0x3142

internal const val QUAD_VERTEX = """
attribute vec4 position;
attribute vec2 coord;
uniform mat4 textureMatrix;
varying vec2 uv;
varying vec2 plain;
void main() {
    gl_Position = position;
    plain = coord;
    uv = (textureMatrix * vec4(coord, 0.0, 1.0)).xy;
}
"""

private val QUAD = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)

internal class GlContext(private val withAlpha: Boolean) {

    private val thread = HandlerThread("manifold-gl", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    val handler = Handler(thread.looper)

    private val vertices = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(QUAD)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idle: EGLSurface = EGL14.EGL_NO_SURFACE

    // One program per context: the attribute locations are kept from the last one built.
    private var positionLocation = 0
    private var coordLocation = 0

    init {
        try {
            run(::setUp)
        } catch (e: Throwable) {
            thread.quitSafely()
            throw e
        }
    }

    fun run(block: () -> Unit) {
        var failure: Throwable? = null
        val done = CountDownLatch(1)
        handler.post {
            try {
                block()
            } catch (e: Throwable) {
                failure = e
            }
            done.countDown()
        }
        done.await()
        failure?.let { throw it }
    }

    fun makeIdleCurrent() {
        EGL14.eglMakeCurrent(display, idle, idle, context)
    }

    fun windowSurface(target: Surface): EGLSurface {
        val window = EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE) { "cannot draw into the surface" }
        return window
    }

    fun makeCurrent(surface: EGLSurface) {
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    fun size(surface: EGLSurface, into: IntArray) {
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, into, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, into, 1)
    }

    /** [timestampNs] is what an encoder stamps the frame with, on the monotonic clock. */
    fun swap(surface: EGLSurface, timestampNs: Long? = null): Boolean {
        if (timestampNs != null) EGLExt.eglPresentationTimeANDROID(display, surface, timestampNs)
        return EGL14.eglSwapBuffers(display, surface)
    }

    fun destroy(surface: EGLSurface) {
        makeIdleCurrent()
        EGL14.eglDestroySurface(display, surface)
    }

    fun externalTexture(filter: Int): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        return textures[0]
    }

    fun program(vertex: String, fragment: String): Int {
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        positionLocation = GLES20.glGetAttribLocation(program, "position")
        coordLocation = GLES20.glGetAttribLocation(program, "coord")
        return program
    }

    fun drawQuad() {
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(positionLocation)
        vertices.position(2)
        GLES20.glVertexAttribPointer(coordLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(coordLocation)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        run {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, idle)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
        }
        thread.quitSafely()
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "no EGL display" }
        val wanted = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            *if (withAlpha) intArrayOf(EGL14.EGL_ALPHA_SIZE, 8) else intArrayOf(EGL_RECORDABLE_ANDROID, 1),
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no usable EGL config" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "cannot create an EGL context" }
        idle = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, idle, idle, context)) { "cannot use the EGL context" }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }
}
