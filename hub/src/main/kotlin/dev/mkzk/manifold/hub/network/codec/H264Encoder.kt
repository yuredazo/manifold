package dev.mkzk.manifold.hub.network.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import dev.mkzk.manifold.hub.network.protocol.Frame

private const val TAG = "H264Encoder"
private const val KEYFRAME_SECONDS = 2
private const val REPEAT_AFTER_US = 200_000L
private const val POLL_US = 100_000L

/**
 * A frame older than this when it comes out is a repeat of an unchanged picture, not a measure of the
 * encoder.
 */
private const val REPEAT_THRESHOLD_US = REPEAT_AFTER_US - 20_000L

/** Every keyframe carries the stream parameters, so a late receiver can start from it. */
internal class H264Encoder(width: Int, height: Int, bitrateKbps: Int, frameRate: Int, private val onFrame: (Frame) -> Unit) {

    private val codec: MediaCodec
    val surface: Surface

    @Volatile private var running = true
    private val drain: Thread

    init {
        codec = open(width, height, bitrateKbps, frameRate)
        surface = codec.createInputSurface()
        codec.start()
        drain = Thread(::drainLoop, "manifold-encoder").apply { start() }
    }

    fun requestKeyframe() {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: IllegalStateException) {
            // Already stopped.
        }
    }

    fun setBitrate(kbps: Int) {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) })
        } catch (_: IllegalStateException) {
            // Already stopped.
        }
    }

    /** Stops on another thread, so a caller that holds a lock does not wait for the codec. */
    fun stop() {
        running = false
        Thread {
            drain.join(500)
            runCatching { codec.stop() }
            codec.release()
        }.start()
    }

    /** Some encoders refuse the low latency options, so a plain configuration is the fallback. */
    private fun open(width: Int, height: Int, bitrateKbps: Int, frameRate: Int): MediaCodec {
        for (tuned in listOf(true, false)) {
            val candidate = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                candidate.configure(format(width, height, bitrateKbps, frameRate, tuned), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return candidate
            } catch (e: RuntimeException) {
                candidate.release()
                Log.w(TAG, "configuration (tuned=$tuned) failed: ${e.message}")
                if (!tuned) throw e
            }
        }
        error("unreachable")
    }

    private fun format(width: Int, height: Int, bitrateKbps: Int, frameRate: Int, tuned: Boolean) =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // A feed that draws faster than was asked for would otherwise be encoded at its own speed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_SECONDS)
            if (tuned) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                // Baseline never reorders frames, so one can be shown the moment it is decoded.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            // A picture that does not change would otherwise produce no frames and look like a dead stream.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
        }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        var parameters: ByteArray? = null
        var nextId = 0
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(bufferInfo, POLL_US)
                if (index < 0) continue
                val encoded = ByteArray(bufferInfo.size)
                codec.getOutputBuffer(index)!!.apply {
                    position(bufferInfo.offset)
                    limit(bufferInfo.offset + bufferInfo.size)
                    get(encoded)
                }
                codec.releaseOutputBuffer(index, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    parameters = encoded
                    continue
                }
                val keyframe = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val bytes = if (keyframe && parameters != null) parameters + encoded else encoded
                // Surface frames are stamped from the monotonic clock when drawn, which is what nanoTime
                // reads. Any other clock gives an out-of-range number.
                val lagUs = System.nanoTime() / 1000 - bufferInfo.presentationTimeUs
                val lagMs = if (lagUs in 0 until REPEAT_THRESHOLD_US) lagUs / 1000f else -1f
                // 90 kHz ticks from microseconds.
                onFrame(Frame(nextId++, (bufferInfo.presentationTimeUs * 9 / 100).toInt(), keyframe, bytes, lagMs))
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "encoder stopped: ${e.message}")
        }
    }
}
