package dev.mkzk.manifold.hub.live.control

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.network.RemoteAction
import kotlin.math.atan2

private const val W = 0x1A
private const val A = 0x04
private const val S = 0x16
private const val D = 0x07
// Near where the thumb landed it is resting, not pointing anywhere.
private const val REST = 0.25f
private const val ARC_DEGREES = 70f
private val PadRadius = 74.dp
private val RingWidth = 1.5.dp
private val ArcWidth = 4.dp
private val ThumbRadius = 14.dp

// One of eight 45 degree slices, counted anticlockwise from the right, so a diagonal holds two keys.
private fun sliceAt(from: Offset, radius: Float): Int? {
    if (from.getDistance() < radius * REST) return null
    val degrees = Math.toDegrees(atan2(-from.y, from.x).toDouble())
    return ((degrees + 360 + 22.5) % 360 / 45).toInt()
}

private fun keysOf(slice: Int?): Set<Int> = when (slice) {
    null -> emptySet()
    0 -> setOf(D)
    1 -> setOf(W, D)
    2 -> setOf(W)
    3 -> setOf(W, A)
    4 -> setOf(A)
    5 -> setOf(S, A)
    6 -> setOf(S)
    else -> setOf(S, D)
}

@Composable
internal fun DirectionPad(send: (RemoteAction) -> Unit, modifier: Modifier = Modifier) {
    val sender by rememberUpdatedState(send)
    var slice by remember { mutableStateOf<Int?>(null) }
    var origin by remember { mutableStateOf<Offset?>(null) }
    var thumb by remember { mutableStateOf(Offset.Zero) }
    val radius = with(LocalDensity.current) { PadRadius.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    fun point(to: Int?) {
        if (to == slice) return
        val before = keysOf(slice)
        val after = keysOf(to)
        (before - after).forEach { sender(RemoteAction.Key(it, false)) }
        (after - before).forEach { sender(RemoteAction.Key(it, true)) }
        slice = to
    }
    DisposableEffect(Unit) { onDispose { keysOf(slice).forEach { sender(RemoteAction.Key(it, false)) } } }

    Box(
        modifier = modifier.pointerInput(radius) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                origin = down.position
                thumb = down.position
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    thumb = change.position
                    point(sliceAt(change.position - down.position, radius))
                }
                point(null)
                origin = null
            }
        },
    ) {
        val center = origin ?: return@Box
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.Black.copy(alpha = 0.12f), radius, center)
            drawCircle(Color.White.copy(alpha = 0.18f), radius, center, style = Stroke(RingWidth.toPx()))
            slice?.let {
                // Canvas angles run clockwise from the right, the slices anticlockwise.
                val inset = ArcWidth.toPx() / 2
                drawArc(
                    color = accent.copy(alpha = 0.85f),
                    startAngle = -it * 45f - ARC_DEGREES / 2,
                    sweepAngle = ARC_DEGREES,
                    useCenter = false,
                    topLeft = center - Offset(radius - inset, radius - inset),
                    size = Size((radius - inset) * 2, (radius - inset) * 2),
                    style = Stroke(ArcWidth.toPx(), cap = StrokeCap.Round),
                )
            }
            val reach = thumb - center
            val clamped = if (reach.getDistance() > radius) reach * (radius / reach.getDistance()) else reach
            drawCircle(Color.White.copy(alpha = 0.3f), ThumbRadius.toPx(), center + clamped)
        }
    }
}
