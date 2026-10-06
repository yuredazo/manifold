package dev.mkzk.manifold.hub.screen

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import kotlin.math.min

private const val TAG = "ScreenFrames"

// Not in EGL14: tells the driver the surface feeds a video encoder.
private const val EGL_RECORDABLE_ANDROID = 0x3142

private const val VERTEX = """
attribute vec4 position;
attribute vec2 coord;
uniform mat4 textureMatrix;
varying vec2 uv;
void main() {
    gl_Position = position;
    uv = (textureMatrix * vec4(coord, 0.0, 1.0)).xy;
}
"""

private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES source;
varying vec2 uv;
void main() {
    gl_FragColor = texture2D(source, uv);
}
"""

private val QUAD = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)

/**
 * Android 14 lets one screen capture drive a single virtual display, so every watcher's encoder is fed by drawing that
 * display's picture once per frame into each of their surfaces.
 */
internal class ScreenFrames(private var width: Int, private var height: Int) {
    private class Output(val id: String, val target: EGLSurface, val width: Int, val height: Int)

    private val thread = HandlerThread("manifold-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private val outputs = LinkedHashMap<String, Output>()
    private val textureMatrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(QUAD)

    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idle = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var sourceLocation = 0
    private var matrixLocation = 0
    private var positionLocation = 0
    private var coordLocation = 0
    private var texture = 0
    private var frames: SurfaceTexture? = null
    private var hasFrame = false

    lateinit var surface: Surface
        private set

    init {
        try {
            onThread {
                setUpEgl()
                setUpProgram()
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                texture = textures[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                val source = SurfaceTexture(texture)
                source.setDefaultBufferSize(width, height)
                source.setOnFrameAvailableListener({ drawLatest() }, handler)
                frames = source
                surface = Surface(source)
            }
        } catch (e: Throwable) {
            thread.quitSafely()
            throw e
        }
    }

    fun add(id: String, target: Surface, width: Int, height: Int) {
        handler.post {
            if (display == EGL14.EGL_NO_DISPLAY) return@post
            val window = EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE), 0)
            if (window == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "cannot draw into the surface of $id")
                return@post
            }
            outputs.remove(id)?.let { destroy(it) }
            val output = Output(id, window, width, height)
            outputs[id] = output
            // A still screen sends no frame, so a late watcher would see nothing until something moved.
            if (hasFrame) draw(output)
        }
    }

    fun remove(id: String) {
        handler.post { outputs.remove(id)?.let { destroy(it) } }
    }

    fun resize(width: Int, height: Int) {
        handler.post {
            this.width = width
            this.height = height
            frames?.setDefaultBufferSize(width, height)
            hasFrame = false
        }
    }

    fun release() {
        onThread {
            outputs.values.forEach(::destroy)
            outputs.clear()
            surface.release()
            frames?.release()
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

    private fun drawLatest() {
        val source = frames ?: return
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, idle, idle, context)
        try {
            source.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "frame skipped: ${e.message}")
            return
        }
        source.getTransformMatrix(textureMatrix)
        hasFrame = true
        outputs.values.toList().forEach(::draw)
    }

    private fun draw(output: Output) {
        EGL14.eglMakeCurrent(display, output.target, output.target, context)
        GLES20.glViewport(0, 0, output.width, output.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val scale = min(output.width.toFloat() / width, output.height.toFloat() / height)
        val fitWidth = (width * scale).toInt()
        val fitHeight = (height * scale).toInt()
        GLES20.glViewport((output.width - fitWidth) / 2, (output.height - fitHeight) / 2, fitWidth, fitHeight)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(sourceLocation, 0)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix, 0)
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(positionLocation)
        vertices.position(2)
        GLES20.glVertexAttribPointer(coordLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(coordLocation)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // The encoder stamps its frames with this, and the hub reads the same clock when sending.
        EGLExt.eglPresentationTimeANDROID(display, output.target, System.nanoTime())
        if (!EGL14.eglSwapBuffers(display, output.target)) {
            Log.w(TAG, "the surface of ${output.id} went away")
            outputs.remove(output.id)
            destroy(output)
        }
    }

    private fun destroy(output: Output) {
        EGL14.eglMakeCurrent(display, idle, idle, context)
        EGL14.eglDestroySurface(display, output.target)
    }

    private fun setUpEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "no EGL display" }
        val wanted = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no usable EGL config" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "cannot create an EGL context" }
        // A context needs some surface to be current on while a frame is taken in, before any watcher has one.
        idle = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, idle, idle, context)) { "cannot use the EGL context" }
    }

    private fun setUpProgram() {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        sourceLocation = GLES20.glGetUniformLocation(program, "source")
        matrixLocation = GLES20.glGetUniformLocation(program, "textureMatrix")
        positionLocation = GLES20.glGetAttribLocation(program, "position")
        coordLocation = GLES20.glGetAttribLocation(program, "coord")
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

    private fun onThread(block: () -> Unit) {
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
}
