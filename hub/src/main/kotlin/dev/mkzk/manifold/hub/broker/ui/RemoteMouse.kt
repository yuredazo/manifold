package dev.mkzk.manifold.hub.broker.ui

import android.view.View
import android.widget.Magnifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.network.RemoteAction
import dev.mkzk.manifold.hub.network.protocol.MouseButton
import kotlin.math.roundToInt

private const val WHEEL_NOTCH = 120
private const val RIGHT_CLICK_HOLD_MS = 450L
private const val LOUPE_ZOOM = 2f
private const val PINCH_TOGETHER_MS = 150L

// A finger that slides this far, or a pinch that opens this far, turns the wheel by one notch.
private val ScrollStep = 32.dp
private val LoupeSize = 128.dp

// Far enough above the touch that the fingertip does not cover the circle.
private val LoupeLift = 110.dp

private fun PointerButtons.held(): Set<MouseButton> = buildSet {
    if (isPrimaryPressed) add(MouseButton.LEFT)
    if (isSecondaryPressed) add(MouseButton.RIGHT)
    if (isTertiaryPressed) add(MouseButton.MIDDLE)
    if (isBackPressed) add(MouseButton.BACK)
    if (isForwardPressed) add(MouseButton.FORWARD)
}

private class Aim(
    val haptics: HapticFeedback,
    val slop: Float,
    val scrollStep: Float,
    val send: (RemoteAction) -> Unit,
    val share: (Offset) -> Pair<Float, Float>,
) {
    fun move(at: Offset) = share(at).let { (x, y) -> send(RemoteAction.Move(x, y)) }

    fun press(button: MouseButton, down: Boolean, at: Offset) = share(at).let { (x, y) -> send(RemoteAction.Press(button, down, x, y)) }

    fun click(button: MouseButton, at: Offset) {
        press(button, true, at)
        press(button, false, at)
    }
}

// It covers the whole screen, since a second finger often lands beside a picture that fills only a band of it.
// speed turns phone pixels into the shared window's pixels, so a swipe across the picture crosses the window.
internal fun Modifier.remoteMouse(
    picture: Rect,
    game: Boolean,
    magnify: Boolean,
    speed: Float,
    haptics: HapticFeedback,
    onAim: (Offset?) -> Unit,
    send: (RemoteAction) -> Unit,
): Modifier = pointerInput(picture, game, magnify, speed) {
    val aim = Aim(haptics, viewConfiguration.touchSlop, ScrollStep.toPx(), send) { at ->
        ((at.x - picture.left) / picture.width).coerceIn(0f, 1f) to ((at.y - picture.top) / picture.height).coerceIn(0f, 1f)
    }
    val aimInPicture: (Offset?) -> Unit = { at -> onAim(at?.let { it - picture.topLeft }) }
    if (game) {
        awaitPointerEventScope { play(aim, speed) }
        return@pointerInput
    }
    awaitPointerEventScope {
        var held = emptySet<MouseButton>()
        while (true) {
            val event = awaitPointerEvent()
            val first = event.changes.firstOrNull() ?: continue
            if (first.type == PointerType.Mouse) {
                held = mouse(event, held, aim)
            } else if (first.pressed && !first.previousPressed) {
                event.changes.forEach { it.consume() }
                if (magnify) {
                    try {
                        aimWithFinger(first, aim, aimInPicture)
                    } finally {
                        aimInPicture(null)
                    }
                } else {
                    touch(first, aim)
                }
            }
            event.changes.forEach { it.consume() }
        }
    }
}

@Composable
internal fun rememberLoupe(view: View?): (Offset?) -> Unit {
    val density = LocalDensity.current
    val magnifier = remember(view) {
        view?.let {
            val size = with(density) { LoupeSize.roundToPx() }
            Magnifier.Builder(it)
                .setSize(size, size)
                .setCornerRadius(size / 2f)
                .setInitialZoom(LOUPE_ZOOM)
                .build()
        }
    }
    val lift = with(density) { LoupeLift.toPx() }
    val half = with(density) { LoupeSize.toPx() / 2 }
    DisposableEffect(magnifier) { onDispose { magnifier?.dismiss() } }
    return remember(magnifier) {
        { at: Offset? ->
            if (at == null) {
                magnifier?.dismiss()
            } else if (magnifier != null && view != null) {
                // Android pushes a magnifier that would leave the window back inside it, onto the finger, so near the top it goes below.
                val origin = IntArray(2).also(view::getLocationInWindow)
                val y = if (origin[1] + at.y - lift - half < 0) at.y + lift else at.y - lift
                magnifier.show(at.x, at.y, at.x, y)
            }
        }
    }
}

private fun mouse(event: PointerEvent, held: Set<MouseButton>, aim: Aim): Set<MouseButton> {
    val change = event.changes.first()
    if (event.type == PointerEventType.Scroll) {
        // Compose counts a notch down as +1, Windows counts a notch up as +120.
        aim.send(RemoteAction.Scroll((change.scrollDelta.x * WHEEL_NOTCH).roundToInt(), (-change.scrollDelta.y * WHEEL_NOTCH).roundToInt()))
        return held
    }
    if (change.position != change.previousPosition) aim.move(change.position)
    val now = event.buttons.held()
    (now - held).forEach { aim.press(it, true, change.position) }
    (held - now).forEach { aim.press(it, false, change.position) }
    return now
}

private suspend fun AwaitPointerEventScope.touch(down: PointerInputChange, aim: Aim) {
    val start = down.position
    val since = System.currentTimeMillis()
    var at = start
    var dragging = false
    var scrolling = false
    var carried = Offset.Zero
    aim.move(start)
    while (true) {
        val event = if (dragging || scrolling) {
            awaitPointerEvent()
        } else {
            withTimeoutOrNull((RIGHT_CLICK_HOLD_MS - (System.currentTimeMillis() - since)).coerceAtLeast(1)) { awaitPointerEvent() }
        }
        if (event == null) {
            aim.click(MouseButton.RIGHT, start)
            aim.haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            waitForUp()
            return
        }
        event.changes.forEach { it.consume() }
        val main = event.changes.firstOrNull { it.id == down.id }
        if (!dragging && event.changes.any { it.id != down.id && it.pressed }) scrolling = true
        if (main == null || !main.pressed) {
            if (dragging) {
                aim.press(MouseButton.LEFT, false, at)
            } else if (!scrolling) {
                aim.click(MouseButton.LEFT, start)
                aim.haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            }
            if (event.changes.any { it.pressed }) waitForUp()
            return
        }
        if (scrolling) {
            carried += main.position - main.previousPosition
            val notchesX = (carried.x / aim.scrollStep).toInt()
            val notchesY = (carried.y / aim.scrollStep).toInt()
            if (notchesX != 0 || notchesY != 0) {
                aim.send(RemoteAction.Scroll(-notchesX * WHEEL_NOTCH, notchesY * WHEEL_NOTCH))
                carried -= Offset(notchesX * aim.scrollStep, notchesY * aim.scrollStep)
            }
            continue
        }
        if (!dragging && (main.position - start).getDistance() > aim.slop) {
            dragging = true
            aim.press(MouseButton.LEFT, true, start)
            aim.haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
        if (dragging && main.position != at) {
            at = main.position
            aim.move(at)
        }
    }
}

private enum class Second { Waiting, Dragging, Scrolling, Done }

private suspend fun AwaitPointerEventScope.aimWithFinger(down: PointerInputChange, aim: Aim, onAim: (Offset?) -> Unit) {
    val pointer: PointerId = down.id
    var at = down.position
    aim.move(at)
    onAim(at)

    var second: PointerId? = null
    var secondState = Second.Done
    var secondStart = Offset.Zero
    var secondAt = Offset.Zero
    var secondSince = 0L
    var aimAtSecond = Offset.Zero
    var carried = Offset.Zero

    while (true) {
        val left = RIGHT_CLICK_HOLD_MS - (System.currentTimeMillis() - secondSince)
        val event = if (secondState == Second.Waiting) withTimeoutOrNull(left.coerceAtLeast(1)) { awaitPointerEvent() } else awaitPointerEvent()
        if (event == null) {
            aim.click(MouseButton.RIGHT, at)
            aim.haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            secondState = Second.Done
            continue
        }
        event.changes.forEach { it.consume() }

        val main = event.changes.firstOrNull { it.id == pointer }
        if (main == null || !main.pressed) {
            if (secondState == Second.Dragging) aim.press(MouseButton.LEFT, false, at)
            onAim(null)
            if (event.changes.any { it.pressed }) waitForUp()
            return
        }
        if (main.position != at) {
            at = main.position
            aim.move(at)
            onAim(at)
        }

        val other = event.changes.firstOrNull { it.id != pointer && (it.id == second || second == null) }
        when {
            other == null -> {}
            other.pressed && !other.previousPressed && second == null -> {
                second = other.id
                secondState = Second.Waiting
                secondStart = other.position
                secondAt = other.position
                secondSince = System.currentTimeMillis()
                aimAtSecond = at
                carried = Offset.Zero
            }
            !other.pressed -> {
                when (secondState) {
                    Second.Waiting -> {
                        aim.click(MouseButton.LEFT, at)
                        aim.haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    }
                    Second.Dragging -> aim.press(MouseButton.LEFT, false, at)
                    else -> {}
                }
                second = null
                secondState = Second.Done
            }
            else -> {
                secondAt = other.position
                if (secondState == Second.Waiting && (at - aimAtSecond).getDistance() > aim.slop) {
                    secondState = Second.Dragging
                    aim.press(MouseButton.LEFT, true, aimAtSecond)
                    aim.move(at)
                    aim.haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                } else if (secondState == Second.Waiting && (secondAt - secondStart).getDistance() > aim.slop) {
                    secondState = Second.Scrolling
                }
                if (secondState == Second.Scrolling) {
                    carried += other.position - other.previousPosition
                    val notchesX = (carried.x / aim.scrollStep).toInt()
                    val notchesY = (carried.y / aim.scrollStep).toInt()
                    // A finger pulled down shows what is above, which is a wheel turned up. Sideways is the same, mirrored.
                    if (notchesX != 0 || notchesY != 0) {
                        aim.send(RemoteAction.Scroll(-notchesX * WHEEL_NOTCH, notchesY * WHEEL_NOTCH))
                        carried -= Offset(notchesX * aim.scrollStep, notchesY * aim.scrollStep)
                    }
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.play(aim: Aim, speed: Float) {
    var looking: PointerId? = null
    var lookAt = Offset.Zero
    var lookStart = Offset.Zero
    var lookSince = 0L
    var carried = Offset.Zero
    var turned = false
    val pinch = LinkedHashMap<PointerId, Offset>()
    var pinchSpan = 0f
    var spread = 0f
    fun span() = pinch.values.toList().let { (a, b) -> (a - b).getDistance() }
    while (true) {
        val event = awaitPointerEvent()
        val now = System.currentTimeMillis()
        for (change in event.changes) {
            if (change.type == PointerType.Mouse) continue
            change.consume()
            val id = change.id
            val landed = change.pressed && !change.previousPressed
            when {
                landed && looking == null && pinch.isEmpty() -> {
                    looking = id
                    lookAt = change.position
                    lookStart = change.position
                    lookSince = now
                    carried = Offset.Zero
                    turned = false
                }
                // A second finger soon after the first, before it turned anything, makes a pinch, which a game takes as the wheel.
                landed && looking != null && !turned && now - lookSince <= PINCH_TOGETHER_MS -> {
                    pinch[looking] = lookAt
                    pinch[id] = change.position
                    looking = null
                    pinchSpan = span()
                    spread = 0f
                }
                landed -> {}
                id in pinch && change.pressed -> {
                    pinch[id] = change.position
                    spread += span() - pinchSpan
                    pinchSpan = span()
                    val notches = (spread / aim.scrollStep).toInt()
                    if (notches != 0) {
                        aim.send(RemoteAction.Scroll(0, notches * WHEEL_NOTCH))
                        spread -= notches * aim.scrollStep
                    }
                }
                id in pinch -> pinch.clear()
                id == looking && change.pressed -> {
                    lookAt = change.position
                    if (turned) {
                        carried += (change.position - change.previousPosition) * speed
                    } else if ((change.position - lookStart).getDistance() > aim.slop) {
                        turned = true
                        carried += (change.position - lookStart) * speed
                    }
                    val dx = carried.x.toInt()
                    val dy = carried.y.toInt()
                    if (dx != 0 || dy != 0) {
                        aim.send(RemoteAction.Motion(dx, dy))
                        carried -= Offset(dx.toFloat(), dy.toFloat())
                    }
                }
                id == looking -> looking = null
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.waitForUp() {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) return
    }
}
