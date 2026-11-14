package dev.mkzk.manifold.hub.screen

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
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
import dev.mkzk.manifold.hub.ui.ButtonKind
import dev.mkzk.manifold.hub.ui.ModalButton
import dev.mkzk.manifold.hub.ui.ModalField
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetList
import dev.mkzk.manifold.hub.ui.SheetListDivider
import dev.mkzk.manifold.hub.ui.SheetListRow
import dev.mkzk.manifold.hub.ui.SheetNote
import dev.mkzk.manifold.hub.ui.SheetTitle
import dev.mkzk.manifold.hub.ui.SquareIcon
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
    val iconPx = with(LocalDensity.current) { AppIconSize.roundToPx() }
    val apps by produceState<List<LaunchableApp>?>(null) {
        value = withContext(Dispatchers.IO) { launchableApps(context, iconPx) }
    }
    fun toggle(packageName: String) = screenShare.setMuted(if (packageName in muted) muted - packageName else muted + packageName)

    Sheet(onDismiss = onDismiss) { modal ->
        if (picking) {
            BackHandler { picking = false }
            AppPicker(apps, muted, ::toggle, onBack = { picking = false })
        } else {
            SheetTitle(stringResource(if (state.sharing) R.string.screen_sharing_title else R.string.screen_share))
            if (state.sharing) {
                SheetList {
                    SheetListRow(
                        title = if (watchers > 0) stringResource(R.string.screen_watching, watchers) else stringResource(R.string.screen_nobody_watching),
                        onClick = null,
                        leading = { SquareIcon(Icons.Outlined.Visibility, active = watchers > 0) },
                    )
                    SheetListDivider()
                    SheetListRow(
                        title = stringResource(R.string.screen_sound),
                        subtitle = stringResource(if (state.sound) R.string.screen_sound_on else R.string.screen_sound_off),
                        onClick = null,
                        leading = {
                            SquareIcon(if (state.sound) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff, active = state.sound)
                        },
                    )
                }
            } else {
                SheetList {
                    SheetListRow(
                        title = stringResource(R.string.screen_sound),
                        subtitle = stringResource(R.string.screen_sound_hint),
                        role = Role.Switch,
                        leading = { SquareIcon(Icons.AutoMirrored.Outlined.VolumeUp, active = withSound) },
                        trailing = { Switch(checked = withSound, onCheckedChange = null) },
                        onClick = { withSound = !withSound },
                    )
                }
            }

            val soundOn = if (state.sharing) state.sound else withSound
            if (soundOn) MutedApps(apps, muted, onUnmute = ::toggle, onAdd = { picking = true })

            if (state.sharing) {
                ModalButton(stringResource(R.string.screen_stop), ButtonKind.Danger, Modifier.fillMaxWidth()) {
                    modal.close {
                        screenShare.stop()
                        onDismiss()
                    }
                }
            } else {
                SheetNote(stringResource(R.string.screen_menu_note))
                ModalButton(stringResource(R.string.screen_start), ButtonKind.Main, Modifier.fillMaxWidth()) { start(withSound) }
            }
        }
    }
}

@Composable
private fun MutedApps(apps: List<LaunchableApp>?, muted: Set<String>, onUnmute: (String) -> Unit, onAdd: () -> Unit) {
    val shown = apps.orEmpty().filter { it.packageName in muted }
    SheetList(label = stringResource(R.string.screen_muted), meta = shown.size.takeIf { it > 0 }?.toString()) {
        shown.forEach { app ->
            SheetListRow(
                title = app.label,
                subtitle = app.packageName.takeIf { app.sharesLabel },
                onClick = null,
                leading = { AppIcon(app) },
                trailing = {
                    IconButton(onClick = { onUnmute(app.packageName) }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.screen_unmute, app.label))
                    }
                },
            )
            SheetListDivider()
        }
        SheetListRow(
            title = stringResource(R.string.screen_mute_app),
            subtitle = if (apps != null && shown.isEmpty()) stringResource(R.string.screen_mute_none) else null,
            leading = { SquareIcon(Icons.Outlined.Add) },
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            onClick = onAdd,
        )
    }
}

@Composable
private fun AppPicker(apps: List<LaunchableApp>?, muted: Set<String>, onToggle: (String) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back))
        }
        SheetTitle(stringResource(R.string.screen_mute_app), Modifier.padding(horizontal = 40.dp))
    }
    ModalField(query, { query = it }, stringResource(R.string.screen_search), leading = Icons.Outlined.Search)
    val needle = query.trim()
    val matching = apps?.filter { it.label.contains(needle, ignoreCase = true) }
    SheetList {
        when {
            matching == null -> SheetListRow(
                title = stringResource(R.string.screen_loading_apps),
                onClick = null,
                leading = { Box(Modifier.size(AppIconSize), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) } },
            )
            matching.isEmpty() -> SheetListRow(title = stringResource(R.string.screen_no_match, needle), onClick = null)
            else -> matching.forEachIndexed { index, app ->
                if (index > 0) SheetListDivider()
                val checked = app.packageName in muted
                SheetListRow(
                    title = app.label,
                    subtitle = app.packageName.takeIf { app.sharesLabel },
                    role = Role.Checkbox,
                    leading = { AppIcon(app) },
                    trailing = { Checkbox(checked = checked, onCheckedChange = null) },
                    onClick = { onToggle(app.packageName) },
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
