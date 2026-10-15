package dev.mkzk.manifold.hub.broker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.broker.Access
import dev.mkzk.manifold.hub.broker.AppRow
import dev.mkzk.manifold.hub.broker.PendingRow
import dev.mkzk.manifold.hub.broker.Registry
import dev.mkzk.manifold.hub.ui.IconBadge
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.Panel
import dev.mkzk.manifold.hub.ui.PanelDivider
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetTitle
import dev.mkzk.manifold.hub.ui.secondaryText

@Composable
internal fun AppsScreen(registry: Registry, apps: List<AppRow>, pending: List<PendingRow>, modifier: Modifier = Modifier) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val asking = pending.map { it.packageName }.toSet()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (apps.isEmpty() && pending.isEmpty()) {
            item {
                Panel { ListRow(title = stringResource(R.string.apps_empty), leading = { IconBadge(Icons.Outlined.Extension) }) }
            }
        }
        if (pending.isNotEmpty()) {
            item {
                Panel(title = stringResource(R.string.apps_needs_answer), meta = pending.size.toString()) {
                    pending.forEachIndexed { index, request ->
                        if (index > 0) PanelDivider()
                        PendingItem(request, registry)
                    }
                }
            }
        }
        for ((title, access) in listOf(R.string.access_allowed to Access.ALLOWED, R.string.menu_ask to Access.ASK, R.string.access_blocked to Access.BLOCKED)) {
            val group = apps.filter { it.access == access && it.packageName !in asking }
            if (group.isEmpty()) continue
            item(key = "apps-$access") {
                Panel(title = stringResource(title), meta = group.size.toString()) {
                    group.forEachIndexed { index, app ->
                        if (index > 0) PanelDivider()
                        AppItem(app, onClick = { chosen = app.packageName })
                    }
                }
            }
        }
    }
    apps.firstOrNull { it.packageName == chosen }?.let { app ->
        AccessSheet(app, onPick = { registry.setAccess(app.packageName, it) }, onDismiss = { chosen = null })
    }
}

@Composable
private fun PendingItem(request: PendingRow, registry: Registry) {
    ListRow(
        title = request.app,
        subtitle = stringResource(R.string.pending_wants, request.feeds.joinToString(", ")),
        leading = { IconBadge(Icons.Outlined.Extension) },
        below = {
            if (request.certificateChanged) {
                Text(stringResource(R.string.cert_changed_pending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            AnswerButtons(request.packageName, registry, Modifier.padding(top = 8.dp))
        },
    )
}

@Composable
private fun AppItem(app: AppRow, onClick: () -> Unit) {
    ListRow(
        title = app.app,
        subtitle = if (app.connected) "${app.packageName} · ${stringResource(R.string.app_connected)}" else app.packageName,
        subtitleMaxLines = 1,
        leading = { IconBadge(Icons.Outlined.Extension, active = app.access == Access.ALLOWED) },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        onClick = onClick,
        below = {
            if (app.certificateChanged) {
                Text(stringResource(R.string.cert_changed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

private class AccessLook(val icon: ImageVector, val hint: Int, val menu: Int)

private fun look(access: Access) = when (access) {
    Access.ALLOWED -> AccessLook(Icons.Filled.Check, R.string.menu_allow_hint, R.string.menu_allow)
    Access.ASK -> AccessLook(Icons.Outlined.QuestionMark, R.string.menu_ask_hint, R.string.menu_ask)
    Access.BLOCKED -> AccessLook(Icons.Outlined.Block, R.string.menu_block_hint, R.string.menu_block)
}

@Composable
private fun AccessSheet(app: AppRow, onPick: (Access) -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        SheetTitle(app.app)
        Text(
            app.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = secondaryText(),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Panel {
            listOf(Access.ALLOWED, Access.ASK, Access.BLOCKED).forEachIndexed { index, option ->
                if (index > 0) PanelDivider()
                val shown = look(option)
                ListRow(
                    title = stringResource(shown.menu),
                    subtitle = stringResource(shown.hint),
                    titleColor = if (option == Access.BLOCKED) MaterialTheme.colorScheme.error else Color.Unspecified,
                    leading = { IconBadge(shown.icon, active = app.access == option) },
                    trailing = { RadioButton(selected = app.access == option, onClick = null) },
                    onClick = {
                        onPick(option)
                        onDismiss()
                    },
                )
            }
        }
    }
}
