package dev.mkzk.manifold.hub.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.about.AboutScreen
import dev.mkzk.manifold.hub.about.UpdateAnnouncement
import dev.mkzk.manifold.hub.broker.ui.AppsScreen
import dev.mkzk.manifold.hub.broker.ui.LiveScreen
import dev.mkzk.manifold.hub.network.ui.DevicesScreen
import dev.mkzk.manifold.hub.screen.ScreenShareAction

private enum class Tab(val title: Int, val icon: ImageVector) {
    Live(R.string.tab_live, Icons.Outlined.LiveTv),
    Apps(R.string.tab_apps, Icons.Outlined.Apps),
    Devices(R.string.tab_devices, Icons.Outlined.Devices),
    About(R.string.tab_about, Icons.Outlined.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val devices by network.devices.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            val ownPackage = LocalContext.current.packageName
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    ScreenShareAction(graph.screenShare, snapshot.senders.firstOrNull { it.packageName == ownPackage }?.watchers ?: 0, startScreenShare)
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            if (tab == Tab.Live && snapshot.pending.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text(snapshot.pending.size.toString()) } }) {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            } else {
                                Icon(tab.icon, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(tab.title)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (Tab.entries[selected]) {
            Tab.Live -> LiveScreen(graph.registry, snapshot, networkState.feedTitles, notificationsOn, openNotificationSettings, modifier)
            Tab.Apps -> AppsScreen(graph.registry, snapshot.apps, modifier)
            Tab.Devices -> DevicesScreen(network, networkState, devices, modifier)
            Tab.About -> AboutScreen(graph.updater, modifier)
        }
    }
    UpdateAnnouncement(graph.updater)
}
