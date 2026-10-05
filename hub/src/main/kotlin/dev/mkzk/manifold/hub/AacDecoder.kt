package dev.mkzk.manifold.hub

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.mkzk.manifold.Manifold
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

private const val TAG = "AacDecoder"
private const val POLL_US = 10_000L

/** Room for the longest playout delay and some more. */
private const val MAX_QUEUED_FRAMES = 64

/** Decoding takes a few milliseconds, and a frame is handed to the decoder this long before it is due. */
private const val DECODE_LEAD_NS = 30_000_000L

/** Linux asks for F_SETPIPE_SZ by number; Android's constants do not list it. */
private const val F_SETPIPE_SZ = 1031

/** Two chunks of sound. What the player has not played yet is what the sound lags the picture by, so the pipe is kept small. */
private const val PIPE_BYTES = 8_192

/**
 * AudioSpecificConfig of the only format that is sent: AAC-LC (object type 2),
 * 48 kHz (index 3), two channels. Fixed here and in the encoders, so it never travels.
 */
private val CONFIG = byteArrayOf(0x11, 0x90.toByte())

/** Writes without waiting: a full pipe takes nothing, which is how [AudioPacer] learns the player is behind. */
private class PipeSink(private val descriptor: FileDescriptor) : PcmSink {
    init {
        try {
            Os.fcntlInt(descriptor, OsConstants.F_SETFL, Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
            Os.fcntlInt(descriptor, F_SETPIPE_SZ, PIPE_BYTES)
        } catch (e: ErrnoException) {
            Log.w(TAG, "cannot size the sound pipe: ${e.message}")
        }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
        try {
            Os.write(descriptor, bytes, offset, length)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EAGAIN) 0 else throw IOException(e)
        }
}

/** Does not own [sink]: the registry closes it. */
internal class AacDecoder(private val sink: ParcelFileDescriptor) {

    private class Due(val frame: ByteArray, val dueAt: Long)

    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val frames = ArrayBlockingQueue<Due>(MAX_QUEUED_FRAMES)

    @Volatile private var running = true
    private val worker: Thread

    @Volatile var speedPpm = 0
        private set

    @Volatile var cutChunks = 0
        private set

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Manifold.AUDIO_SAMPLE_RATE, Manifold.AUDIO_CHANNELS)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(CONFIG))
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: RuntimeException) {
            codec.release()
            throw e
        }
        worker = Thread(::pump, "manifold-aacdec").apply { start() }
    }

    /** [dueAt] is on the `System.nanoTime` clock. */
    fun push(frame: ByteArray, dueAt: Long) {
        if (!running) return
        val due = Due(frame, dueAt)
        if (!frames.offer(due)) {
            frames.poll()
            frames.offer(due)
        }
    }

    fun stop() {
        running = false
        worker.interrupt()
    }

    private fun waitUntil(time: Long) {
        while (running) {
            val left = time - System.nanoTime()
            if (left <= 0) return
            LockSupport.parkNanos(left)
        }
    }

    private fun pump() {
        val bufferInfo = MediaCodec.BufferInfo()
        val pacer = AudioPacer(PipeSink(sink.fileDescriptor), System::nanoTime, ::waitUntil)
        try {
            while (running) {
                val due = frames.poll(5, TimeUnit.MILLISECONDS)
                if (due != null) {
                    waitUntil(due.dueAt - DECODE_LEAD_NS)
                    val index = codec.dequeueInputBuffer(POLL_US)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!
                        input.clear()
                        val size = minOf(due.frame.size, input.capacity())
                        input.put(due.frame, 0, size)
                        codec.queueInputBuffer(index, 0, size, due.dueAt / 1000, 0)
                    }
                }
                drain(bufferInfo, pacer)
            }
        } catch (_: InterruptedException) {
            // Stopped.
        } catch (_: IOException) {
            // The receiver closed its end of the pipe.
        } catch (e: IllegalStateException) {
            Log.w(TAG, "decoder stopped: ${e.message}")
        } finally {
            running = false
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun drain(bufferInfo: MediaCodec.BufferInfo, pacer: AudioPacer) {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index < 0) continue
            val pcm = ByteArray(bufferInfo.size)
            codec.getOutputBuffer(index)!!.apply {
                position(bufferInfo.offset)
                limit(bufferInfo.offset + bufferInfo.size)
                get(pcm)
            }
            val dueAt = bufferInfo.presentationTimeUs * 1000
            codec.releaseOutputBuffer(index, false)
            pacer.play(pcm, dueAt)
            speedPpm = pacer.speedPpm
            cutChunks = pacer.cut
        }
    }
}
