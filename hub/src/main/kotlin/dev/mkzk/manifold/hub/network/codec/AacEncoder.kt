package dev.mkzk.manifold.hub.network.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.mkzk.manifold.Manifold
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.IOException

private const val TAG = "AacEncoder"
private const val BITRATE = 128_000
private const val BYTES_PER_SAMPLE = 2 * Manifold.AUDIO_CHANNELS

/** One AAC frame covers 1024 samples, so reading that much at a time gives one frame per read. */
private const val CHUNK_BYTES = 1024 * BYTES_PER_SAMPLE
private const val POLL_US = 10_000L

/** Timestamps follow the monotonic clock the picture is stamped with, so a receiver can keep sound and picture together. */
internal class AacEncoder(
    private val source: ParcelFileDescriptor,
    private val onFrame: (timestamp: Int, frame: ByteArray) -> Unit,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

    @Volatile private var running = true

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Manifold.AUDIO_SAMPLE_RATE, Manifold.AUDIO_CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: RuntimeException) {
            codec.release()
            throw e
        }
        Thread(::pump, "manifold-aac").start()
    }

    fun stop() {
        running = false
        runCatching { source.close() }
    }

    private fun pump() {
        val bufferInfo = MediaCodec.BufferInfo()
        val chunk = ByteArray(CHUNK_BYTES)
        var samples = 0L
        val sampleClock = SampleClock(Manifold.AUDIO_SAMPLE_RATE)
        try {
            // The stream wraps the pipe's descriptor without owning it, so it is never closed here.
            val input = DataInputStream(FileInputStream(source.fileDescriptor))
            while (running) {
                input.readFully(chunk)
                val chunkStartUs = sampleClock.stamp(System.nanoTime() / 1000, samples, CHUNK_BYTES / BYTES_PER_SAMPLE)
                var offset = 0
                while (offset < chunk.size && running) {
                    val index = codec.dequeueInputBuffer(POLL_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = minOf(chunk.size - offset, buffer.capacity())
                        buffer.clear()
                        buffer.put(chunk, offset, size)
                        codec.queueInputBuffer(index, 0, size, chunkStartUs + (offset / BYTES_PER_SAMPLE) * 1_000_000L / Manifold.AUDIO_SAMPLE_RATE, 0)
                        samples += size / BYTES_PER_SAMPLE
                        offset += size
                    }
                    drain(bufferInfo)
                }
            }
        } catch (_: IOException) {
            // The feed closed the pipe, which is how it says it is done.
        } catch (e: IllegalStateException) {
            Log.w(TAG, "encoder stopped: ${e.message}")
        } finally {
            running = false
            runCatching { source.close() }
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun drain(bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index < 0) continue
            val frame = ByteArray(bufferInfo.size)
            codec.getOutputBuffer(index)!!.apply {
                position(bufferInfo.offset)
                limit(bufferInfo.offset + bufferInfo.size)
                get(frame)
            }
            codec.releaseOutputBuffer(index, false)
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) continue
            // 90 kHz ticks from microseconds.
            onFrame((bufferInfo.presentationTimeUs * 9 / 100).toInt(), frame)
        }
    }
}
