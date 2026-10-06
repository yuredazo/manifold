package dev.mkzk.manifold.hub.broker.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.ParcelFileDescriptor
import android.view.Surface
import dev.mkzk.manifold.Manifold
import dev.mkzk.manifold.SenderInfo
import dev.mkzk.manifold.hub.broker.Access
import dev.mkzk.manifold.hub.broker.Owner
import dev.mkzk.manifold.hub.broker.ReceiverLink
import dev.mkzk.manifold.hub.broker.Registry
import java.io.FileInputStream
import java.io.IOException

/** A muted preview still drains the pipe, so the sender is never held up. */
internal class PreviewSound {
    private val pipe = ParcelFileDescriptor.createPipe()
    private val track: AudioTrack

    @Volatile private var playing = true

    val sink: ParcelFileDescriptor get() = pipe[1]

    var muted: Boolean = false
        set(value) {
            field = value
            if (playing) runCatching { track.setVolume(if (value) 0f else 1f) }
        }

    init {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Manifold.AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(Manifold.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum * 2, BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        Thread(::pump, "manifold-sound").start()
    }

    /** The thread ends once the sender has closed its end too, which it does when it is told to stop. */
    fun stop() {
        playing = false
        runCatching { pipe[0].close() }
        runCatching { pipe[1].close() }
    }

    private fun pump() {
        val chunk = ByteArray(CHUNK_BYTES)
        try {
            // The stream wraps the pipe's descriptor without owning it, so it is never closed here.
            val input = FileInputStream(pipe[0].fileDescriptor)
            while (playing) {
                val read = input.read(chunk)
                if (read < 0) break
                track.write(chunk, 0, read)
            }
        } catch (_: IOException) {
            // The pipe was closed.
        } finally {
            playing = false
            runCatching { track.stop() }
            track.release()
        }
    }

    private companion object {
        /**
         * About 60 ms. Whatever sits in this buffer is how far the sound lags the picture, and the hub already smooths
         * what arrives, so a bigger buffer would only add lag.
         */
        const val BUFFER_BYTES = Manifold.AUDIO_SAMPLE_RATE * 4 * 6 / 100
        const val CHUNK_BYTES = 4096
    }
}

/** A receiver like any other, except the hub does not ask itself for permission. */
internal class PreviewSession(
    private val registry: Registry,
    private val owner: Owner,
    private val feedName: String,
    private val newSound: (() -> PreviewSound)? = null,
) {
    private val link = object : ReceiverLink {
        override val key = Any()
        override fun sendersChanged(senders: List<SenderInfo>) {}
        override fun accessChanged(subscriptionId: String, access: Access) {}
    }
    private var subscriptionId: String? = null
    private var sound: PreviewSound? = null

    var muted: Boolean = false
        set(value) {
            field = value
            sound?.muted = value
        }

    fun start(surface: Surface?, width: Int, height: Int): Boolean {
        check(subscriptionId == null) { "the preview is already running" }
        if (!registry.addReceiver(link, owner)) return false
        val playing = newSound?.invoke()?.also { it.muted = muted }
        val id = registry.subscribe(link.key, owner.uid, feedName, surface, width, height, playing?.sink)
        if (id == null) {
            playing?.stop()
            registry.removeReceiver(link.key, owner.uid)
            return false
        }
        subscriptionId = id
        sound = playing
        return true
    }

    fun stop() {
        val id = subscriptionId ?: return
        subscriptionId = null
        registry.unsubscribe(id, owner.uid)
        registry.removeReceiver(link.key, owner.uid)
        sound?.stop()
        sound = null
    }
}
