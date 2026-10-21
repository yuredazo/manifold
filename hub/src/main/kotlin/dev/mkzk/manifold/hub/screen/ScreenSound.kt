package dev.mkzk.manifold.hub.screen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import dev.mkzk.manifold.Manifold
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max

private const val TAG = "ScreenSound"

/** One AAC frame is 1024 samples, which is what the encoder reads at a time. */
private const val SOUND_CHUNK_BYTES = 1024 * 2 * Manifold.AUDIO_CHANNELS

internal class SoundCapture(
    @Volatile private var record: AudioRecord,
    private val rebuild: (excludedUids: Set<Int>) -> AudioRecord?,
    private val onLost: () -> Unit,
) {
    private val sinks = ConcurrentHashMap<String, FileDescriptor>()
    private val handover = Any()
    private var running = true
    private var ready: AudioRecord? = null
    @Volatile private var rebuildFailed = false
    private val builder = Executors.newSingleThreadExecutor { Thread(it, "manifold-screen-sound-swap") }
    private val reader = Thread(::pump, "manifold-screen-sound")

    init {
        record.startRecording()
        reader.start()
    }

    fun add(id: String, sink: ParcelFileDescriptor) {
        // Not owned: the registry closes the pipe when the subscription ends.
        val descriptor = sink.fileDescriptor
        try {
            Os.fcntlInt(descriptor, OsConstants.F_SETFL, Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
        } catch (e: ErrnoException) {
            Log.w(TAG, "cannot make the sound pipe non-blocking: ${e.message}")
        }
        sinks[id] = descriptor
    }

    fun remove(id: String) {
        sinks.remove(id)
    }

    // The filter of a playback capture is fixed when the recorder is built. Building and starting one takes about half a
    // second, so the next recorder warms up here while the reader keeps draining the current one.
    fun exclude(uids: Set<Int>) {
        builder.execute {
            val next = rebuild(uids)?.apply { startRecording() }
            synchronized(handover) {
                when {
                    !running -> next?.release()
                    // Sound from an app the owner just muted must not keep flowing, so a failed rebuild ends the sound.
                    next == null -> rebuildFailed = true
                    else -> {
                        ready?.release()
                        ready = next
                    }
                }
            }
        }
    }

    fun close() {
        synchronized(handover) {
            running = false
            ready?.release()
            ready = null
        }
        builder.shutdown()
        runCatching { record.stop() }
        reader.join(500)
    }

    private fun pump() {
        val chunk = ByteArray(SOUND_CHUNK_BYTES)
        while (!rebuildFailed) {
            val next = synchronized(handover) {
                if (!running) break
                ready.also { ready = null }
            }
            if (next != null) {
                val previous = record
                record = next
                retire(previous)
            }
            val read = record.read(chunk, 0, chunk.size)
            if (read < 0) break
            for ((id, descriptor) in sinks) {
                try {
                    // A chunk is no bigger than PIPE_BUF, so it goes in whole or not at all. A watcher that has stopped
                    // reading loses that chunk and no one else waits for it.
                    Os.write(descriptor, chunk, 0, read)
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.EAGAIN) sinks.remove(id)
                }
            }
        }
        record.release()
        if (rebuildFailed) onLost()
    }

    // Stopping a recorder takes over 100 ms, close to what the new recorder buffers, so the reader does not wait for it.
    private fun retire(old: AudioRecord) {
        try {
            builder.execute {
                runCatching { old.stop() }
                old.release()
            }
        } catch (_: RejectedExecutionException) {
            old.release()
        }
    }
}

/** Null where Android cannot capture playback or the sound permission was refused: the screen is then shared without it. */
internal fun openSound(projection: MediaProjection, excludedUids: Set<Int>, onLost: () -> Unit): SoundCapture? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return playbackRecord(projection, excludedUids)?.let { SoundCapture(it, { uids -> playbackRecord(projection, uids) }, onLost) }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun playbackRecord(projection: MediaProjection, excludedUids: Set<Int>): AudioRecord? {
    return try {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .apply { excludedUids.forEach(::excludeUid) }
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Manifold.AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minimum = AudioRecord.getMinBufferSize(Manifold.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(capture)
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minimum, SOUND_CHUNK_BYTES * 8))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            null
        } else {
            record
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "no permission to capture sound", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "cannot capture sound", e)
        null
    }
}
