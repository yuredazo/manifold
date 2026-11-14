package dev.mkzk.manifold.hub.broker.ui

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.network.RemoteAction
import dev.mkzk.manifold.hub.network.protocol.MouseButton
import dev.mkzk.manifold.hub.ui.ButtonKind
import dev.mkzk.manifold.hub.ui.ConfirmModal
import dev.mkzk.manifold.hub.ui.ModalButton
import dev.mkzk.manifold.hub.ui.ModalField
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetList
import dev.mkzk.manifold.hub.ui.SheetListDivider
import dev.mkzk.manifold.hub.ui.SheetListRow
import dev.mkzk.manifold.hub.ui.SheetTitle
import dev.mkzk.manifold.hub.ui.SquareIcon
import dev.mkzk.manifold.hub.ui.secondaryText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

private const val TAG = "GameButtons"
private const val PREFS = "game_buttons"
private const val LAYOUTS_KEY = "layouts"
private const val ACTIVE_KEY = "active"
private const val DEFAULT_DIAMETER = 60f
private const val MIN_DIAMETER = 24f
private const val MAX_DIAMETER = 120f
private val PanelWidth = 296.dp
private val ControlHeight = 40.dp
private val FieldHeight = 48.dp
private val PanelShape = RoundedCornerShape(28.dp)
private val GroupShape = RoundedCornerShape(20.dp)
private val KeyShape = RoundedCornerShape(10.dp)
private val MouseWidth = 64.dp
private val MouseHeight = 92.dp
private val MouseShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 30.dp, bottomEnd = 30.dp)

// x and y place the button's centre as shares of the screen, so a layout fits either way the phone is turned. diameter is in dp.
internal data class GameButton(
    val id: Long,
    val x: Float,
    val y: Float,
    val diameter: Float = DEFAULT_DIAMETER,
    val name: String = "",
    val keys: List<Int> = emptyList(),
    val mouse: MouseButton? = null,
    val showLabel: Boolean = true,
) {
    fun label(): String = name.ifBlank {
        (keys.map(::keyName) + listOfNotNull(mouse?.let { MouseNames[it] })).joinToString("+")
    }
}

private val MouseNames = mapOf(MouseButton.LEFT to "Left", MouseButton.MIDDLE to "Middle", MouseButton.RIGHT to "Right")

internal data class ButtonLayout(val name: String, val buttons: List<GameButton> = emptyList())

internal class ButtonLayouts(context: Context, defaultName: String) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var nextId = 1L
    var layouts by mutableStateOf(listOf(ButtonLayout(defaultName)))
        private set
    var active by mutableIntStateOf(0)
        private set

    val current: ButtonLayout get() = layouts[active.coerceIn(0, layouts.lastIndex)]

    init {
        load()
    }

    fun newId() = nextId++

    fun select(index: Int) {
        active = index.coerceIn(0, layouts.lastIndex)
        save()
    }

    fun add(name: String, buttons: List<GameButton> = emptyList()) {
        layouts = layouts + ButtonLayout(name, buttons.map { it.copy(id = newId()) })
        select(layouts.lastIndex)
    }

    fun rename(name: String) = change { it.copy(name = name) }

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
                        .put("label", button.showLabel),
                )
            }
            all.put(JSONObject().put("name", layout.name).put("buttons", buttons))
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
                    layout.getString("name"),
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
                            showLabel = button.optBoolean("label", true),
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

private fun Modifier.placed(button: GameButton, width: Dp, height: Dp) = offset {
    IntOffset(
        ((width * button.x).toPx() - button.diameter.dp.toPx() / 2).roundToInt(),
        ((height * button.y).toPx() - button.diameter.dp.toPx() / 2).roundToInt(),
    )
}

@Composable
private fun ButtonFace(button: GameButton, modifier: Modifier, fill: Color, border: BorderStroke, text: Color?) {
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
internal fun GameButtons(layout: ButtonLayout, speed: Float, send: (RemoteAction) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        layout.buttons.forEach { button -> key(button.id) { PlayButton(button, maxWidth, maxHeight, speed, send) } }
    }
}

@Composable
private fun PlayButton(button: GameButton, width: Dp, height: Dp, speed: Float, send: (RemoteAction) -> Unit) {
    val sender by rememberUpdatedState(send)
    var down by remember { mutableStateOf(false) }
    fun press(on: Boolean) {
        if (down == on) return
        down = on
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

// Keys most games bind, offered first so a button rarely needs the full keyboard.
private val CommonKeys = listOf(
    0x29, 0x2B, 0x14, 0x08, 0x15, 0x09, 0x17, 0x1D, 0x1B, 0x06, 0x19, 0x0B,
    0x1E, 0x1F, 0x20, 0x21, 0x22, 0xE1, 0xE0, 0xE2, 0x2C, 0x28,
)

private fun List<Int>.toggled(usage: Int) = if (usage in this) this - usage else this + usage

private enum class Naming { NewLayout, RenameLayout, DuplicateLayout, RenameButton }

@Composable
internal fun ButtonEditor(store: ButtonLayouts, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<Long?>(null) }
    var allKeys by remember { mutableStateOf(false) }
    var layoutsOpen by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Naming?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val layout = store.current
    val chosen = layout.buttons.firstOrNull { it.id == selected }
    if (chosen == null) allKeys = false
    val scheme = MaterialTheme.colorScheme

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Two fingers anywhere resize the selected button; the drag under the first finger pauses meanwhile.
                awaitPointerEventScope {
                    var span = 0f
                    var resized = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.filter { it.pressed }
                        val id = selected
                        if (fingers.size >= 2 && id != null) {
                            moving = true
                            val now = (fingers[0].position - fingers[1].position).getDistance()
                            if (span > 0f && now > 0f) {
                                val ratio = now / span
                                store.changeButton(id, save = false) { it.copy(diameter = (it.diameter * ratio).coerceIn(MIN_DIAMETER, MAX_DIAMETER)) }
                                resized = true
                            }
                            span = now
                            event.changes.forEach { it.consume() }
                        } else {
                            span = 0f
                            if (fingers.isEmpty()) moving = false
                            if (resized && fingers.isEmpty()) {
                                store.save()
                                resized = false
                            }
                        }
                    }
                }
            },
    ) {
        val width = maxWidth
        val height = maxHeight
        val wide = maxWidth > maxHeight
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .pointerInput(width, height) {
                    // Like a touchpad: a drag on empty space moves the chosen button, so the finger never covers it.
                    awaitEachGesture {
                        val first = awaitFirstDown()
                        var dragged = false
                        var pinched = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                            if (!change.pressed) break
                            if (change.isConsumed) {
                                pinched = true
                                continue
                            }
                            if (!dragged && (change.position - first.position).getDistance() <= viewConfiguration.touchSlop) continue
                            val id = selected ?: continue
                            val step = if (dragged) change.position - change.previousPosition else change.position - first.position
                            dragged = true
                            moving = true
                            change.consume()
                            store.changeButton(id, save = false) {
                                it.copy(
                                    x = (it.x + step.x / width.toPx()).coerceIn(0f, 1f),
                                    y = (it.y + step.y / height.toPx()).coerceIn(0f, 1f),
                                )
                            }
                        }
                        moving = false
                        if (dragged) store.save() else if (!pinched) selected = null
                    }
                },
        )
        if (layout.buttons.isEmpty()) {
            Text(
                stringResource(R.string.buttons_empty),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        layout.buttons.forEach { button ->
            key(button.id) {
                val on = button.id == selected
                ButtonFace(
                    button,
                    Modifier
                        .placed(button, width, height)
                        .pointerInput(button.id, width, height) {
                            awaitEachGesture {
                                val first = awaitFirstDown()
                                first.consume()
                                selected = button.id
                                var moved = false
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                                    if (!change.pressed) break
                                    if (change.isConsumed) continue
                                    change.consume()
                                    val step = change.position - change.previousPosition
                                    if (step.x == 0f && step.y == 0f) continue
                                    moved = true
                                    moving = true
                                    store.changeButton(button.id, save = false) {
                                        it.copy(
                                            x = (it.x + step.x / width.toPx()).coerceIn(0f, 1f),
                                            y = (it.y + step.y / height.toPx()).coerceIn(0f, 1f),
                                        )
                                    }
                                }
                                moving = false
                                if (moved) store.save()
                            }
                        },
                    fill = if (on) scheme.primary.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.35f),
                    border = if (on) BorderStroke(2.dp, scheme.primary) else BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                    text = Color.White.copy(alpha = if (button.showLabel) 1f else 0.4f),
                )
            }
        }

        if (moving) return@BoxWithConstraints
        naming?.let { mode ->
            val forButton = mode == Naming.RenameButton
            NameBar(
                icon = when (mode) {
                    Naming.NewLayout -> Icons.Outlined.Add
                    Naming.DuplicateLayout -> Icons.Outlined.ContentCopy
                    else -> Icons.Outlined.Edit
                },
                initial = when (mode) {
                    Naming.NewLayout -> stringResource(R.string.layout_default, store.layouts.size + 1)
                    Naming.RenameLayout -> layout.name
                    Naming.DuplicateLayout -> stringResource(R.string.layout_copy, layout.name)
                    Naming.RenameButton -> chosen?.name.orEmpty()
                },
                placeholder = if (forButton) chosen?.copy(name = "")?.label().orEmpty() else stringResource(R.string.layout_name_hint),
                action = stringResource(if (mode == Naming.NewLayout || mode == Naming.DuplicateLayout) R.string.layout_create else R.string.layout_save),
                limit = if (forButton) 12 else 24,
                allowEmpty = forButton,
                onCancel = { naming = null },
                modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(12.dp).widthIn(max = 560.dp).fillMaxWidth(),
            ) { name ->
                when (mode) {
                    Naming.NewLayout -> store.add(name)
                    Naming.RenameLayout -> store.rename(name)
                    Naming.DuplicateLayout -> store.add(name, layout.buttons)
                    Naming.RenameButton -> chosen?.let { button -> store.changeButton(button.id) { it.copy(name = name) } }
                }
                if (!forButton) selected = null
                naming = null
            }
            return@BoxWithConstraints
        }
        val panelAtStart = wide && chosen != null && chosen.x > 0.5f
        val panelAtTop = !wide && chosen != null && chosen.y > 0.5f
        fun under(top: Boolean) = layout.buttons.count { it.x in 0.2f..0.8f && if (top) it.y < 0.2f else it.y > 0.8f }
        val toolbarAtTop = when {
            !wide && chosen != null && !allKeys -> !panelAtTop
            allKeys -> true
            else -> under(top = true) <= under(top = false)
        }
        val beside = if (chosen != null && !allKeys && wide) PanelWidth + 24.dp else 0.dp
        Toolbar(
            layoutName = layout.name,
            onLayouts = { layoutsOpen = true },
            onAdd = {
                val id = store.newId()
                store.change { it.copy(buttons = it.buttons + GameButton(id, 0.5f, 0.5f)) }
                selected = id
            },
            onDone = onDone,
            modifier = Modifier
                .align(if (toolbarAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(vertical = 12.dp)
                .padding(start = if (panelAtStart) beside else 0.dp, end = if (panelAtStart) 0.dp else beside),
        )

        if (chosen != null && allKeys) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Floating(shape = CircleShape) {
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            chosen.keys.joinToString(" + ", transform = ::keyName).ifEmpty { stringResource(R.string.buttons_keys_none) },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp).weight(1f, fill = false),
                        )
                        Pill(stringResource(R.string.buttons_done), Icons.Outlined.Check, strong = true) { allKeys = false }
                    }
                }
                Spacer(Modifier.height(8.dp))
                RemoteKeyboard(send = { action ->
                    if (action is RemoteAction.Key && action.down) store.changeButton(chosen.id) { it.copy(keys = it.keys.toggled(action.usage)) }
                })
            }
        } else if (chosen != null) {
            Floating(
                shape = PanelShape,
                modifier = if (wide) {
                    Modifier
                        .align(if (panelAtStart) Alignment.CenterStart else Alignment.CenterEnd)
                        .safeDrawingPadding()
                        .padding(12.dp)
                        .width(PanelWidth)
                        .heightIn(max = maxHeight - 24.dp)
                } else {
                    Modifier
                        .align(if (panelAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(12.dp)
                        .fillMaxWidth()
                        .heightIn(max = maxHeight * 0.5f)
                },
            ) {
                ButtonPanel(
                    button = chosen,
                    onKey = { usage -> store.changeButton(chosen.id) { it.copy(keys = it.keys.toggled(usage)) } },
                    onMouse = { mouse -> store.changeButton(chosen.id) { it.copy(mouse = mouse) } },
                    onShowLabel = { show -> store.changeButton(chosen.id) { it.copy(showLabel = show) } },
                    onAllKeys = { allKeys = true },
                    onRename = { naming = Naming.RenameButton },
                    onDelete = {
                        store.change { it.copy(buttons = it.buttons.filter { other -> other.id != chosen.id }) }
                        selected = null
                    },
                    onClose = { selected = null },
                )
            }
        }
    }

    if (layoutsOpen) {
        LayoutsSheet(
            store = store,
            onDismiss = { layoutsOpen = false },
            onPick = { index ->
                store.select(index)
                selected = null
                layoutsOpen = false
            },
            onAction = { action ->
                layoutsOpen = false
                if (action == null) deleting = true else naming = action
            },
        )
    }
    if (deleting) {
        ConfirmModal(
            title = stringResource(R.string.layout_delete_title, layout.name),
            message = stringResource(R.string.layout_delete_text),
            action = stringResource(R.string.layout_delete),
            cancel = stringResource(R.string.common_cancel),
            onDismiss = { deleting = false },
        ) {
            store.remove()
            selected = null
            deleting = false
        }
    }
}

// Naming happens at the top of the screen, so the keyboard below never hides the text, even with the phone turned sideways.
@Composable
private fun NameBar(
    icon: ImageVector,
    initial: String,
    placeholder: String,
    action: String,
    limit: Int,
    allowEmpty: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        withFrameNanos {}
        focus.requestFocus()
        keyboard?.show()
    }
    BackHandler(onBack = onCancel)
    val ready = allowEmpty || text.isNotBlank()
    fun finish() {
        if (ready) onDone(text.trim())
    }
    Floating(shape = CircleShape, modifier = modifier) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundIcon(Icons.Outlined.Close, stringResource(R.string.common_cancel), size = FieldHeight, onClick = onCancel)
            ModalField(
                text,
                { text = it.take(limit) },
                placeholder,
                Modifier.weight(1f).focusRequester(focus),
                leading = icon,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finish() }),
            )
            ModalButton(action, ButtonKind.Main, Modifier, enabled = ready) { finish() }
        }
    }
}

@Composable
private fun Floating(shape: androidx.compose.ui.graphics.Shape, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier, shape = shape, color = scheme.surfaceContainer, border = BorderStroke(1.dp, scheme.outlineVariant), shadowElevation = 6.dp, content = content)
}

@Composable
private fun Toolbar(layoutName: String, onLayouts: () -> Unit, onAdd: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Floating(shape = CircleShape, modifier = modifier) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill(layoutName, Icons.Outlined.ExpandMore, iconAfter = true, onClick = onLayouts)
            Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Pill(stringResource(R.string.buttons_add), Icons.Outlined.Add, onClick = onAdd)
            Pill(stringResource(R.string.buttons_done), Icons.Outlined.Check, strong = true, onClick = onDone)
        }
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector, iconAfter: Boolean = false, strong: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (strong) scheme.onPrimary else scheme.onSurface
    Row(
        Modifier
            .height(ControlHeight)
            .clip(CircleShape)
            .then(if (strong) Modifier.background(scheme.primary) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (iconAfter) 16.dp else 12.dp, end = if (iconAfter) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!iconAfter) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Text(label, color = ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (iconAfter) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, size: Dp = ControlHeight, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonPanel(
    button: GameButton,
    onKey: (Int) -> Unit,
    onMouse: (MouseButton?) -> Unit,
    onShowLabel: (Boolean) -> Unit,
    onAllKeys: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                button.label().ifEmpty { stringResource(R.string.buttons_untitled) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            RoundIcon(Icons.Outlined.Edit, stringResource(R.string.buttons_rename), onClick = onRename)
            RoundIcon(Icons.Outlined.DeleteOutline, stringResource(R.string.buttons_delete), tint = scheme.error, onClick = onDelete)
            RoundIcon(Icons.Outlined.Close, stringResource(R.string.buttons_close), onClick = onClose)
        }

        Group(stringResource(R.string.buttons_keys)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (CommonKeys + button.keys.filter { it !in CommonKeys }).forEach { usage ->
                    KeyCap(keyName(usage), usage in button.keys) { onKey(usage) }
                }
                KeyCap(stringResource(R.string.buttons_more_keys), on = false, icon = Icons.Outlined.Keyboard, onClick = onAllKeys)
            }
        }

        Group(stringResource(R.string.buttons_mouse)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MousePicker(button.mouse) { part -> onMouse(if (part == button.mouse) null else part) }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            when (button.mouse) {
                                MouseButton.LEFT -> R.string.buttons_mouse_left
                                MouseButton.MIDDLE -> R.string.buttons_mouse_middle
                                MouseButton.RIGHT -> R.string.buttons_mouse_right
                                else -> R.string.buttons_mouse_none
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (button.mouse != null) scheme.primary else scheme.onSurface,
                    )
                    Text(stringResource(R.string.buttons_mouse_hint), style = MaterialTheme.typography.bodySmall, color = secondaryText())
                }
            }
        }

        Surface(Modifier.fillMaxWidth(), shape = GroupShape, color = scheme.surfaceContainerHigh) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = button.showLabel, role = Role.Switch, onValueChange = onShowLabel)
                    .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.buttons_show_label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(
                    checked = button.showLabel,
                    onCheckedChange = null,
                    modifier = Modifier.graphicsLayer {
                        scaleX = 0.8f
                        scaleY = 0.8f
                        transformOrigin = TransformOrigin(1f, 0.5f)
                    },
                )
            }
        }

        Text(
            stringResource(R.string.buttons_hint),
            style = MaterialTheme.typography.bodySmall,
            color = secondaryText(),
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun MousePicker(chosen: MouseButton?, onPick: (MouseButton) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.outline.copy(alpha = 0.6f)
    fun fill(part: MouseButton) = if (chosen == part) scheme.primary else Color.Transparent
    Box(
        Modifier
            .size(width = MouseWidth, height = MouseHeight)
            .clip(MouseShape)
            .background(scheme.surfaceContainerHighest)
            .border(1.5.dp, line, MouseShape),
    ) {
        Row(Modifier.fillMaxWidth().height(MouseHeight * 0.45f)) {
            Box(Modifier.weight(1f).fillMaxSize().background(fill(MouseButton.LEFT)).clickable(role = Role.RadioButton) { onPick(MouseButton.LEFT) })
            Box(Modifier.width(1.5.dp).fillMaxSize().background(line))
            Box(Modifier.weight(1f).fillMaxSize().background(fill(MouseButton.RIGHT)).clickable(role = Role.RadioButton) { onPick(MouseButton.RIGHT) })
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(width = 26.dp, height = 36.dp).clickable(role = Role.RadioButton) { onPick(MouseButton.MIDDLE) }) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 12.dp, height = 24.dp)
                    .clip(CircleShape)
                    .background(if (chosen == MouseButton.MIDDLE) scheme.primary else scheme.surfaceContainerHigh)
                    .border(1.5.dp, if (chosen == MouseButton.MIDDLE) scheme.onPrimary.copy(alpha = 0.6f) else line, CircleShape),
            )
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = MouseHeight * 0.45f).fillMaxWidth().height(1.5.dp).background(line))
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = secondaryText(), modifier = Modifier.padding(horizontal = 6.dp))
        Surface(Modifier.fillMaxWidth(), shape = GroupShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.padding(8.dp)) { content() }
        }
    }
}

@Composable
private fun KeyCap(label: String, on: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .defaultMinSize(minWidth = 36.dp)
            .height(36.dp)
            .clip(KeyShape)
            .background(if (on) scheme.primary else scheme.surfaceContainerHighest)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                on -> scheme.onPrimary
                icon != null -> scheme.primary
                else -> scheme.onSurface
            },
            maxLines = 1,
        )
    }
}

// null as the action asks to delete the layout, which needs its own confirmation.
@Composable
private fun LayoutsSheet(store: ButtonLayouts, onDismiss: () -> Unit, onPick: (Int) -> Unit, onAction: (Naming?) -> Unit) {
    Sheet(onDismiss) { modal ->
        SheetTitle(stringResource(R.string.buttons_layouts))
        SheetList {
            store.layouts.forEachIndexed { index, layout ->
                if (index > 0) SheetListDivider()
                val on = index == store.active
                SheetListRow(
                    title = layout.name,
                    subtitle = pluralStringResource(R.plurals.buttons_count, layout.buttons.size, layout.buttons.size),
                    selected = on,
                    leading = { SquareIcon(Icons.Outlined.DashboardCustomize, active = on) },
                    trailing = if (on) ({ Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    onClick = { modal.close { onPick(index) } },
                )
            }
        }
        SheetList {
            SheetListRow(stringResource(R.string.layout_new), { modal.close { onAction(Naming.NewLayout) } }, leading = { SquareIcon(Icons.Outlined.Add) })
            SheetListDivider()
            SheetListRow(stringResource(R.string.layout_rename), { modal.close { onAction(Naming.RenameLayout) } }, leading = { SquareIcon(Icons.Outlined.Edit) })
            SheetListDivider()
            SheetListRow(stringResource(R.string.layout_duplicate), { modal.close { onAction(Naming.DuplicateLayout) } }, leading = { SquareIcon(Icons.Outlined.ContentCopy) })
            if (store.layouts.size > 1) {
                SheetListDivider()
                SheetListRow(
                    stringResource(R.string.layout_delete),
                    { modal.close { onAction(null) } },
                    titleColor = MaterialTheme.colorScheme.error,
                    leading = { SquareIcon(Icons.Outlined.DeleteOutline, tint = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }
}
