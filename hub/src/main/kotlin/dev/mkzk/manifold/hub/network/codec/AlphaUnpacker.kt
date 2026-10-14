package dev.mkzk.manifold.hub.network.codec

import android.opengl.GLES20
import android.util.Log
import android.view.Surface

private const val TAG = "AlphaUnpacker"

private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES source;
uniform mat4 textureMatrix;
varying vec2 plain;
void main() {
    vec2 top = (textureMatrix * vec4(plain.x, plain.y * 0.5 + 0.5, 0.0, 1.0)).xy;
    vec2 bottom = (textureMatrix * vec4(plain.x, plain.y * 0.5, 0.0, 1.0)).xy;
    gl_FragColor = vec4(texture2D(source, top).rgb, texture2D(source, bottom).r);
}
"""

// The two halves are read one to one, and a smoothed seam would bleed alpha into colour.
internal class AlphaUnpacker(target: Surface) : AlphaPass(target, withAlpha = true, FRAGMENT, GLES20.GL_NEAREST) {

    private val targetSize = IntArray(2)

    override fun draw(frameTimestampNs: Long) {
        gl.size(output, targetSize)
        GLES20.glViewport(0, 0, targetSize[0], targetSize[1])
        gl.drawQuad()
        if (!gl.swap(output)) Log.w(TAG, "the receiver's surface went away")
    }
}
