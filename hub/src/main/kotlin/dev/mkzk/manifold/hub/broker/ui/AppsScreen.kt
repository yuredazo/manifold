package dev.mkzk.manifold.hub.broker.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.broker.Access
import dev.mkzk.manifold.hub.broker.AppRow
import dev.mkzk.manifold.hub.broker.Registry
import dev.mkzk.manifold.hub.ui.Group
import dev.mkzk.manifold.hub.ui.GroupDivider
import dev.mkzk.manifold.hub.ui.IconBadge
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetTitle
import dev.mkzk.manifold.hub.ui.secondaryText

@Composable
internal fun AppsScreen(registry: Registry, apps: List<AppRow>, modifier: Modifier = Modifier) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
        item {
            Text(
                stringResource(R.string.apps_intro),
                style = MaterialTheme.typography.bodySmall,
                color = secondaryText(),
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 12.dp),
            )
        }
        item {
            Group {
                if (apps.isEmpty()) {
                    ListRow(title = stringResource(R.string.apps_empty), leading = { IconBadge(Icons.Outlined.Extension) })
                }
                apps.forEachIndexed { index, app ->
                    if (index > 0) GroupDivider()
                    AppItem(app, onClick = { chosen = app.packageName })
                }
            }
        }
    }
    apps.firstOrNull { it.packageName == chosen }?.let { app ->
        AccessSheet(app, onPick = { registry.setAccess(app.packageName, it) }, onDismiss = { chosen = null })
    }
}

@Composable
private fun AppItem(app: AppRow, onClick: () -> Unit) {
    ListRow(
        title = app.app,
        subtitle = if (app.connected) "${app.packageName} · ${stringResource(R.string.app_connected)}" else app.packageName,
        subtitleMaxLines = 1,
        leading = { IconBadge(Icons.Outlined.Extension) },
        trailing = { AccessPill(app.access) },
        onClick = onClick,
        below = {
            if (app.certificateChanged) {
                Text(stringResource(R.string.cert_changed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

private class AccessLook(val icon: ImageVector, val label: Int, val hint: Int, val menu: Int)

private fun look(access: Access) = when (access) {
    Access.ALLOWED -> AccessLook(Icons.Filled.Check, R.string.access_allowed, R.string.menu_allow_hint, R.string.menu_allow)
    Access.ASK -> AccessLook(Icons.Outlined.QuestionMark, R.string.access_ask, R.string.menu_ask_hint, R.string.menu_ask)
    Access.BLOCKED -> AccessLook(Icons.Outlined.Block, R.string.access_blocked, R.string.menu_block_hint, R.string.menu_block)
}

/** Shows the decision at a glance. Tapping the row changes it. */
@Composable
private fun AccessPill(access: Access) {
    val blocked = access == Access.BLOCKED
    val container = if (blocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (blocked) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val tint = if (access == Access.ALLOWED) MaterialTheme.colorScheme.primary else content
    val shown = look(access)
    Surface(shape = RoundedCornerShape(50), color = container) {
        Row(modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(shown.icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(shown.label), style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

@Composable
private fun AccessSheet(app: AppRow, onPick: (Access) -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        SheetTitle(app.app)
        Text(
            app.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = secondaryText(),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        listOf(Access.ALLOWED, Access.ASK, Access.BLOCKED).forEach { option ->
            val shown = look(option)
            ListRow(
                title = stringResource(shown.menu),
                subtitle = stringResource(shown.hint),
                titleColor = if (option == Access.BLOCKED) MaterialTheme.colorScheme.error else Color.Unspecified,
                leading = { IconBadge(shown.icon) },
                trailing = { RadioButton(selected = app.access == option, onClick = null) },
                onClick = {
                    onPick(option)
                    onDismiss()
                },
            )
        }
    }
}
