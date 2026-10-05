package dev.mkzk.manifold.hub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun AppsScreen(apps: List<AppRow>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.apps_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            HorizontalDivider()
        }
        if (apps.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(apps, key = { it.packageName }) { AppItem(it) }
    }
}

@Composable
private fun AppItem(app: AppRow) {
    ListItem(
        headlineContent = { Text(app.app) },
        supportingContent = {
            Column {
                Text(if (app.connected) "${app.packageName}  ·  ${stringResource(R.string.app_connected)}" else app.packageName)
                if (app.certificateChanged) {
                    Text(stringResource(R.string.cert_changed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = { AccessMenu(app) },
    )
    HorizontalDivider()
}

@Composable
private fun AccessMenu(app: AppRow) {
    var open by remember { mutableStateOf(false) }
    val label = when (app.access) {
        Access.ALLOWED -> R.string.access_allowed
        Access.ASK -> R.string.access_ask
        Access.BLOCKED -> R.string.access_blocked
    }
    fun choose(access: Access) {
        open = false
        Registry.instance.setAccess(app.packageName, access)
    }
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(label))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_allow)) }, onClick = { choose(Access.ALLOWED) })
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_ask)) }, onClick = { choose(Access.ASK) })
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_block)) }, onClick = { choose(Access.BLOCKED) })
        }
    }
}
