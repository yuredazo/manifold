package dev.mkzk.manifold.hub.broker.ui

import android.os.Build
import android.os.Process
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
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.broker.Owner
import dev.mkzk.manifold.hub.broker.Registry
import dev.mkzk.manifold.hub.broker.SenderRow
import kotlinx.coroutines.delay

private const val CONTROLS_SHOWN_MS = 3_000L

@Composable
internal fun FeedPreview(registry: Registry, feed: SenderRow, title: String, live: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = remember { Owner(Process.myUid(), context.packageName, context.getString(R.string.preview_watcher)) }
    val session = remember(feed.name) {
        PreviewSession(registry, owner, feed.name, newSound = if (feed.hasAudio) ::PreviewSound else null)
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
                    title = title,
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
private fun TopBar(feed: SenderRow, title: String, muted: Boolean, onClose: () -> Unit, onToggleSound: () -> Unit) {
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
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
