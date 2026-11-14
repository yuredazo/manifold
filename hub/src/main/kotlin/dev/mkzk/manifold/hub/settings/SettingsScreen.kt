package dev.mkzk.manifold.hub.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.app.ThemePreference
import dev.mkzk.manifold.hub.ui.PageCard
import dev.mkzk.manifold.hub.ui.PageDivider
import dev.mkzk.manifold.hub.ui.PageRow
import dev.mkzk.manifold.hub.ui.PageSwitch
import dev.mkzk.manifold.hub.ui.secondaryText
import dev.mkzk.manifold.hub.update.Updater

private const val REPO = "https://github.com/yuredazo/manifold"

@Composable
internal fun SettingsScreen(updater: Updater, theme: ThemePreference, notificationsOn: Boolean, openNotificationSettings: () -> Unit, openAcknowledgements: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val open = { url: String -> runCatching { uri.openUri(url) } }
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    val checkOnLaunch by updater.checkOnLaunch.collectAsStateWithLifecycle()
    val wallpaperColors by theme.wallpaperColors.collectAsStateWithLifecycle()
    val (updateText, updateTone) = updateValue(updater)
    var aboutSheet by rememberSaveable { mutableStateOf(false) }
    var updateSheet by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ManifoldTile(version, onOpen = { aboutSheet = true })
        PageCard {
            PageRow(stringResource(R.string.update_title), Icons.Outlined.SystemUpdate, onClick = { updateSheet = true }, value = updateText, valueColor = updateTone)
            PageDivider()
            PageSwitch(stringResource(R.string.update_on_launch), Icons.Outlined.Refresh, checkOnLaunch, onChange = updater::setCheckOnLaunch)
        }
        PageCard {
            PageRow(
                stringResource(R.string.settings_notifications),
                Icons.Outlined.Notifications,
                onClick = openNotificationSettings,
                note = stringResource(if (notificationsOn) R.string.settings_notifications_on else R.string.settings_notifications_off),
            )
            if (ThemePreference.available) {
                PageDivider()
                PageSwitch(stringResource(R.string.settings_wallpaper_colors), Icons.Outlined.Palette, wallpaperColors, onChange = theme::setWallpaperColors)
            }
        }
        PageCard {
            PageRow(stringResource(R.string.about_source), Icons.Outlined.Code, onClick = { open(REPO) }, external = true)
            PageDivider()
            PageRow(stringResource(R.string.about_how), Icons.Outlined.Description, onClick = { open("$REPO/blob/main/docs/PROTOCOL.md") }, external = true)
            PageDivider()
            PageRow(stringResource(R.string.about_report), Icons.Outlined.BugReport, onClick = { open("$REPO/blob/main/SECURITY.md") }, external = true)
        }
        PageCard {
            PageRow(stringResource(R.string.ack_title), Icons.AutoMirrored.Outlined.LibraryBooks, onClick = openAcknowledgements)
            PageDivider()
            PageRow(stringResource(R.string.about_license), Icons.Outlined.Gavel, onClick = { open("$REPO/blob/main/LICENSE") }, value = "Apache 2.0", external = true)
        }
        Text(stringResource(R.string.settings_footer, version), style = MaterialTheme.typography.bodySmall, color = secondaryText(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    if (aboutSheet) AboutSheet(version, onDismiss = { aboutSheet = false })
    if (updateSheet) UpdateSheet(updater, onDismiss = { updateSheet = false })
}
