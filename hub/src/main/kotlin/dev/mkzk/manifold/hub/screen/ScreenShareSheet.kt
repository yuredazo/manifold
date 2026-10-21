package dev.mkzk.manifold.hub.screen

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.ui.ControlShape
import dev.mkzk.manifold.hub.ui.IconBadge
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.Panel
import dev.mkzk.manifold.hub.ui.PanelDivider
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetTitle
import dev.mkzk.manifold.hub.ui.SwitchRow
import dev.mkzk.manifold.hub.ui.secondaryText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val AppIconSize = 34.dp

internal class LaunchableApp(val packageName: String, val label: String, val sharesLabel: Boolean, val icon: ImageBitmap)

@Composable
internal fun ScreenShareSheet(
    screenShare: ScreenShare,
    state: ScreenShare.State,
    watchers: Int,
    onDismiss: () -> Unit,
    start: (withSound: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val muted by screenShare.muted.collectAsStateWithLifecycle()
    var withSound by rememberSaveable { mutableStateOf(true) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val soundPossible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val iconPx = with(LocalDensity.current) { AppIconSize.roundToPx() }
    val apps by produceState<List<LaunchableApp>?>(null) {
        value = if (soundPossible) withContext(Dispatchers.IO) { launchableApps(context, iconPx) } else emptyList()
    }
    fun toggle(packageName: String) = screenShare.setMuted(if (packageName in muted) muted - packageName else muted + packageName)

    Sheet(onDismiss = onDismiss) {
        if (picking) {
            BackHandler { picking = false }
            AppPicker(apps, muted, ::toggle, onBack = { picking = false })
        } else {
            SheetTitle(stringResource(if (state.sharing) R.string.screen_sharing_title else R.string.screen_share))
            if (state.sharing) {
                Panel {
                    ListRow(
                        title = if (watchers > 0) stringResource(R.string.screen_watching, watchers) else stringResource(R.string.screen_nobody_watching),
                        leading = { IconBadge(Icons.Outlined.Visibility, active = watchers > 0) },
                    )
                    if (soundPossible) {
                        PanelDivider()
                        ListRow(
                            title = stringResource(R.string.screen_sound),
                            subtitle = stringResource(if (state.sound) R.string.screen_sound_on else R.string.screen_sound_off),
                            leading = {
                                IconBadge(if (state.sound) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff, active = state.sound)
                            },
                        )
                    }
                }
            } else if (soundPossible) {
                Panel {
                    SwitchRow(
                        title = stringResource(R.string.screen_sound),
                        checked = withSound,
                        onChange = { withSound = it },
                        subtitle = stringResource(R.string.screen_sound_hint),
                        icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    )
                }
            }

            val soundOn = if (state.sharing) state.sound else soundPossible && withSound
            if (soundOn) MutedApps(apps, muted, onUnmute = ::toggle, onAdd = { picking = true })

            if (state.sharing) {
                Button(
                    onClick = {
                        screenShare.stop()
                        onDismiss()
                    },
                    shape = ControlShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.screen_stop))
                }
            } else {
                Text(
                    stringResource(R.string.screen_menu_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryText(),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Button(onClick = { start(soundPossible && withSound) }, shape = ControlShape, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.screen_start))
                }
            }
        }
    }
}

@Composable
private fun MutedApps(apps: List<LaunchableApp>?, muted: Set<String>, onUnmute: (String) -> Unit, onAdd: () -> Unit) {
    val shown = apps.orEmpty().filter { it.packageName in muted }
    Panel(title = stringResource(R.string.screen_muted), meta = shown.size.takeIf { it > 0 }?.toString()) {
        shown.forEach { app ->
            ListRow(
                title = app.label,
                titleMaxLines = 1,
                subtitle = app.packageName.takeIf { app.sharesLabel },
                leading = { AppIcon(app) },
                trailing = {
                    IconButton(onClick = { onUnmute(app.packageName) }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.screen_unmute, app.label))
                    }
                },
            )
            PanelDivider()
        }
        ListRow(
            title = stringResource(R.string.screen_mute_app),
            subtitle = if (apps != null && shown.isEmpty()) stringResource(R.string.screen_mute_none) else null,
            leading = { IconBadge(Icons.Outlined.Add) },
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            onClick = onAdd,
        )
    }
}

@Composable
private fun AppPicker(apps: List<LaunchableApp>?, muted: Set<String>, onToggle: (String) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back))
        }
        Text(stringResource(R.string.screen_mute_app), style = MaterialTheme.typography.titleLarge)
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(R.string.screen_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth(),
    )
    val needle = query.trim()
    val matching = apps?.filter { it.label.contains(needle, ignoreCase = true) }
    Panel {
        when {
            matching == null -> ListRow(
                title = stringResource(R.string.screen_loading_apps),
                leading = { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) },
            )
            matching.isEmpty() -> ListRow(title = stringResource(R.string.screen_no_match, needle))
            else -> matching.forEachIndexed { index, app ->
                if (index > 0) PanelDivider()
                val checked = app.packageName in muted
                ListRow(
                    title = app.label,
                    titleMaxLines = 1,
                    subtitle = app.packageName.takeIf { app.sharesLabel },
                    modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(app.packageName) }),
                    leading = { AppIcon(app) },
                    trailing = { Checkbox(checked = checked, onCheckedChange = null) },
                )
            }
        }
    }
}

@Composable
private fun AppIcon(app: LaunchableApp) {
    Image(app.icon, contentDescription = null, modifier = Modifier.size(AppIconSize))
}

private fun launchableApps(context: Context, iconPx: Int): List<LaunchableApp> {
    val packages = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val found = packages.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
    val labels = found.associate { it.packageName to packages.getApplicationLabel(it).toString() }
    val labelCounts = labels.values.groupingBy { it }.eachCount()
    return found
        .map {
            val label = labels.getValue(it.packageName)
            LaunchableApp(it.packageName, label, labelCounts.getValue(label) > 1, packages.getApplicationIcon(it).toBitmap(iconPx, iconPx).asImageBitmap())
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}
