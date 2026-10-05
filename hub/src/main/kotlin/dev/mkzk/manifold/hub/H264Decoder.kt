package dev.mkzk.manifold.hub

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import dev.mkzk.manifold.hub.net.Frame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "H264Decoder"
private const val MAX_TRACKED_FRAMES = 64

private const val OPERATING_RATE = 120

/** [onStall] fires when a frame was dropped because the decoder was behind; the picture is wrong until the next keyframe. */
internal class H264Decoder(
    surface: Surface,
    width: Int,
    height: Int,
    private val onStall: () -> Unit,
    private val onShown: (Long) -> Unit = {},
) {

    private val thread = HandlerThread("manifold-decoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val freeInputs = ArrayDeque<Int>()
    private class Queued(val pushedAt: Long, val renderAt: Long)

    private val queued = HashMap<Long, Queued>()
    private var stopped = false

    init {
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                freeInputs.addLast(index)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val frame = queued.remove(info.presentationTimeUs)
                if (frame != null && frame.renderAt > 0) {
                    codec.releaseOutputBuffer(index, frame.renderAt)
                } else {
                    codec.releaseOutputBuffer(index, true)
                }
                frame?.let { onShown(System.nanoTime() - it.pushedAt) }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "decoder failed", e)
            }
        }, handler)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, OPERATING_RATE)
        }
        try {
            codec.configure(format, surface, null, 0)
            codec.start()
        } catch (e: RuntimeException) {
            codec.release()
            thread.quitSafely()
            throw e
        }
    }

    /** [renderAt] is on the `System.nanoTime` clock. Zero means as soon as the picture is ready. */
    fun push(frame: Frame, renderAt: Long = 0L) {
        handler.post {
            if (stopped) return@post
            val index = freeInputs.removeFirstOrNull()
            if (index == null) {
                onStall()
                return@post
            }
            val input = codec.getInputBuffer(index)!!
            if (frame.encoded.size > input.capacity()) {
                codec.queueInputBuffer(index, 0, 0, 0, 0)
                onStall()
                return@post
            }
            input.clear()
            input.put(frame.encoded)
            val timestampUs = frame.timestamp * 100L / 9
            // A frame the decoder swallows without showing would leave its entry behind.
            if (queued.size > MAX_TRACKED_FRAMES) queued.clear()
            queued[timestampUs] = Queued(System.nanoTime(), renderAt)
            codec.queueInputBuffer(index, 0, frame.encoded.size, timestampUs, 0)
        }
    }

    /** Returns once the decoder has stopped drawing, so the caller may release the surface. */
    fun stop() {
        val done = CountDownLatch(1)
        handler.post {
            stopped = true
            runCatching { codec.stop() }
            codec.release()
            thread.quitSafely()
            done.countDown()
        }
        done.await(300, TimeUnit.MILLISECONDS)
    }
}
