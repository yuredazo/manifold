package dev.mkzk.manifold.hub

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.mkzk.manifold.Manifold
import dev.mkzk.manifold.SenderInfo
import kotlinx.coroutines.delay
import java.io.FileInputStream
import java.io.IOException

private const val CONTROLS_SHOWN_MS = 3_000L

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

@Composable
internal fun FeedPreview(feed: SenderRow, live: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = remember { Owner(Process.myUid(), context.packageName, context.getString(R.string.preview_watcher)) }
    val session = remember(feed.name) {
        PreviewSession(Registry.instance, owner, feed.name, newSound = if (feed.hasAudio) ::PreviewSound else null)
    }
    var refused by remember(feed.name) { mutableStateOf(false) }
    var muted by remember(feed.name) { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    DisposableEffect(session) { onDispose { session.stop() } }
    LaunchedEffect(controls) {
        if (controls) {
            delay(CONTROLS_SHOWN_MS)
            controls = false
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        FullScreenWindow()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { controls = !controls } },
            contentAlignment = Alignment.Center,
        ) {
            // A feed with a known shape keeps it, with bars around it. One without a size fills the screen,
            // and its sender draws for that shape.
            val screen = LocalConfiguration.current
            val ratio = if (feed.width > 0 && feed.height > 0) {
                feed.width.toFloat() / feed.height
            } else {
                screen.screenWidthDp.toFloat() / screen.screenHeightDp
            }
            if (feed.soundOnly) {
                LaunchedEffect(session) { refused = !session.start(null, 1, 1) }
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(72.dp), tint = Color.White.copy(alpha = 0.6f))
            } else {
                AndroidView(
                    modifier = Modifier.aspectRatio(ratio),
                    factory = { viewContext ->
                        SurfaceView(viewContext).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                private var started = false

                                override fun surfaceCreated(holder: SurfaceHolder) {}

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    if (started) return
                                    started = true
                                    refused = !session.start(holder.surface, width, height)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    started = false
                                    session.stop()
                                }
                            })
                        }
                    },
                )
            }
            if (refused || !live) {
                Text(
                    stringResource(if (refused) R.string.preview_refused else R.string.preview_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (refused) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(32.dp),
                )
            }
            AnimatedVisibility(
                visible = controls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopBar(
                    feed = feed,
                    muted = muted,
                    onClose = onClose,
                    onToggleSound = {
                        muted = !muted
                        session.muted = muted
                    },
                )
            }
        }
    }
}

@Composable
private fun TopBar(feed: SenderRow, muted: Boolean, onClose: () -> Unit, onToggleSound: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.preview_close), tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(feed.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                feed.app,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (feed.hasAudio) {
            IconButton(onClick = onToggleSound) {
                Icon(
                    if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = stringResource(if (muted) R.string.preview_unmute else R.string.preview_mute),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun FullScreenWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = ((view.parent as? DialogWindowProvider) ?: (view as? DialogWindowProvider))?.window
        if (window != null) {
            window.setDimAmount(0f)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.also {
                    it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {}
    }
}
