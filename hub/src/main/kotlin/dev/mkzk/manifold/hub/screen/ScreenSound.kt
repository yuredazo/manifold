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
import dev.mkzk.manifold.Manifold
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private const val TAG = "ScreenSound"

/** One AAC frame is 1024 samples, which is what the encoder reads at a time. */
private const val SOUND_CHUNK_BYTES = 1024 * 2 * Manifold.AUDIO_CHANNELS

internal class SoundCapture(private val record: AudioRecord) {
    private val sinks = ConcurrentHashMap<String, FileDescriptor>()
    @Volatile private var running = true
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

    fun close() {
        running = false
        runCatching { record.stop() }
        reader.join(500)
        record.release()
    }

    private fun pump() {
        val chunk = ByteArray(SOUND_CHUNK_BYTES)
        while (running) {
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
    }
}

/** Null where Android cannot capture playback or the sound permission was refused: the screen is then shared without it. */
internal fun openSound(projection: MediaProjection): SoundCapture? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return try {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
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
            SoundCapture(record)
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "no permission to capture sound", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "cannot capture sound", e)
        null
    }
}
