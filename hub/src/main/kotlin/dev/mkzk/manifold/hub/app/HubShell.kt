package dev.mkzk.manifold.hub.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.Manifold
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.broker.ui.AppsScreen
import dev.mkzk.manifold.hub.broker.ui.LiveScreen
import dev.mkzk.manifold.hub.broker.ui.PreviewStats
import dev.mkzk.manifold.hub.broker.ui.SourceStatus
import dev.mkzk.manifold.hub.network.REMOTE_PREFIX
import dev.mkzk.manifold.hub.network.ui.DevicesScreen
import dev.mkzk.manifold.hub.screen.ScreenShareAction
import dev.mkzk.manifold.hub.settings.SettingsScreen
import dev.mkzk.manifold.hub.settings.UpdateAnnouncement
import kotlin.math.roundToInt

private enum class Tab(val title: Int, val icon: ImageVector) {
    Live(R.string.tab_live, Icons.Outlined.LiveTv),
    Apps(R.string.tab_apps, Icons.Outlined.Apps),
    Devices(R.string.tab_devices, Icons.Outlined.Devices),
    Settings(R.string.tab_settings, Icons.Outlined.Tune),
}

@Composable
internal fun HubShell(
    graph: HubGraph,
    notificationsOn: Boolean,
    openNotificationSettings: () -> Unit,
    startScreenShare: (Boolean) -> Unit,
) {
    val snapshot by graph.registry.state.collectAsStateWithLifecycle()
    val network = graph.network
    val networkState by network.state.collectAsStateWithLifecycle()
    // A feed from another device is registered as "feed (device)", which is what the preview looks it up by.
    val previewStats = remember(networkState.streams) {
        networkState.streams.associate {
            "${it.feed} (${it.device})".take(Manifold.MAX_NAME_LENGTH) to PreviewStats(it.window.fps, it.rttMs?.roundToInt())
        }
    }
    val devices by network.devices.collectAsStateWithLifecycle()
    val sources = remember(devices, networkState.online, networkState.latencyMs) {
        devices.associate { REMOTE_PREFIX + it.fingerprint to SourceStatus(it.publicKey in networkState.online, networkState.latencyMs[it.publicKey]) }
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            val ownPackage = LocalContext.current.packageName
            TopBar(stringResource(Tab.entries[selected].title)) {
                ScreenShareAction(graph.screenShare, snapshot.senders.firstOrNull { it.packageName == ownPackage }?.watchers ?: 0, startScreenShare)
            }
        },
        bottomBar = {
            BottomBar(
                Tab.entries.mapIndexed { index, tab ->
                    NavItem(tab.icon, stringResource(tab.title), selected == index, if (tab == Tab.Live) snapshot.pending.size else 0) { selected = index }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (Tab.entries[selected]) {
            Tab.Live -> LiveScreen(graph.registry, snapshot, networkState.feedTitles, previewStats, sources, notificationsOn, openNotificationSettings, modifier)
            Tab.Apps -> AppsScreen(graph.registry, snapshot.apps, snapshot.pending, modifier)
            Tab.Devices -> DevicesScreen(network, graph.streamPreference, networkState, devices, modifier)
            Tab.Settings -> SettingsScreen(graph.updater, notificationsOn, openNotificationSettings, modifier)
        }
    }
    UpdateAnnouncement(graph.updater)
}
