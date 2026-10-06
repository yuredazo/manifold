package dev.mkzk.manifold.hub.broker.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Process
import android.util.Rational
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.ScreenRotation
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.broker.Owner
import dev.mkzk.manifold.hub.broker.Registry
import dev.mkzk.manifold.hub.broker.SenderRow
import kotlinx.coroutines.delay

private const val CONTROLS_SHOWN_MS = 3_000L
private val LiveRed = Color(0xFFFF4D4D)

internal class PreviewStats(val fps: Float, val pingMs: Int?)

@Composable
internal fun FeedPreview(registry: Registry, feed: SenderRow, title: String, stats: PreviewStats?, live: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = remember { Owner(Process.myUid(), context.packageName, context.getString(R.string.preview_watcher)) }
    val session = remember(feed.name) {
        PreviewSession(registry, owner, feed.name, newSound = if (feed.hasAudio) ::PreviewSound else null)
    }
    var refused by remember(feed.name) { mutableStateOf(false) }
    var muted by remember(feed.name) { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var fill by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var inPictureInPicture by remember { mutableStateOf(false) }
    DisposableEffect(session) { onDispose { session.stop() } }
    LaunchedEffect(controls) {
        if (controls) {
            delay(CONTROLS_SHOWN_MS)
            controls = false
        }
    }
    KeepOrientation(landscape)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        FullScreenWindow()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { if (!feed.soundOnly) fill = !fill }) },
            contentAlignment = Alignment.Center,
        ) {
            // A feed without a size fills the screen, since its sender draws for that shape.
            val ratio = if (feed.width > 0 && feed.height > 0) feed.width.toFloat() / feed.height else maxWidth / maxHeight
            val screenIsWider = maxWidth / maxHeight > ratio
            val pictureWidth = if (screenIsWider != fill) maxHeight * ratio else maxWidth
            val enterPictureInPicture = rememberPictureInPicture(ratio, enabled = !feed.soundOnly) { inPictureInPicture = it }

            if (feed.soundOnly) {
                LaunchedEffect(session) { refused = !session.start(null, 1, 1) }
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(72.dp), tint = Color.White.copy(alpha = 0.6f))
            } else {
                AndroidView(
                    modifier = Modifier.requiredSize(pictureWidth, pictureWidth / ratio),
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
                visible = controls && !inPictureInPicture,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    TopBar(feed, title, onClose, enterPictureInPicture, Modifier.align(Alignment.TopCenter))
                    BottomBar(
                        feed = feed,
                        live = live,
                        stats = stats,
                        muted = muted,
                        fill = fill,
                        onToggleSound = {
                            muted = !muted
                            session.muted = muted
                        },
                        onToggleFill = { fill = !fill },
                        onRotate = { landscape = !landscape },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(feed: SenderRow, title: String, onClose: () -> Unit, enterPictureInPicture: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
            .safeDrawingPadding()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.preview_close), tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val shape = if (feed.width > 0 && feed.height > 0) "${feed.width}x${feed.height}" else null
            Text(
                listOfNotNull(feed.app, shape).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (enterPictureInPicture != null) {
            IconButton(onClick = enterPictureInPicture) {
                Icon(Icons.Outlined.PictureInPictureAlt, contentDescription = stringResource(R.string.preview_pip), tint = Color.White)
            }
        }
    }
}

@Composable
private fun BottomBar(
    feed: SenderRow,
    live: Boolean,
    stats: PreviewStats?,
    muted: Boolean,
    fill: Boolean,
    onToggleSound: () -> Unit,
    onToggleFill: () -> Unit,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .safeDrawingPadding()
            .padding(start = 16.dp, end = 4.dp, top = 28.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).background(if (live) LiveRed else Color.White.copy(alpha = 0.5f), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (live) R.string.preview_live else R.string.preview_offline),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
        val readout = listOfNotNull(stats?.pingMs?.let { "$it ms" }, stats?.fps?.takeIf { it > 0f }?.let { "${it.toInt()} fps" }).joinToString(" · ")
        if (readout.isNotEmpty()) {
            Text(readout, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.weight(1f))
        if (feed.hasAudio) {
            IconButton(onClick = onToggleSound) {
                Icon(
                    if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = stringResource(if (muted) R.string.preview_unmute else R.string.preview_mute),
                    tint = Color.White,
                )
            }
        }
        if (!feed.soundOnly) {
            IconButton(onClick = onToggleFill) {
                Icon(
                    if (fill) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    contentDescription = stringResource(if (fill) R.string.preview_fit else R.string.preview_fill),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onRotate) {
                Icon(Icons.Outlined.ScreenRotation, contentDescription = stringResource(R.string.preview_rotate), tint = Color.White)
            }
        }
    }
}

@Composable
private fun KeepOrientation(landscape: Boolean) {
    val activity = LocalContext.current.componentActivity() ?: return
    DisposableEffect(activity, landscape) {
        activity.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

/** Null where the phone has no picture-in-picture or the feed has no picture. */
@Composable
private fun rememberPictureInPicture(ratio: Float, enabled: Boolean, onModeChanged: (Boolean) -> Unit): (() -> Unit)? {
    val activity = LocalContext.current.componentActivity()
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null && enabled
    val latestModeChanged by rememberUpdatedState(onModeChanged)
    // Android refuses shapes wider than about 2.39 to 1 or taller than 1 to 2.39.
    val shape = Rational((ratio.coerceIn(0.42f, 2.39f) * 1000).toInt(), 1000)
    DisposableEffect(activity, shape, supported) {
        if (!supported || activity == null) return@DisposableEffect onDispose {}
        val builder = PictureInPictureParams.Builder().setAspectRatio(shape)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(true)
        val params = builder.build()
        activity.setPictureInPictureParams(params)
        // From Android 12 the system enters it by itself, which also covers the gesture to go home.
        val leaving = Runnable { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) activity.enterPictureInPictureMode(params) }
        val changed = Consumer<PictureInPictureModeChangedInfo> { latestModeChanged(it.isInPictureInPictureMode) }
        activity.addOnUserLeaveHintListener(leaving)
        activity.addOnPictureInPictureModeChangedListener(changed)
        onDispose {
            activity.removeOnUserLeaveHintListener(leaving)
            activity.removeOnPictureInPictureModeChangedListener(changed)
            val off = PictureInPictureParams.Builder()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) off.setAutoEnterEnabled(false)
            activity.setPictureInPictureParams(off.build())
        }
    }
    if (!supported || activity == null) return null
    return { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(shape).build()) }
}

private tailrec fun Context.componentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> null
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
