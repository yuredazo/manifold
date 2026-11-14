package dev.mkzk.manifold.hub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val PageCardShape = RoundedCornerShape(24.dp)
private val PageRowShape = RoundedCornerShape(18.dp)

@Composable
internal fun PageCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = PageCardShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp), content = content)
    }
}

@Composable
internal fun PageDivider() {
    HorizontalDivider(Modifier.padding(start = 54.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
internal fun PageRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    note: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    external: Boolean = false,
    role: Role = Role.Button,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .padding(horizontal = 6.dp)
            .clip(PageRowShape)
            .clickable(role = role, onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = if (note != null) 10.dp else 0.dp, bottom = if (note != null) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = secondaryText(), modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = secondaryText(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor ?: secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        }
        when {
            trailing != null -> trailing()
            external -> Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = secondaryText().copy(alpha = 0.5f), modifier = Modifier.padding(start = 4.dp).size(16.dp))
            else -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = secondaryText(), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun PageSwitch(label: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    PageRow(label, icon, onClick = { onChange(!checked) }, role = Role.Switch) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.graphicsLayer {
                scaleX = 0.8f
                scaleY = 0.8f
                transformOrigin = TransformOrigin(1f, 0.5f)
            },
        )
    }
}
