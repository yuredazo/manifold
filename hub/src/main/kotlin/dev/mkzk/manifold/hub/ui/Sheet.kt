package dev.mkzk.manifold.hub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val ModalControlHeight = 48.dp
private val ModalPadding = 12.dp
private val FieldShape = RoundedCornerShape(ModalControlHeight / 2)

private val ModalShape = RoundedCornerShape(ModalControlHeight / 2 + ModalPadding)
private val ModalMargin = 8.dp
private val ModalShadow = 6.dp
private val ModalMaxWidth = 640.dp
private val TextInset = 12.dp
private val GroupShape = RoundedCornerShape(24.dp)
private val GroupRowShape = RoundedCornerShape(18.dp)
private val SquareShape = RoundedCornerShape(12.dp)
private const val MODAL_OUT_MS = 160
private const val MODAL_DAMPING = 0.82f
private const val MODAL_STIFFNESS = 340f
private const val DISABLED_ALPHA = 0.38f

internal class ModalScope(private val closing: (() -> Unit) -> Unit) {
    // then runs once the modal has slid away, so removing it from the screen never cuts the animation.
    fun close(then: () -> Unit) = closing(then)
}

internal enum class ButtonKind { Main, Quiet, Danger }

internal class ModalAction(val label: String, val kind: ButtonKind = ButtonKind.Main, val enabled: Boolean = true, val onClick: () -> Unit)

@Composable
internal fun BottomModal(
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    contentPadding: Dp = ModalPadding,
    content: @Composable ColumnScope.(ModalScope) -> Unit,
) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = remember {
        ModalScope { then ->
            if (visible.targetState) {
                after = then
                visible.targetState = false
            }
        }
    }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) (after ?: onDismiss)()
    }
    val dismiss = { if (dismissible) scope.close(onDismiss) }
    Dialog(
        dismiss,
        DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible, usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = dismiss)
                .statusBarsPadding().imePadding().navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible,
                enter = slideInVertically(spring(dampingRatio = MODAL_DAMPING, stiffness = MODAL_STIFFNESS)) { it } + fadeIn(),
                exit = slideOutVertically(tween(MODAL_OUT_MS)) { it } + fadeOut(tween(MODAL_OUT_MS)),
            ) {
                val scheme = MaterialTheme.colorScheme
                Surface(
                    Modifier.padding(ModalMargin).widthIn(max = ModalMaxWidth).fillMaxWidth(),
                    shape = ModalShape,
                    color = scheme.surfaceContainer,
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                    shadowElevation = ModalShadow,
                ) {
                    ModalSurfaces { Column(Modifier.animateContentSize().padding(contentPadding)) { content(scope) } }
                }
            }
        }
    }
}

@Composable
internal fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.(ModalScope) -> Unit) {
    BottomModal(onDismiss, contentPadding = 0.dp) { modal ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = ModalPadding, end = ModalPadding, top = 20.dp, bottom = ModalPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content(modal) }
    }
}

// Inside the card everything sits one surface level up, so groups and fields keep their contrast against it.
@Composable
private fun ModalSurfaces(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = scheme.raised(), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

private fun ColorScheme.raised(): ColorScheme {
    val group = lerp(surfaceContainer, surfaceContainerHigh, 0.6f)
    return copy(
        surface = surfaceContainer,
        surfaceContainerLowest = surfaceContainer,
        surfaceContainerLow = surfaceContainer,
        surfaceContainer = group,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = lerp(surfaceContainerHigh, surfaceContainerHighest, 0.6f),
    )
}

@Composable
internal fun ChoiceModal(
    title: String,
    message: String?,
    actions: List<ModalAction>,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BottomModal(onDismiss, dismissible) { modal ->
        Column(
            Modifier.fillMaxWidth().padding(start = TextInset, end = TextInset, top = TextInset, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = secondaryText(), textAlign = TextAlign.Center)
        }
        if (body != null) {
            Spacer(Modifier.height(12.dp))
            body()
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action -> ModalButton(action.label, action.kind, Modifier.weight(1f), enabled = action.enabled) { modal.close(action.onClick) } }
            }
        }
    }
}

@Composable
internal fun ConfirmModal(title: String, message: String, action: String, cancel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ChoiceModal(title, message, listOf(ModalAction(cancel, ButtonKind.Quiet, onClick = onDismiss), ModalAction(action, ButtonKind.Danger, onClick = onConfirm)), onDismiss)
}

@Composable
internal fun TextModal(
    title: String,
    message: String?,
    placeholder: String,
    action: String,
    onDismiss: () -> Unit,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    BottomModal(onDismiss) { modal ->
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            withFrameNanos {}
            focus.requestFocus()
            keyboard?.show()
        }
        val ready = text.isNotBlank()
        fun finish() {
            if (ready) modal.close { onDone(text.trim()) }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = TextInset, end = TextInset, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = secondaryText(), textAlign = TextAlign.Center)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModalField(
                text,
                { text = it },
                placeholder,
                Modifier.weight(1f).focusRequester(focus),
                keyboardOptions = keyboardOptions,
                keyboardActions = KeyboardActions(onDone = { finish() }, onGo = { finish() }),
            )
            ModalButton(action, ButtonKind.Main, Modifier, enabled = ready) { finish() }
        }
    }
}

@Composable
internal fun ModalField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        value,
        onValueChange,
        modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { field ->
            Row(
                Modifier.heightIn(min = ModalControlHeight).clip(FieldShape).background(scheme.surfaceContainerHighest).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Icon(leading, contentDescription = null, tint = secondaryText(), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = secondaryText(), maxLines = 1)
                    field()
                }
            }
        },
    )
}

@Composable
internal fun ModalButton(label: String, kind: ButtonKind, modifier: Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (fill, ink) = when (kind) {
        ButtonKind.Main -> scheme.primary to scheme.onPrimary
        ButtonKind.Quiet -> scheme.surfaceContainerHighest to scheme.onSurface
        ButtonKind.Danger -> scheme.errorContainer to scheme.onErrorContainer
    }
    Box(
        modifier.height(ModalControlHeight).alpha(if (enabled || busy) 1f else DISABLED_ALPHA).clip(CircleShape).background(fill)
            .then(if (kind == ButtonKind.Danger) Modifier.border(1.dp, scheme.error.copy(alpha = 0.6f), CircleShape) else Modifier)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = ink, strokeWidth = 2.dp)
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
        }
    }
}

@Composable
internal fun SheetTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = secondaryText(), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SheetNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = secondaryText(), modifier = modifier.padding(horizontal = 16.dp))
}

@Composable
internal fun SheetList(modifier: Modifier = Modifier, label: String? = null, meta: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = secondaryText(), modifier = Modifier.weight(1f))
                if (meta != null) Text(meta, style = MaterialTheme.typography.labelMedium, color = secondaryText())
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = GroupShape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
    }
}

@Composable
internal fun SheetListDivider() {
    HorizontalDivider(Modifier.padding(start = 66.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
internal fun SheetListRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleLines: Int = 2,
    titleColor: Color = Color.Unspecified,
    selected: Boolean = false,
    role: Role = Role.Button,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .padding(horizontal = 6.dp)
            .clip(GroupRowShape)
            .then(if (selected) Modifier.background(scheme.primary.copy(alpha = 0.14f)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(role = role, onClick = onClick) else Modifier)
            .padding(start = if (leading == null) 10.dp else 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = secondaryText(), maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 36.dp), contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

@Composable
internal fun SquareIcon(icon: ImageVector, active: Boolean = false, tint: Color? = null) {
    val scheme = MaterialTheme.colorScheme
    val ink = tint ?: if (active) scheme.primary else scheme.onSurface
    Box(
        Modifier.size(40.dp).background(if (tint != null || active) ink.copy(alpha = 0.18f) else scheme.surfaceContainerHighest, SquareShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(19.dp))
    }
}
