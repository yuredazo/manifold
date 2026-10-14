package dev.mkzk.manifold.hub.screen

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import dev.mkzk.manifold.hub.gl.GlContext
import dev.mkzk.manifold.hub.gl.QUAD_VERTEX
import kotlin.math.min

private const val TAG = "ScreenFrames"
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES source;
varying vec2 uv;
void main() {
    gl_FragColor = texture2D(source, uv);
}
"""

/**
 * Android 14 lets one screen capture drive a single virtual display, so every watcher's encoder is fed by drawing that
 * display's picture once per frame into each of their surfaces.
 */
internal class ScreenFrames(private var width: Int, private var height: Int, maxFps: Int) {
    private class Output(val id: String, val target: EGLSurface, val width: Int, val height: Int)

    private val gl = GlContext(withAlpha = false)
    private val outputs = LinkedHashMap<String, Output>()
    private val textureMatrix = FloatArray(16)
    private val minInterval = NANOS_PER_SECOND / maxFps * 9 / 10
    private val redraw = Runnable {
        redrawQueued = false
        drawAll()
    }

    private var program = 0
    private var matrixLocation = 0
    private var texture = 0
    private var frames: SurfaceTexture? = null
    private var hasFrame = false
    private var released = false
    private var lastDrawAt = 0L
    private var redrawQueued = false

    lateinit var surface: Surface
        private set

    init {
        try {
            gl.run {
                program = gl.program(QUAD_VERTEX, FRAGMENT)
                matrixLocation = GLES20.glGetUniformLocation(program, "textureMatrix")
                texture = gl.externalTexture(GLES20.GL_LINEAR)
                val source = SurfaceTexture(texture)
                source.setDefaultBufferSize(width, height)
                source.setOnFrameAvailableListener({ drawLatest() }, gl.handler)
                frames = source
                surface = Surface(source)
            }
        } catch (e: Throwable) {
            gl.release()
            throw e
        }
    }

    fun add(id: String, target: Surface, width: Int, height: Int) {
        gl.handler.post {
            if (released) return@post
            val window = try {
                gl.windowSurface(target)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "cannot draw into the surface of $id")
                return@post
            }
            outputs.remove(id)?.let { gl.destroy(it.target) }
            val output = Output(id, window, width, height)
            outputs[id] = output
            // A still screen sends no frame, so a late watcher would see nothing until something moved.
            if (hasFrame) draw(output)
        }
    }

    fun remove(id: String) {
        gl.handler.post { outputs.remove(id)?.let { gl.destroy(it.target) } }
    }

    fun resize(width: Int, height: Int) {
        gl.handler.post {
            this.width = width
            this.height = height
            frames?.setDefaultBufferSize(width, height)
            hasFrame = false
        }
    }

    fun release() {
        gl.run {
            released = true
            outputs.values.forEach { gl.destroy(it.target) }
            outputs.clear()
            surface.release()
            frames?.release()
        }
        gl.release()
    }

    private fun drawLatest() {
        val source = frames ?: return
        if (released) return
        gl.makeIdleCurrent()
        try {
            source.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "frame skipped: ${e.message}")
            return
        }
        source.getTransformMatrix(textureMatrix)
        hasFrame = true
        // A feed that draws faster than it announces would be encoded at its own speed, and the screen goes faster than the stream.
        // The frame that comes too soon is drawn when its turn comes, so a screen that stops moving never stays one frame behind.
        val wait = lastDrawAt + minInterval - System.nanoTime()
        if (wait <= 0) {
            drawAll()
        } else if (!redrawQueued) {
            redrawQueued = true
            gl.handler.postDelayed(redraw, wait / NANOS_PER_MILLISECOND + 1)
        }
    }

    private fun drawAll() {
        if (released) return
        lastDrawAt = System.nanoTime()
        outputs.values.toList().forEach(::draw)
    }

    private fun draw(output: Output) {
        gl.makeCurrent(output.target)
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
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix, 0)
        gl.drawQuad()

        // The encoder stamps its frames with this, and the hub reads the same clock when sending.
        if (!gl.swap(output.target, System.nanoTime())) {
            Log.w(TAG, "the surface of ${output.id} went away")
            outputs.remove(output.id)
            gl.destroy(output.target)
        }
    }
}
