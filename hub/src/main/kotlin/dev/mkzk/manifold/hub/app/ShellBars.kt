package dev.mkzk.manifold.hub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private val BarShape = RoundedCornerShape(22.dp)
private val ItemShape = RoundedCornerShape(17.dp)

@Composable
internal fun TopBar(title: String, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 72.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        actions()
    }
}

internal class NavItem(val icon: ImageVector, val label: String, val selected: Boolean, val badge: Int, val onClick: () -> Unit)

@Composable
internal fun BottomBar(items: List<NavItem>) {
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BarShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), BarShape)
                .selectableGroup()
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEach { NavButton(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun NavButton(item: NavItem, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val tint = if (item.selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .height(54.dp)
            .clip(ItemShape)
            .then(if (item.selected) Modifier.background(accent.copy(alpha = 0.2f)) else Modifier)
            .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (item.badge > 0) {
            BadgedBox(badge = { Badge { Text(item.badge.toString()) } }) { Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
        } else {
            Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Text(item.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}
