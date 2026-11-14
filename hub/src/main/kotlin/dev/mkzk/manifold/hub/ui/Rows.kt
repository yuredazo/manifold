package dev.mkzk.manifold.hub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val RowShape = RoundedCornerShape(18.dp)

internal val ControlShape = RoundedCornerShape(8.dp)

/** Secondary text is dimmed from the main text color, since the wallpaper-based palette makes the usual grey too faint. */
@Composable
internal fun secondaryText() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    meta: String? = null,
    online: Boolean? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (online != null) StatusDot(online)
                Text(title, style = MaterialTheme.typography.titleSmall, color = secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (meta != null) Text(meta, style = MaterialTheme.typography.labelMedium, color = secondaryText(), maxLines = 1)
            }
        }
        Surface(shape = PageCardShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
    }
}

@Composable
internal fun PanelDivider() {
    HorizontalDivider(Modifier.padding(start = 54.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
internal fun Banner(icon: ImageVector, modifier: Modifier = Modifier, error: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val tone = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Surface(shape = PageCardShape, color = tone.copy(alpha = 0.12f), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.padding(top = 1.dp).size(20.dp))
            Column(modifier = Modifier.weight(1f), content = content)
        }
    }
}

@Composable
internal fun EmptyNotice(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(48.dp).background(scheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = secondaryText(), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        if (action != null) Box(Modifier.padding(top = 14.dp)) { action() }
    }
}

@Composable
internal fun StatusDot(on: Boolean) {
    Box(
        modifier = Modifier.size(8.dp).then(
            if (on) {
                Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
            } else {
                Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            },
        ),
    )
}

@Composable
internal fun IconBadge(icon: ImageVector, active: Boolean = false) {
    Icon(icon, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else secondaryText(), modifier = Modifier.size(20.dp))
}

@Composable
internal fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    titleMaxLines: Int = Int.MAX_VALUE,
    titleColor: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .padding(horizontal = 6.dp)
            .clip(RowShape)
            .then(modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Normal, color = titleColor, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = secondaryText(), maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
            }
            below?.invoke(this)
        }
        trailing?.invoke()
    }
}

/** The whole row toggles, so the target is large without the row being tall. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        leading = icon?.let { { IconBadge(it, active = checked) } },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.graphicsLayer {
                    scaleX = 0.8f
                    scaleY = 0.8f
                    transformOrigin = TransformOrigin(1f, 0.5f)
                },
            )
        },
    )
}
