package dev.mkzk.manifold.hub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val IconColumn = 34.dp
private val RowPadding = 14.dp
private val IconGap = 12.dp
private val PanelShape = RoundedCornerShape(12.dp)
private val TileShape = RoundedCornerShape(8.dp)

internal val ControlShape = RoundedCornerShape(8.dp)

/** Secondary text is dimmed from the main text color, since the wallpaper-based palette makes the usual grey too faint. */
@Composable
internal fun secondaryText() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)

@Composable
private fun controlLine() = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    meta: String? = null,
    online: Boolean? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.fillMaxWidth().clip(PanelShape).border(1.dp, line, PanelShape)) {
        if (title != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = RowPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (online != null) {
                    StatusDot(online)
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (meta != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = secondaryText(), maxLines = 1)
                }
            }
            HorizontalDivider(color = line)
        }
        content()
    }
}

@Composable
internal fun PanelDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun Banner(icon: ImageVector, modifier: Modifier = Modifier, error: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val warm = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(warm.copy(alpha = 0.08f))
            .border(1.dp, warm.copy(alpha = 0.45f), PanelShape)
            .padding(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = warm, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), content = content)
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
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(IconColumn).border(1.dp, if (active) accent.copy(alpha = 0.5f) else controlLine(), TileShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
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
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = RowPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(IconGap))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = secondaryText(), maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
            }
            below?.invoke(this)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
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
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}
