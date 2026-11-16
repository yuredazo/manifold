package dev.mkzk.manifold.hub.live.control

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.network.RemoteAction
import dev.mkzk.manifold.hub.network.protocol.MouseButton
import dev.mkzk.manifold.hub.ui.ButtonKind
import dev.mkzk.manifold.hub.ui.ConfirmModal
import dev.mkzk.manifold.hub.ui.ModalButton
import dev.mkzk.manifold.hub.ui.ModalField
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetTitle
import dev.mkzk.manifold.hub.ui.secondaryText
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

// Five percent apart from 10 to 100.
private const val OPACITY_STEPS = 17
private val PanelWidth = 296.dp
private val ControlHeight = 40.dp
private val FieldHeight = 48.dp
private val PanelShape = RoundedCornerShape(28.dp)
private val GroupShape = RoundedCornerShape(20.dp)
private val KeyShape = RoundedCornerShape(10.dp)
private val MiniPanelWidth = 236.dp
// Tiles sit concentric inside the small panels: 22 dp corners less 10 dp padding.
private val MiniPanelShape = RoundedCornerShape(22.dp)
private val TileShape = RoundedCornerShape(12.dp)
private val MouseWidth = 64.dp
private val MouseHeight = 92.dp
private val MouseShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 30.dp, bottomEnd = 30.dp)

// Keys most games bind, offered first so a button rarely needs the full keyboard.
private val CommonKeys = listOf(
    0x29, 0x2B, 0x14, 0x08, 0x15, 0x09, 0x17, 0x1D, 0x1B, 0x06, 0x19, 0x0B,
    0x1E, 0x1F, 0x20, 0x21, 0x22, 0xE1, 0xE0, 0xE2, 0x2C, 0x28,
)

private fun List<Int>.toggled(usage: Int) = if (usage in this) this - usage else this + usage

private enum class Naming { NewLayout, RenameLayout, DuplicateLayout, RenameButton }

@Composable
internal fun ButtonEditor(store: ButtonLayouts, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    var selected by remember { mutableStateOf<Long?>(null) }
    var allKeys by remember { mutableStateOf(false) }
    var layoutsOpen by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Naming?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var layoutOpacity by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
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
                        .graphicsLayer { alpha = button.opacity ?: layout.opacity }
                        .pointerInput(button.id, width, height) {
                            awaitEachGesture {
                                val first = awaitFirstDown()
                                first.consume()
                                selected = button.id
                                var target = button.id
                                var moved = false
                                var settled = false
                                var copied = false
                                val deadline = first.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                                var last = first.uptimeMillis
                                while (true) {
                                    val event = if (settled) {
                                        awaitPointerEvent()
                                    } else {
                                        withTimeoutOrNull((deadline - last).coerceAtLeast(1)) { awaitPointerEvent() }
                                    }
                                    if (event == null) {
                                        settled = true
                                        val source = store.current.buttons.firstOrNull { it.id == target } ?: continue
                                        val copy = store.newId()
                                        val nudge = 12.dp.toPx()
                                        store.change(save = false) {
                                            it.copy(
                                                buttons = it.buttons + source.copy(
                                                    id = copy,
                                                    x = (source.x + nudge / width.toPx()).coerceIn(0f, 1f),
                                                    y = (source.y + nudge / height.toPx()).coerceIn(0f, 1f),
                                                ),
                                            )
                                        }
                                        target = copy
                                        selected = copy
                                        copied = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        continue
                                    }
                                    val change = event.changes.firstOrNull { it.id == first.id } ?: break
                                    if (!change.pressed) break
                                    if (change.isConsumed) {
                                        settled = true
                                        continue
                                    }
                                    change.consume()
                                    last = change.uptimeMillis
                                    val step = if (moved) {
                                        change.position - change.previousPosition
                                    } else {
                                        val total = change.position - first.position
                                        if (total.getDistance() <= viewConfiguration.touchSlop) continue
                                        moved = true
                                        settled = true
                                        moving = true
                                        total
                                    }
                                    store.changeButton(target, save = false) {
                                        it.copy(
                                            x = (it.x + step.x / width.toPx()).coerceIn(0f, 1f),
                                            y = (it.y + step.y / height.toPx()).coerceIn(0f, 1f),
                                        )
                                    }
                                }
                                moving = false
                                if (moved || copied) store.save()
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
            opacity = layout.opacity,
            onLayouts = { layoutsOpen = true },
            onOpacity = {
                layoutOpacity = !layoutOpacity
                adding = false
                selected = null
            },
            onAdd = {
                adding = !adding
                layoutOpacity = false
                selected = null
            },
            onDone = onDone,
            modifier = Modifier
                .align(if (toolbarAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(vertical = 12.dp)
                .padding(start = if (panelAtStart) beside else 0.dp, end = if (panelAtStart) 0.dp else beside),
        )

        if (adding && chosen == null) {
            fun add(stick: Stick?) {
                val id = store.newId()
                val diameter = when (stick) {
                    null -> 60f
                    Stick.DPAD -> 100f
                    else -> 120f
                }
                store.change { it.copy(buttons = it.buttons + GameButton(id, 0.5f, 0.5f, diameter, stick = stick)) }
                selected = id
                adding = false
            }
            ToolbarPanel(atTop = toolbarAtTop) {
                PanelTitle(stringResource(R.string.buttons_add_title))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceTile(stringResource(R.string.buttons_add), Icons.Outlined.RadioButtonUnchecked, Modifier.weight(1f)) { add(null) }
                    ChoiceTile(stringResource(R.string.buttons_add_dpad), Icons.Outlined.Gamepad, Modifier.weight(1f)) { add(Stick.DPAD) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceTile(stringResource(R.string.buttons_add_left_stick), Icons.Outlined.Adjust, Modifier.weight(1f)) { add(Stick.LEFT) }
                    ChoiceTile(stringResource(R.string.buttons_add_right_stick), Icons.Outlined.Adjust, Modifier.weight(1f)) { add(Stick.RIGHT) }
                }
            }
        }

        if (layoutOpacity && chosen == null) {
            ToolbarPanel(atTop = toolbarAtTop) {
                PanelTitle(stringResource(R.string.buttons_opacity_layout))
                PillFader(layout.opacity, onChange = { value -> store.change(save = false) { it.copy(opacity = value) } }, onFinished = { store.save() })
                Text(
                    stringResource(R.string.buttons_opacity_layout_hint),
                    style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Heading),
                    color = secondaryText(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

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
                    onPad = { bit -> store.changeButton(chosen.id) { it.copy(pad = it.pad xor bit) } },
                    onStick = { stick -> store.changeButton(chosen.id) { it.copy(stick = stick) } },
                    layoutOpacity = layout.opacity,
                    onOpacity = { value -> store.changeButton(chosen.id, save = false) { it.copy(opacity = value) } },
                    onOpacitySaved = { store.save() },
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
private fun Toolbar(
    layoutName: String,
    opacity: Float,
    onLayouts: () -> Unit,
    onOpacity: () -> Unit,
    onAdd: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Floating(shape = CircleShape, modifier = modifier) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill(layoutName, Icons.Outlined.ExpandMore, iconAfter = true, onClick = onLayouts)
            Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Pill(percent(opacity), Icons.Outlined.Opacity, widest = percent(1f), onClick = onOpacity)
            Pill(stringResource(R.string.buttons_add), Icons.Outlined.Add, onClick = onAdd)
            Pill(stringResource(R.string.buttons_done), Icons.Outlined.Check, strong = true, onClick = onDone)
        }
    }
}

@Composable
// widest reserves room for the longest label the pill can show, so a changing number does not move its neighbours.
private fun Pill(label: String, icon: ImageVector, iconAfter: Boolean = false, strong: Boolean = false, widest: String? = null, onClick: () -> Unit) {
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
        Box(contentAlignment = Alignment.Center) {
            val style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
            if (widest != null) Text(widest, style = style, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.alpha(0f))
            Text(label, color = ink, style = style, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
    onPad: (Int) -> Unit,
    onStick: (Stick) -> Unit,
    layoutOpacity: Float,
    onOpacity: (Float?) -> Unit,
    onOpacitySaved: () -> Unit,
    onAllKeys: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val kind = stringResource(
            when (button.stick) {
                null -> R.string.buttons_add
                Stick.LEFT -> R.string.buttons_add_left_stick
                Stick.RIGHT -> R.string.buttons_add_right_stick
                Stick.DPAD -> R.string.buttons_add_dpad
            },
        )
        val title = if (button.stick != null) button.name.ifBlank { kind } else button.label().ifEmpty { stringResource(R.string.buttons_untitled) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(ControlHeight).clip(CircleShape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(
                    when (button.stick) {
                        null -> Icons.Outlined.RadioButtonUnchecked
                        Stick.DPAD -> Icons.Outlined.Gamepad
                        else -> Icons.Outlined.Adjust
                    },
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (title != kind) Text(kind, style = MaterialTheme.typography.labelMedium, color = secondaryText(), maxLines = 1)
            }
            RoundIcon(Icons.Outlined.Edit, stringResource(R.string.buttons_rename), onClick = onRename)
            RoundIcon(Icons.Outlined.DeleteOutline, stringResource(R.string.buttons_delete), tint = scheme.error, onClick = onDelete)
            RoundIcon(Icons.Outlined.Close, stringResource(R.string.buttons_close), onClick = onClose)
        }

        val opacity: @Composable () -> Unit = {
            Group(stringResource(R.string.buttons_opacity)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyCap(stringResource(R.string.buttons_opacity_follow), on = button.opacity == null) {
                        onOpacity(if (button.opacity == null) layoutOpacity else null)
                        onOpacitySaved()
                    }
                    PillFader(button.opacity ?: layoutOpacity, onChange = onOpacity, onFinished = onOpacitySaved, height = 36.dp, modifier = Modifier.weight(1f))
                }
            }
        }
        val stick = button.stick
        if (stick != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.buttons_stick), style = MaterialTheme.typography.labelMedium, color = secondaryText(), modifier = Modifier.padding(horizontal = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceTile(stringResource(R.string.buttons_add_left_stick), Icons.Outlined.Adjust, Modifier.weight(1f), on = stick == Stick.LEFT) { onStick(Stick.LEFT) }
                    ChoiceTile(stringResource(R.string.buttons_add_right_stick), Icons.Outlined.Adjust, Modifier.weight(1f), on = stick == Stick.RIGHT) { onStick(Stick.RIGHT) }
                    ChoiceTile(stringResource(R.string.buttons_add_dpad), Icons.Outlined.Gamepad, Modifier.weight(1f), on = stick == Stick.DPAD) { onStick(Stick.DPAD) }
                }
            }
            opacity()
            return@Column
        }

        Group(stringResource(R.string.buttons_keys)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (CommonKeys + button.keys.filter { it !in CommonKeys }).forEach { usage ->
                    KeyCap(keyName(usage), usage in button.keys) { onKey(usage) }
                }
                KeyCap(stringResource(R.string.buttons_more_keys), on = false, icon = Icons.Outlined.Keyboard, onClick = onAllKeys)
            }
        }

        Group(stringResource(R.string.buttons_controller)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PadButtons.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { (bit, name) -> KeyCap(name, button.pad and bit != 0, modifier = Modifier.weight(1f)) { onPad(bit) } }
                    }
                }
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

        opacity()

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

// The number keeps a fixed place, so dragging never moves anything beside it.
@Composable
private fun PillFader(value: Float, onChange: (Float) -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier, height: Dp = ControlHeight) {
    val scheme = MaterialTheme.colorScheme
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinished)
    val label = percent(value)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHighest)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, MIN_OPACITY..1f, OPACITY_STEPS)
                setProgress { target ->
                    change(snapped(target))
                    finish()
                    true
                }
            }
            .pointerInput(Unit) {
                fun at(x: Float) = snapped(MIN_OPACITY + (x / size.width).coerceIn(0f, 1f) * (1f - MIN_OPACITY))
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    change(at(down.position.x))
                    while (true) {
                        val moved = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!moved.pressed) break
                        moved.consume()
                        change(at(moved.position.x))
                    }
                    finish()
                }
            },
    ) {
        val fill = height + (maxWidth - height) * ((value - MIN_OPACITY) / (1f - MIN_OPACITY))
        val numberWidth = 52.dp
        Box(Modifier.fillMaxHeight().width(fill).clip(CircleShape).background(scheme.primary))
        Icon(
            Icons.Outlined.Opacity,
            contentDescription = null,
            tint = scheme.onPrimary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = (height - 18.dp) / 2).size(18.dp),
        )
        Text(
            label,
            color = if (fill >= maxWidth - numberWidth / 2) scheme.onPrimary else scheme.onSurface,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.align(Alignment.CenterEnd).width(numberWidth).padding(end = 14.dp),
        )
    }
}

private fun snapped(value: Float) = ((value * 20).roundToInt() / 20f).coerceIn(MIN_OPACITY, 1f)

private fun percent(value: Float) = "${(value * 100).roundToInt()}%"

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
private fun ChoiceTile(label: String, icon: ImageVector, modifier: Modifier, on: Boolean = false, color: Color? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .height(58.dp)
            .clip(TileShape)
            .background(if (on) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceContainerHighest)
            .then(if (on) Modifier.border(1.5.dp, scheme.primary, TileShape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = color ?: scheme.primary, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color ?: scheme.onSurface, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun BoxScope.ToolbarPanel(atTop: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Floating(
        shape = MiniPanelShape,
        modifier = Modifier
            .align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
            .safeDrawingPadding()
            .padding(vertical = 12.dp + ControlHeight + 16.dp, horizontal = 12.dp)
            .widthIn(max = MiniPanelWidth)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
}

@Composable
private fun KeyCap(label: String, on: Boolean, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
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

@Composable
private fun LayoutTile(layout: ButtonLayout, on: Boolean, dragging: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = TileShape,
        color = if (on) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceContainerHigh,
        border = if (on) BorderStroke(1.5.dp, scheme.primary) else null,
        shadowElevation = if (dragging) 8.dp else 0.dp,
        onClick = onClick,
    ) {
        Row(Modifier.heightIn(min = 64.dp).padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(layout.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.buttons_count, layout.buttons.size, layout.buttons.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = secondaryText(),
                )
            }
            if (on) Icon(Icons.Outlined.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

// null as the action asks to delete the layout, which needs its own confirmation.
@Composable
private fun LayoutsSheet(store: ButtonLayouts, onDismiss: () -> Unit, onPick: (Int) -> Unit, onAction: (Naming?) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Sheet(onDismiss) { modal ->
        SheetTitle(stringResource(R.string.buttons_layouts), subtitle = stringResource(R.string.layout_reorder_hint))
        val grid = rememberLazyGridState()
        val reorder = rememberReorderableLazyGridState(grid) { from, to ->
            store.move(from.index, to.index)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            state = grid,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(store.layouts, key = { _, layout -> layout.id }) { index, layout ->
                ReorderableItem(reorder, key = layout.id) { dragging ->
                    LayoutTile(
                        layout = layout,
                        on = index == store.active,
                        dragging = dragging,
                        modifier = Modifier.longPressDraggableHandle(
                            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                        ),
                        onClick = { modal.close { onPick(index) } },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceTile(stringResource(R.string.layout_new), Icons.Outlined.Add, Modifier.weight(1f)) { modal.close { onAction(Naming.NewLayout) } }
            ChoiceTile(stringResource(R.string.layout_rename), Icons.Outlined.Edit, Modifier.weight(1f)) { modal.close { onAction(Naming.RenameLayout) } }
            ChoiceTile(stringResource(R.string.layout_duplicate), Icons.Outlined.ContentCopy, Modifier.weight(1f)) { modal.close { onAction(Naming.DuplicateLayout) } }
            if (store.layouts.size > 1) {
                ChoiceTile(stringResource(R.string.layout_delete), Icons.Outlined.DeleteOutline, Modifier.weight(1f), color = MaterialTheme.colorScheme.error) {
                    modal.close { onAction(null) }
                }
            }
        }
    }
}
