package dev.mkzk.manifold.hub.live.control

import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mkzk.manifold.hub.network.RemoteAction
import dev.mkzk.manifold.hub.network.protocol.MouseButton
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "GameButtons"
private const val PREFS = "game_buttons"
private const val LAYOUTS_KEY = "layouts"
private const val ACTIVE_KEY = "active"
private const val DEFAULT_DIAMETER = 60f
internal const val MIN_DIAMETER = 24f
internal const val MAX_DIAMETER = 160f
private const val TEMPLATE_KEY = "controller_added"
internal const val MIN_OPACITY = 0.1f

// x and y place the button's centre as shares of the screen, so a layout fits either way the phone is turned. diameter is in dp.
// A null opacity follows the layout's. pad holds controller button bits; with a stick set the button is that stick instead.
internal data class GameButton(
    val id: Long,
    val x: Float,
    val y: Float,
    val diameter: Float = DEFAULT_DIAMETER,
    val name: String = "",
    val keys: List<Int> = emptyList(),
    val mouse: MouseButton? = null,
    val showLabel: Boolean = false,
    val opacity: Float? = null,
    val pad: Int = 0,
    val stick: Stick? = null,
) {
    fun label(): String = name.ifBlank {
        stick?.let { StickNames.getValue(it) }
            ?: (keys.map(::keyName) + padNames(pad) + listOfNotNull(mouse?.let { MouseNames[it] })).joinToString("+")
    }
}

private val MouseNames = mapOf(MouseButton.LEFT to "Left", MouseButton.MIDDLE to "Middle", MouseButton.RIGHT to "Right")

// id only tells layouts apart while the app runs, so a moved one keeps its place in the list; it is not saved.
internal data class ButtonLayout(val name: String, val buttons: List<GameButton> = emptyList(), val id: Long = 0, val opacity: Float = 1f)

internal class ButtonLayouts(context: Context, defaultName: String, controllerName: String) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var nextId = 1L
    var layouts by mutableStateOf(listOf(ButtonLayout(defaultName, id = 0)))
        private set
    var active by mutableIntStateOf(0)
        private set

    val current: ButtonLayout get() = layouts[active.coerceIn(0, layouts.lastIndex)]

    init {
        load()
        if (!prefs.getBoolean(TEMPLATE_KEY, false)) {
            val screen = context.resources.displayMetrics
            val width = maxOf(screen.widthPixels, screen.heightPixels) / screen.density
            val height = minOf(screen.widthPixels, screen.heightPixels) / screen.density
            layouts = layouts + ButtonLayout(controllerName, controllerButtons(::newId, width, height), newId())
            prefs.edit().putBoolean(TEMPLATE_KEY, true).apply()
            save()
        }
    }

    fun newId() = nextId++

    fun select(index: Int) {
        active = index.coerceIn(0, layouts.lastIndex)
        save()
    }

    fun add(name: String, buttons: List<GameButton> = emptyList()) {
        layouts = layouts + ButtonLayout(name, buttons.map { it.copy(id = newId()) }, newId())
        select(layouts.lastIndex)
    }

    fun rename(name: String) = change { it.copy(name = name) }

    fun move(from: Int, to: Int) {
        if (from !in layouts.indices || to !in layouts.indices || from == to) return
        val current = layouts[active].id
        layouts = layouts.toMutableList().apply { add(to, removeAt(from)) }
        active = layouts.indexOfFirst { it.id == current }.coerceAtLeast(0)
        save()
    }

    fun remove() {
        if (layouts.size < 2) return
        layouts = layouts.filterIndexed { index, _ -> index != active }
        select(active - 1)
    }

    fun change(save: Boolean = true, edit: (ButtonLayout) -> ButtonLayout) {
        layouts = layouts.mapIndexed { index, layout -> if (index == active) edit(layout) else layout }
        if (save) save()
    }

    fun changeButton(id: Long, save: Boolean = true, edit: (GameButton) -> GameButton) =
        change(save) { layout -> layout.copy(buttons = layout.buttons.map { if (it.id == id) edit(it) else it }) }

    fun save() {
        val all = JSONArray()
        layouts.forEach { layout ->
            val buttons = JSONArray()
            layout.buttons.forEach { button ->
                buttons.put(
                    JSONObject()
                        .put("x", button.x.toDouble())
                        .put("y", button.y.toDouble())
                        .put("diameter", button.diameter.toDouble())
                        .put("name", button.name)
                        .put("keys", JSONArray(button.keys))
                        .put("mouse", button.mouse?.name ?: "")
                        .put("label", button.showLabel)
                        .apply {
                            button.opacity?.let { put("opacity", it.toDouble()) }
                            if (button.pad != 0) put("pad", button.pad)
                            button.stick?.let { put("stick", it.name) }
                        },
                )
            }
            all.put(JSONObject().put("name", layout.name).put("opacity", layout.opacity.toDouble()).put("buttons", buttons))
        }
        prefs.edit().putString(LAYOUTS_KEY, all.toString()).putInt(ACTIVE_KEY, active).apply()
    }

    private fun load() {
        val text = prefs.getString(LAYOUTS_KEY, null) ?: return
        try {
            val all = JSONArray(text)
            val loaded = (0 until all.length()).map { index ->
                val layout = all.getJSONObject(index)
                val buttons = layout.getJSONArray("buttons")
                ButtonLayout(
                    id = newId(),
                    name = layout.getString("name"),
                    opacity = layout.optDouble("opacity", 1.0).toFloat().coerceIn(MIN_OPACITY, 1f),
                    buttons =
                    (0 until buttons.length()).map { at ->
                        val button = buttons.getJSONObject(at)
                        val keys = button.getJSONArray("keys")
                        GameButton(
                            id = newId(),
                            x = button.getDouble("x").toFloat().coerceIn(0f, 1f),
                            y = button.getDouble("y").toFloat().coerceIn(0f, 1f),
                            diameter = button.optDouble("diameter", DEFAULT_DIAMETER.toDouble()).toFloat().coerceIn(MIN_DIAMETER, MAX_DIAMETER),
                            name = button.optString("name"),
                            keys = (0 until keys.length()).map(keys::getInt),
                            mouse = MouseButton.entries.firstOrNull { it.name == button.optString("mouse") },
                            showLabel = button.optBoolean("label", false),
                            opacity = if (button.has("opacity")) button.getDouble("opacity").toFloat().coerceIn(MIN_OPACITY, 1f) else null,
                            pad = button.optInt("pad", 0) and PAD_MASK,
                            stick = Stick.entries.firstOrNull { it.name == button.optString("stick") },
                        )
                    },
                )
            }
            if (loaded.isNotEmpty()) {
                layouts = loaded
                active = prefs.getInt(ACTIVE_KEY, 0).coerceIn(0, loaded.lastIndex)
            }
        } catch (e: JSONException) {
            Log.w(TAG, "saved layouts unreadable, starting fresh", e)
        }
    }
}

internal fun Modifier.placed(button: GameButton, width: Dp, height: Dp) = offset {
    IntOffset(
        ((width * button.x).toPx() - button.diameter.dp.toPx() / 2).roundToInt(),
        ((height * button.y).toPx() - button.diameter.dp.toPx() / 2).roundToInt(),
    )
}

@Composable
internal fun ButtonFace(button: GameButton, modifier: Modifier, fill: Color, border: BorderStroke, text: Color?) {
    if (button.stick != null) return StickFace(button, Offset.Zero, active = false, directions = 0, modifier = modifier, border = border)
    val label = button.label()
    Box(
        modifier = modifier
            .size(button.diameter.dp)
            .clip(CircleShape)
            .background(fill)
            .border(border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (text == null) return@Box
        val size = (button.diameter * if (label.length > 5) 0.16f else 0.3f).coerceIn(7f, 22f).sp
        Text(
            label.ifEmpty { "?" },
            color = text,
            fontSize = size,
            lineHeight = size,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Without the font's own padding and with the line trimmed to the glyphs, a short label sits in the true centre.
            style = TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            modifier = Modifier.padding((button.diameter * 0.12f).dp),
        )
    }
}

@Composable
internal fun GameButtons(layout: ButtonLayout, pads: PadReader, speed: Float, send: (RemoteAction) -> Unit, modifier: Modifier = Modifier) {
    val screen = remember(pads) { ScreenPad(pads) }
    DisposableEffect(screen) { onDispose { screen.release() } }
    BoxWithConstraints(modifier.fillMaxSize()) {
        layout.buttons.forEach { button ->
            key(button.id) {
                val opacity = button.opacity ?: layout.opacity
                if (button.stick != null) {
                    PlayStick(button, opacity, maxWidth, maxHeight, screen)
                } else {
                    PlayButton(button, opacity, maxWidth, maxHeight, speed, send, screen)
                }
            }
        }
    }
}

@Composable
private fun PlayButton(button: GameButton, opacity: Float, width: Dp, height: Dp, speed: Float, send: (RemoteAction) -> Unit, pad: ScreenPad) {
    val sender by rememberUpdatedState(send)
    val haptics = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    fun press(on: Boolean) {
        if (down == on) return
        down = on
        if (button.pad != 0) {
            if (on) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            pad.press(button.id, button.pad, on)
        }
        if (on) {
            button.keys.forEach { sender(RemoteAction.Key(it, true)) }
            button.mouse?.let { sender(RemoteAction.Press(it, true)) }
        } else {
            button.mouse?.let { sender(RemoteAction.Press(it, false)) }
            button.keys.asReversed().forEach { sender(RemoteAction.Key(it, false)) }
        }
    }
    DisposableEffect(button) { onDispose { press(false) } }
    ButtonFace(
        button,
        Modifier
            .placed(button, width, height)
            .graphicsLayer { alpha = if (down) 1f else opacity }
            .pointerInput(button, speed) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    first.consume()
                    press(true)
                    var aiming = false
                    var carried = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == first.id }
                        if (change == null || !change.pressed) break
                        if (aiming) {
                            carried += (change.position - change.previousPosition) * speed
                        } else if ((change.position - first.position).getDistance() > viewConfiguration.touchSlop) {
                            aiming = true
                            carried += (change.position - first.position) * speed
                        }
                        val dx = carried.x.toInt()
                        val dy = carried.y.toInt()
                        if (dx != 0 || dy != 0) {
                            sender(RemoteAction.Motion(dx, dy))
                            carried -= Offset(dx.toFloat(), dy.toFloat())
                        }
                    }
                    press(false)
                }
            },
        fill = if (down) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, if (down) Color.Transparent else Color.White.copy(alpha = 0.22f)),
        text = when {
            !button.showLabel -> null
            down -> MaterialTheme.colorScheme.onPrimary
            else -> Color.White.copy(alpha = 0.85f)
        },
    )
}
