package dev.mkzk.manifold.hub.network.codec

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import dev.mkzk.manifold.hub.gl.GlContext
import dev.mkzk.manifold.hub.gl.QUAD_VERTEX

private const val TAG = "AlphaPass"

internal abstract class AlphaPass(target: Surface, withAlpha: Boolean, fragment: String, filter: Int, bufferWidth: Int = 0, bufferHeight: Int = 0) {

    protected val gl = GlContext(withAlpha)
    protected lateinit var output: EGLSurface
    protected var program = 0
    private val matrix = FloatArray(16)
    private lateinit var source: SurfaceTexture
    private var matrixLocation = 0
    private var texture = 0

    lateinit var surface: Surface
        private set

    init {
        try {
            gl.run {
                output = gl.windowSurface(target)
                program = gl.program(QUAD_VERTEX, fragment)
                matrixLocation = GLES20.glGetUniformLocation(program, "textureMatrix")
                texture = gl.externalTexture(filter)
                source = SurfaceTexture(texture).apply {
                    if (bufferWidth > 0) setDefaultBufferSize(bufferWidth, bufferHeight)
                    setOnFrameAvailableListener({ drawLatest() }, gl.handler)
                }
                surface = Surface(source)
            }
        } catch (e: Throwable) {
            gl.release()
            throw e
        }
    }

    fun release() {
        gl.run {
            surface.release()
            source.release()
            gl.destroy(output)
        }
        gl.release()
    }

    protected abstract fun draw(frameTimestampNs: Long)

    private fun drawLatest() {
        gl.makeIdleCurrent()
        try {
            source.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "frame skipped: ${e.message}")
            return
        }
        source.getTransformMatrix(matrix)
        gl.makeCurrent(output)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0)
        draw(source.timestamp)
    }
}
