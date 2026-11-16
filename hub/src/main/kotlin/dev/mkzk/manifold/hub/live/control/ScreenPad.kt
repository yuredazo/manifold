package dev.mkzk.manifold.hub.live.control

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt

internal enum class Stick { LEFT, RIGHT, DPAD }

internal val StickNames = mapOf(Stick.LEFT to "L stick", Stick.RIGHT to "R stick", Stick.DPAD to "D-pad")

// In the order the editor offers them.
internal val PadButtons = listOf(
    PAD_A to "A", PAD_B to "B", PAD_X to "X", PAD_Y to "Y",
    PAD_LEFT_SHOULDER to "LB", PAD_RIGHT_SHOULDER to "RB", PAD_LEFT_TRIGGER_KEY to "LT", PAD_RIGHT_TRIGGER_KEY to "RT",
    PAD_BACK to "Back", PAD_START to "Start", PAD_LEFT_THUMB to "L3", PAD_RIGHT_THUMB to "R3",
    PAD_UP to "Up", PAD_DOWN to "Down", PAD_LEFT to "Left", PAD_RIGHT to "Right",
)

internal val PAD_MASK = PadButtons.fold(0) { all, (bit, _) -> all or bit }

internal fun padNames(bits: Int) = PadButtons.filter { (bit, _) -> bits and bit != 0 }.map { it.second }

// Past this share of the radius a d-pad counts a direction; nearer the middle it is resting.
private const val DPAD_DEAD_ZONE = 0.35f
private const val KNOB_SHARE = 0.42f

internal class ScreenPad(private val pads: PadReader) {
    private val held = HashMap<Long, Int>()
    private val sticks = HashMap<Stick, Pair<Int, Int>>()
    private var released = false

    fun press(id: Long, bits: Int, down: Boolean) {
        if (down && bits != 0) held[id] = bits else held.remove(id)
        push()
    }

    fun tilt(stick: Stick, x: Int, y: Int) {
        sticks[stick] = x to y
        push()
    }

    // A stick letting go while the layout goes away must not bring the pad back.
    fun release() {
        released = true
        pads.forget(SCREEN_PAD)
    }

    private fun push() {
        if (released) return
        val (leftX, leftY) = sticks[Stick.LEFT] ?: (0 to 0)
        val (rightX, rightY) = sticks[Stick.RIGHT] ?: (0 to 0)
        pads.screen(held.values.fold(0) { all, bits -> all or bits }, leftX, leftY, rightX, rightY)
    }
}

// One of eight 45 degree slices, counted anticlockwise from the right, so a diagonal holds two directions.
internal fun dpadBits(tilt: Offset): Int {
    if (tilt.getDistance() < DPAD_DEAD_ZONE) return 0
    val slice = ((atan2(-tilt.y, tilt.x) / (PI / 4)).roundToInt() + 8) % 8
    return when (slice) {
        0 -> PAD_RIGHT
        1 -> PAD_RIGHT or PAD_UP
        2 -> PAD_UP
        3 -> PAD_UP or PAD_LEFT
        4 -> PAD_LEFT
        5 -> PAD_LEFT or PAD_DOWN
        6 -> PAD_DOWN
        else -> PAD_DOWN or PAD_RIGHT
    }
}

private fun axis(value: Float) = (value * 32767f).roundToInt().coerceIn(-32768, 32767)

// The centre is wherever the thumb lands, so a stick never starts tilted by a touch beside its middle.
@Composable
internal fun PlayStick(button: GameButton, opacity: Float, width: Dp, height: Dp, pad: ScreenPad) {
    val stick = button.stick ?: return
    val haptics = LocalHapticFeedback.current
    var tilt by remember { mutableStateOf(Offset.Zero) }
    var held by remember { mutableStateOf(false) }
    var directions by remember { mutableIntStateOf(0) }
    fun steer(to: Offset) {
        tilt = to
        if (stick == Stick.DPAD) {
            val bits = dpadBits(to)
            if (bits != directions && bits != 0) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            directions = bits
            pad.press(button.id, bits, bits != 0)
        } else {
            pad.tilt(stick, axis(to.x), axis(-to.y))
        }
    }
    DisposableEffect(button) { onDispose { steer(Offset.Zero) } }
    StickFace(
        button,
        tilt = tilt,
        active = held,
        directions = directions,
        modifier = Modifier
            .placed(button, width, height)
            .graphicsLayer { alpha = if (held) 1f else opacity }
            .pointerInput(button) {
                val radius = size.width / 2f
                awaitEachGesture {
                    val first = awaitFirstDown()
                    first.consume()
                    held = true
                    val origin = first.position
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == first.id }
                        if (change == null || !change.pressed) break
                        val pull = (change.position - origin) / radius
                        val length = pull.getDistance()
                        steer(if (length > 1f) pull / length else pull)
                    }
                    held = false
                    steer(Offset.Zero)
                }
            },
    )
}

@Composable
internal fun StickFace(button: GameButton, tilt: Offset, active: Boolean, directions: Int, modifier: Modifier, border: BorderStroke? = null) {
    val scheme = MaterialTheme.colorScheme
    val size = button.diameter.dp
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.25f))
            .border(border ?: BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (button.stick == Stick.DPAD) {
            val arrow = size * 0.3f
            fun tint(bit: Int) = if (directions and bit != 0) scheme.primary else Color.White.copy(alpha = 0.7f)
            Icon(Icons.Rounded.KeyboardArrowUp, null, tint = tint(PAD_UP), modifier = Modifier.align(Alignment.TopCenter).size(arrow))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = tint(PAD_DOWN), modifier = Modifier.align(Alignment.BottomCenter).size(arrow))
            Icon(Icons.Rounded.KeyboardArrowUp, null, tint = tint(PAD_LEFT), modifier = Modifier.align(Alignment.CenterStart).size(arrow).rotate(-90f))
            Icon(Icons.Rounded.KeyboardArrowUp, null, tint = tint(PAD_RIGHT), modifier = Modifier.align(Alignment.CenterEnd).size(arrow).rotate(90f))
            Box(Modifier.size(size * 0.12f).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
        } else {
            val knob = size * KNOB_SHARE
            val travel = (size - knob) / 2
            Box(Modifier.size(size * 0.66f).border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape))
            Box(
                Modifier
                    .offset { IntOffset((tilt.x * travel.toPx()).roundToInt(), (tilt.y * travel.toPx()).roundToInt()) }
                    .size(knob)
                    .clip(CircleShape)
                    .background(if (active) scheme.primary.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.3f))
                    .border(1.dp, Color.White.copy(alpha = if (active) 0f else 0.35f), CircleShape),
            )
        }
    }
}

// Layouts store positions as shares of the screen, so these dp distances are turned into shares for the phone the layout
// is made on, held sideways. That keeps the face buttons a tight diamond on any screen. The right side leaves room for the
// control rail.
internal fun controllerButtons(newId: () -> Long, width: Float, height: Float): List<GameButton> {
    val left = 28f
    val right = width - 96f
    val top = 40f
    val bottom = height - 16f
    val faceX = right - 120f
    val faceY = bottom - 130f
    fun at(x: Float, y: Float, diameter: Float, bits: Int = 0, stick: Stick? = null) = GameButton(
        newId(),
        (x / width).coerceIn(0f, 1f),
        (y / height).coerceIn(0f, 1f),
        diameter,
        pad = bits,
        stick = stick,
        showLabel = stick == null,
    )
    return listOf(
        at(left + 100f, bottom - 110f, 140f, stick = Stick.LEFT),
        at(left + 250f, bottom - 70f, 104f, stick = Stick.DPAD),
        at(right - 290f, bottom - 70f, 120f, stick = Stick.RIGHT),
        at(faceX, faceY + 56f, 52f, PAD_A),
        at(faceX + 56f, faceY, 52f, PAD_B),
        at(faceX - 56f, faceY, 52f, PAD_X),
        at(faceX, faceY - 56f, 52f, PAD_Y),
        at(left + 70f, top, 48f, PAD_LEFT_TRIGGER_KEY),
        at(left + 150f, top, 48f, PAD_LEFT_SHOULDER),
        at(right - 70f, top, 48f, PAD_RIGHT_TRIGGER_KEY),
        at(right - 150f, top, 48f, PAD_RIGHT_SHOULDER),
        at(width / 2 - 40f, top, 44f, PAD_BACK),
        at(width / 2 + 40f, top, 44f, PAD_START),
    )
}
