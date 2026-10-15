package dev.mkzk.manifold.hub.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.ui.IconBadge
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.Panel
import dev.mkzk.manifold.hub.ui.PanelDivider
import dev.mkzk.manifold.hub.ui.SwitchRow
import dev.mkzk.manifold.hub.update.UpdateState
import dev.mkzk.manifold.hub.update.Updater

private const val REPO = "https://github.com/yuredazo/manifold"

private class Link(val icon: ImageVector, val label: Int, val url: String)

private val links = listOf(
    Link(Icons.Outlined.Code, R.string.about_source, REPO),
    Link(Icons.Outlined.Description, R.string.about_how, "$REPO/blob/main/docs/PROTOCOL.md"),
    Link(Icons.Outlined.BugReport, R.string.about_report, "$REPO/blob/main/SECURITY.md"),
    Link(Icons.Outlined.Gavel, R.string.about_license, "$REPO/blob/main/LICENSE"),
)

@Composable
internal fun SettingsScreen(updater: Updater, notificationsOn: Boolean, openNotificationSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    val state by updater.state.collectAsStateWithLifecycle()
    val checkOnLaunch by updater.checkOnLaunch.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        UpdateCard(updater, version)
        Panel {
            SwitchRow(stringResource(R.string.update_on_launch), checkOnLaunch, updater::setCheckOnLaunch, icon = Icons.Outlined.Refresh)
            PanelDivider()
            ListRow(
                title = stringResource(R.string.settings_notifications),
                subtitle = stringResource(if (notificationsOn) R.string.settings_notifications_on else R.string.settings_notifications_off),
                leading = { IconBadge(Icons.Outlined.Notifications, active = notificationsOn) },
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                onClick = openNotificationSettings,
            )
            (state as? UpdateState.Available)?.let { available ->
                PanelDivider()
                LinkRow(Icons.Outlined.SystemUpdate, R.string.update_release_page, available.release.page)
            }
        }
        links.chunked(2).forEach { pair ->
            Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { LinkTile(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun LinkTile(link: Link, modifier: Modifier) {
    val uri = LocalUriHandler.current
    Panel(modifier = modifier) {
        Column(modifier = Modifier.clickable { runCatching { uri.openUri(link.url) } }.padding(14.dp).fillMaxSize()) {
            IconBadge(link.icon)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(link.label), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
internal fun LinkRow(icon: ImageVector, label: Int, url: String) {
    val uri = LocalUriHandler.current
    ListRow(title = stringResource(label), leading = { IconBadge(icon) }, onClick = { runCatching { uri.openUri(url) } })
}
