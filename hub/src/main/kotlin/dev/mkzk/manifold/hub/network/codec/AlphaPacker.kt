package dev.mkzk.manifold.hub.network.codec

import android.opengl.GLES20
import android.util.Log
import android.view.Surface

private const val TAG = "AlphaPacker"

private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES source;
uniform float alphaHalf;
varying vec2 uv;
void main() {
    vec4 pixel = texture2D(source, uv);
    gl_FragColor = alphaHalf > 0.5 ? vec4(pixel.a, pixel.a, pixel.a, 1.0) : vec4(pixel.rgb, 1.0);
}
"""

// The encoder surface is twice as tall: colour on top, alpha as grey below, so one stream carries both and they stay in step.
internal class AlphaPacker(private val width: Int, private val height: Int, encoderSurface: Surface) :
    AlphaPass(encoderSurface, withAlpha = false, FRAGMENT, GLES20.GL_LINEAR, width, height) {

    private val alphaHalf by lazy { GLES20.glGetUniformLocation(program, "alphaHalf") }

    override fun draw(frameTimestampNs: Long) {
        GLES20.glViewport(0, height, width, height)
        GLES20.glUniform1f(alphaHalf, 0f)
        gl.drawQuad()
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUniform1f(alphaHalf, 1f)
        gl.drawQuad()
        // A feed stamps its frames when it draws them, the clock the encoder's lag measurement reads.
        val stamp = frameTimestampNs.takeIf { it > 0 } ?: System.nanoTime()
        if (!gl.swap(output, stamp)) Log.w(TAG, "the encoder surface went away")
    }
}
