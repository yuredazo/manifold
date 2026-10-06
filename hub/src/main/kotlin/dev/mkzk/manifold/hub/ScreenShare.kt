package dev.mkzk.manifold.hub

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import dev.mkzk.manifold.Manifold
import dev.mkzk.manifold.SenderInfo
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "ScreenShare"
private const val FEED_NAME = "Screen"
private const val FEED_FPS = 30
private const val LONG_EDGE_LIMIT = 1920

/** One AAC frame is 1024 samples, which is what the encoder reads at a time. */
private const val SOUND_CHUNK_BYTES = 1024 * 2 * Manifold.AUDIO_CHANNELS

internal class CaptureSize(val width: Int, val height: Int, val dpi: Int)

/** Capped so a large screen does not become a 4K stream, and even because encoders require it. */
internal fun scaledCapture(widthPixels: Int, heightPixels: Int, dpi: Int): CaptureSize {
    val scale = min(1f, LONG_EDGE_LIMIT.toFloat() / max(widthPixels, heightPixels))
    fun fit(pixels: Int) = max(2, (pixels * scale).toInt() and 1.inv())
    return CaptureSize(fit(widthPixels), fit(heightPixels), dpi)
}

/** The whole screen as a feed, drawn only while somebody watches. */
internal object ScreenShare {
    data class State(val sharing: Boolean = false, val sound: Boolean = false, val failed: Boolean = false)

    private val current = MutableStateFlow(State())
    val state: StateFlow<State> = current

    private var session: Session? = null
    private var onStopped: () -> Unit = {}

    /** [onStopped] runs when the share ends for any reason, including a failure to start. */
    @Synchronized
    fun start(context: Context, projection: MediaProjection, sound: Boolean, onStopped: () -> Unit) {
        // A second start would stop the first, whose callback ends the very service that is starting.
        if (session != null) {
            runCatching { projection.stop() }
            return
        }
        this.onStopped = onStopped
        try {
            val started = Session(context.applicationContext, projection, sound, ::stop)
            session = started
            current.value = State(sharing = true, sound = started.hasSound)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot share the screen", e)
            runCatching { projection.stop() }
            current.value = State(failed = true)
            finish()
        }
    }

    @Synchronized
    fun stop() {
        val ended = session ?: return
        session = null
        ended.close()
        current.value = State()
        finish()
    }

    fun clearFailure() {
        current.update { it.copy(failed = false) }
    }

    private fun finish() {
        val callback = onStopped
        onStopped = {}
        callback()
    }

    private class Session(
        context: Context,
        private val projection: MediaProjection,
        sound: Boolean,
        private val onEnded: () -> Unit,
    ) : SenderLink {
        override val key = Any()

        private val ownUid = Process.myUid()
        private val displays = context.getSystemService(DisplayManager::class.java)
        private val main = Handler(Looper.getMainLooper())
        private val watching = HashSet<String>()
        private var size = measure()
        private val frames = ScreenFrames(size.width, size.height)
        private val voice: SoundCapture? = if (sound) openSound(projection) else null
        private var screen: VirtualDisplay? = null

        val hasSound get() = voice != null

        // Once Android reports the size of what is captured, which for a single app is the app's window and not the display, it is
        // the only size to follow.
        private var contentKnown = false

        private val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() = onEnded()

            override fun onCapturedContentResize(width: Int, height: Int) {
                contentKnown = true
                resize(scaledCapture(width, height, size.dpi))
            }
        }

        private val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY && !contentKnown) resize(measure())
            }

            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }

        init {
            try {
                // Android 14 refuses a virtual display on a projection nobody is listening to.
                projection.registerCallback(projectionCallback, main)
                screen = projection.createVirtualDisplay(
                    "manifold-screen", size.width, size.height, size.dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, main,
                )
                displays.registerDisplayListener(displayListener, main)
                val owner = Owner(ownUid, context.packageName, context.getString(R.string.app_name))
                checkNotNull(Registry.instance.addSender(this, info(), owner)) { "the hub refused the feed" }
            } catch (e: RuntimeException) {
                close()
                throw e
            }
        }

        override fun deliver(subscriptionId: String, surface: Surface?, width: Int, height: Int, audioSink: ParcelFileDescriptor?) {
            if (surface == null) return
            synchronized(watching) {
                // Nothing is composed for a display with no surface, so an unwatched screen costs nothing.
                if (watching.isEmpty()) screen?.surface = frames.surface
                watching.add(subscriptionId)
            }
            frames.add(subscriptionId, surface, width, height)
            if (audioSink != null) voice?.add(subscriptionId, audioSink)
        }

        override fun revoke(subscriptionId: String) {
            frames.remove(subscriptionId)
            voice?.remove(subscriptionId)
            synchronized(watching) {
                if (watching.remove(subscriptionId) && watching.isEmpty()) screen?.surface = null
            }
        }

        fun close() {
            runCatching { displays.unregisterDisplayListener(displayListener) }
            Registry.instance.removeSender(key, ownUid)
            voice?.close()
            screen?.release()
            frames.release()
            runCatching { projection.unregisterCallback(projectionCallback) }
            runCatching { projection.stop() }
        }

        private fun resize(now: CaptureSize) {
            if (now.width == size.width && now.height == size.height) return
            size = now
            screen?.resize(now.width, now.height, now.dpi)
            frames.resize(now.width, now.height)
            Registry.instance.updateSender(key, info(), ownUid)
        }

        private fun measure(): CaptureSize {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            displays.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
            return scaledCapture(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }

        private fun info() = SenderInfo().also {
            it.name = FEED_NAME
            it.width = size.width
            it.height = size.height
            it.fps = FEED_FPS
            it.hasAudio = hasSound
        }
    }
}

private class SoundCapture(private val record: AudioRecord) {
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
private fun openSound(projection: MediaProjection): SoundCapture? {
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
