package dev.mkzk.manifold.hub.live

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.hardware.input.InputManager
import android.os.Build
import android.os.Process
import android.util.Rational
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TouchApp
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
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
import dev.mkzk.manifold.hub.live.control.ButtonEditor
import dev.mkzk.manifold.hub.live.control.ButtonLayouts
import dev.mkzk.manifold.hub.live.control.DirectionPad
import dev.mkzk.manifold.hub.live.control.GameButtons
import dev.mkzk.manifold.hub.live.control.PAD_REPEAT_MS
import dev.mkzk.manifold.hub.live.control.PadReader
import dev.mkzk.manifold.hub.live.control.RemoteKeyboard
import dev.mkzk.manifold.hub.live.control.Stick
import dev.mkzk.manifold.hub.live.control.forwardKey
import dev.mkzk.manifold.hub.live.control.rememberLoupe
import dev.mkzk.manifold.hub.live.control.remoteMouse
import dev.mkzk.manifold.hub.network.CopiedText
import dev.mkzk.manifold.hub.network.PadRumble
import dev.mkzk.manifold.hub.network.RemoteAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

private const val CONTROLS_SHOWN_MS = 3_000L
private val RailWidth = 56.dp

private val PointerArrow = ImageVector.Builder("PointerArrow", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        pathData = PathParser().parsePathString("M6 3.5v15.2l4.1-3.9 2.7 6.1 2.6-1.1-2.7-6h5.6z").toNodes(),
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.8f,
        strokeLineJoin = StrokeJoin.Round,
    )
    .build()
private const val CONTROL_PREFS = "remote_control"
private const val MAGNIFY_KEY = "magnify"

// A swipe across the picture turns as far as the mouse crossing the shared window twice; once felt slow in a game.
private const val LOOK_SPEED = 2f
private val LiveRed = Color(0xFFFF4D4D)

internal class PreviewStats(val fps: Float, val pingMs: Int?)

@Composable
internal fun FeedPreview(
    registry: Registry,
    feed: SenderRow,
    title: String,
    stats: PreviewStats?,
    live: Boolean,
    canControl: Boolean,
    captured: Set<String>,
    sendInput: (String, RemoteAction) -> Unit,
    rumbles: Flow<PadRumble>,
    copied: Flow<CopiedText>,
    onClose: () -> Unit,
) {
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
    var steering by remember(feed.name) { mutableStateOf(false) }
    val steer: (RemoteAction) -> Unit = { action -> session.subscription?.let { sendInput(it, action) } }
    if (!canControl) steering = false
    var typing by remember(feed.name) { mutableStateOf(false) }
    if (!steering) typing = false
    val keys = remember { FocusRequester() }
    LaunchedEffect(steering) { if (steering) runCatching { keys.requestFocus() } }
    var shownView by remember(feed.name) { mutableStateOf<View?>(null) }
    val loupe = rememberLoupe(shownView)
    val controlPrefs = remember { context.getSharedPreferences(CONTROL_PREFS, Context.MODE_PRIVATE) }
    var magnify by remember { mutableStateOf(controlPrefs.getBoolean(MAGNIFY_KEY, true)) }
    val buttonLayouts = remember { ButtonLayouts(context, context.getString(R.string.layout_default, 1), context.getString(R.string.layout_controller)) }
    var editingButtons by remember(feed.name) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val gameOnPc = session.subscription?.let { it in captured } ?: false
    // A mode picked by hand holds until the PC reports the next change.
    var forced by remember(feed.name, gameOnPc) { mutableStateOf<Boolean?>(null) }
    val game = forced ?: gameOnPc
    if (!steering || !game) editingButtons = false
    LaunchedEffect(game) { if (steering) steer(RemoteAction.Release) }
    // Declared after the session's, so it is disposed first and lets go of the other device's buttons while the stream still exists.
    DisposableEffect(session, steering) { onDispose { if (steering) steer(RemoteAction.Release) } }
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
                .pointerInput(steering) { if (!steering) detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { if (!feed.soundOnly) fill = !fill }) },
            contentAlignment = Alignment.Center,
        ) {
            // A feed without a size fills the screen, since its sender draws for that shape.
            val ratio = if (feed.width > 0 && feed.height > 0) feed.width.toFloat() / feed.height else maxWidth / maxHeight
            val screenIsWider = maxWidth / maxHeight > ratio
            val pictureWidth = if (screenIsWider != fill) maxHeight * ratio else maxWidth
            val picture = with(LocalDensity.current) {
                val size = Size(pictureWidth.toPx(), (pictureWidth / ratio).toPx())
                Rect(Offset((maxWidth.toPx() - size.width) / 2, (maxHeight.toPx() - size.height) / 2), size)
            }
            val beside = with(LocalDensity.current) { (maxWidth.toPx() - picture.right).coerceAtLeast(0f).toDp() }
            val enterPictureInPicture = rememberPictureInPicture(ratio, enabled = !feed.soundOnly) { inPictureInPicture = it }

            if (feed.soundOnly) {
                LaunchedEffect(session) { refused = !session.start(null, 1, 1) }
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(72.dp), tint = Color.White.copy(alpha = 0.6f))
            } else {
                Box(modifier = Modifier.requiredSize(pictureWidth, pictureWidth / ratio)) {
                    if (feed.hasAlpha) Checkerboard()
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            pictureView(
                                viewContext,
                                texture = feed.hasAlpha || canControl,
                                transparent = feed.hasAlpha,
                                onReady = { surface, width, height -> refused = !session.start(surface, width, height) },
                                onGone = session::stop,
                            )
                                .also { shownView = it }
                        },
                    )
                }
            }
            if (steering && !feed.soundOnly) {
                val speed = LOOK_SPEED * if (feed.width > 0) feed.width / picture.width else 1f
                val view = LocalView.current
                val pads = remember(session) { PadReader(view.context, steer) }
                DisposableEffect(view, pads) {
                    view.setOnGenericMotionListener { _, event -> pads.onMotion(event) }
                    val inputs = view.context.getSystemService(InputManager::class.java)
                    val unplugged = object : InputManager.InputDeviceListener {
                        override fun onInputDeviceAdded(deviceId: Int) {}

                        override fun onInputDeviceChanged(deviceId: Int) {}

                        override fun onInputDeviceRemoved(deviceId: Int) = pads.forget(deviceId)
                    }
                    inputs.registerInputDeviceListener(unplugged, null)
                    onDispose {
                        view.setOnGenericMotionListener(null)
                        inputs.unregisterInputDeviceListener(unplugged)
                        pads.stopRumble()
                    }
                }
                LaunchedEffect(pads) {
                    while (true) {
                        delay(PAD_REPEAT_MS)
                        pads.repeat()
                    }
                }
                LaunchedEffect(copied) {
                    val clipboard = view.context.getSystemService(ClipboardManager::class.java)
                    copied.collect { text ->
                        if (text.subscriptionId == session.subscription) clipboard?.setPrimaryClip(ClipData.newPlainText(title, text.text))
                    }
                }
                val clipboard = remember(view) { view.context.getSystemService(ClipboardManager::class.java) }
                val controlSince = remember(session) { System.currentTimeMillis() }
                // What was copied before control began stays on the phone.
                fun sendCopy() {
                    val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 && it.description.timestamp >= controlSince } ?: return
                    val text = clip.getItemAt(0).coerceToText(view.context)?.toString()
                    if (!text.isNullOrEmpty()) steer(RemoteAction.Clipboard(text))
                }
                DisposableEffect(clipboard, steer) {
                    val copied = ClipboardManager.OnPrimaryClipChangedListener { sendCopy() }
                    clipboard?.addPrimaryClipChangedListener(copied)
                    onDispose { clipboard?.removePrimaryClipChangedListener(copied) }
                }
                // Android lets only the app in front read the clipboard, so a copy made in another app is picked up on return.
                val focused = LocalWindowInfo.current.isWindowFocused
                LaunchedEffect(focused) { if (focused) sendCopy() }
                LaunchedEffect(pads, rumbles) {
                    rumbles.collect { rumble -> if (rumble.subscriptionId == session.subscription) pads.rumble(rumble.pad, rumble.large, rumble.small) }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .systemGestureExclusion()
                        .remoteMouse(picture, game, magnify, speed, haptics, loupe, steer)
                        .focusRequester(keys)
                        .onKeyEvent { pads.onKey(it.nativeKeyEvent) || forwardKey(it.nativeKeyEvent, steer) }
                        .focusable(),
                )
                if (typing) {
                    RemoteKeyboard(steer, Modifier.align(Alignment.BottomCenter).padding(horizontal = if (beside >= RailWidth) beside else 0.dp))
                } else if (game && !editingButtons) {
                    if (buttonLayouts.current.buttons.none { it.stick == Stick.LEFT }) {
                        DirectionPad(steer, Modifier.align(Alignment.BottomStart).fillMaxWidth(0.5f).fillMaxHeight(0.5f))
                    }
                    GameButtons(buttonLayouts.current, pads, speed, steer)
                }
                if (editingButtons) ButtonEditor(buttonLayouts, onDone = { editingButtons = false })
            }
            if (steering && !inPictureInPicture && !editingButtons) {
                val density = LocalDensity.current
                val below = with(density) { (maxHeight.toPx() - picture.bottom).coerceAtLeast(0f).toDp() }
                val above = with(density) { picture.top.coerceAtLeast(0f).toDp() }
                val (vertical, roomy, place) = when {
                    beside >= RailWidth -> Triple(
                        true,
                        true,
                        Modifier.align(if (typing) Alignment.TopEnd else Alignment.CenterEnd).safeDrawingPadding().width(beside).padding(vertical = 12.dp),
                    )
                    !typing && !game && below >= RailWidth -> Triple(false, true, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().height(below))
                    above >= RailWidth -> Triple(false, true, Modifier.align(Alignment.TopCenter).safeDrawingPadding().height(above))
                    else -> Triple(true, false, Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(end = 6.dp))
                }
                ControlRail(
                    game = game,
                    typing = typing,
                    roomy = roomy,
                    vertical = vertical,
                    onMode = { forced = !game },
                    magnify = magnify,
                    onMagnify = {
                        magnify = !magnify
                        controlPrefs.edit().putBoolean(MAGNIFY_KEY, magnify).apply()
                    },
                    onKeyboard = { typing = !typing },
                    onButtons = {
                        typing = false
                        editingButtons = true
                    },
                    onStop = { steering = false },
                    modifier = place,
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
                visible = controls && !inPictureInPicture && !steering,
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
                        onControl = if (canControl) ({ steering = true }) else null,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlRail(
    game: Boolean,
    typing: Boolean,
    roomy: Boolean,
    vertical: Boolean,
    onMode: () -> Unit,
    magnify: Boolean,
    onMagnify: () -> Unit,
    onKeyboard: () -> Unit,
    onButtons: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttons = @Composable {
        RailButton(
            if (game) Icons.Outlined.SportsEsports else Icons.Outlined.Mouse,
            stringResource(if (game) R.string.preview_mode_game else R.string.preview_mode_pointer),
            active = game,
            roomy = roomy,
            onClick = onMode,
        )
        if (game) {
            RailButton(Icons.Outlined.DashboardCustomize, stringResource(R.string.buttons_edit), active = false, roomy = roomy, onClick = onButtons)
        } else {
            RailButton(
                if (magnify) PointerArrow else Icons.Outlined.TouchApp,
                stringResource(if (magnify) R.string.preview_touch_magnifier else R.string.preview_touch_direct),
                active = false,
                roomy = roomy,
                onClick = onMagnify,
            )
        }
        RailButton(
            Icons.Outlined.Keyboard,
            stringResource(if (typing) R.string.preview_keyboard_close else R.string.preview_keyboard),
            active = typing,
            roomy = roomy,
            onClick = onKeyboard,
        )
        RailButton(Icons.Outlined.Close, stringResource(R.string.preview_control_stop), active = false, roomy = roomy, onClick = onStop)
    }
    if (vertical) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) { buttons() }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { buttons() }
    }
}

@Composable
private fun RailButton(icon: ImageVector, description: String, active: Boolean, roomy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (roomy) 44.dp else 36.dp)
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = if (roomy) 0.5f else 0.35f))
            .border(1.dp, if (active) Color.Transparent else Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = if (roomy) 1f else 0.8f),
            modifier = Modifier.size(if (roomy) 22.dp else 18.dp),
        )
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
    onControl: (() -> Unit)?,
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
        if (onControl != null) {
            IconButton(onClick = onControl) {
                Icon(Icons.Outlined.Mouse, contentDescription = stringResource(R.string.preview_control), tint = Color.White)
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

// A transparent feed needs a view that is composited with what is behind it, which a SurfaceView is not. A controlled feed
// needs one too: the magnifier copies the app's window, and a SurfaceView's own surface came out blank between frames.
private fun pictureView(context: Context, texture: Boolean, transparent: Boolean, onReady: (Surface, Int, Int) -> Unit, onGone: () -> Unit): View {
    if (texture) {
        return TextureView(context).apply {
            isOpaque = !transparent
            var own: Surface? = null
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    own = Surface(texture).also { onReady(it, width, height) }
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    onGone()
                    own?.release()
                    own = null
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
            }
        }
    }
    return SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            private var started = false

            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (started) return
                started = true
                onReady(holder.surface, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                started = false
                onGone()
            }
        })
    }
}

@Composable
private fun Checkerboard() {
    Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val square = 12.dp.toPx()
        drawRect(Color(0xFF3A3A3A))
        for (row in 0..(size.height / square).toInt()) {
            for (column in row % 2..(size.width / square).toInt() step 2) {
                drawRect(Color(0xFF5A5A5A), Offset(column * square, row * square), Size(square, square))
            }
        }
    }
}

/** Null where the phone has no picture-in-picture or the feed has no picture. */
@Composable
private fun rememberPictureInPicture(ratio: Float, enabled: Boolean, onModeChanged: (Boolean) -> Unit): (() -> Unit)? {
    val activity = LocalContext.current.componentActivity()
    val supported = activity != null && enabled
    val latestModeChanged by rememberUpdatedState(onModeChanged)
    // Android refuses shapes wider than about 2.39 to 1 or taller than 1 to 2.39.
    val shape = Rational((ratio.coerceIn(0.42f, 2.39f) * 1000).toInt(), 1000)
    DisposableEffect(activity, shape, supported) {
        if (!supported) return@DisposableEffect onDispose {}
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
    if (!supported) return null
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
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
