package dev.mkzk.manifold.hub.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.mkzk.manifold.hub.settings.AcknowledgementsPage
import dev.mkzk.manifold.hub.settings.SettingsScreen
import dev.mkzk.manifold.hub.settings.UpdateAnnouncement
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val PAGE_FADE_MS = 180

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
    val pager = rememberPagerState { Tab.entries.size }
    val scope = rememberCoroutineScope()
    var acknowledgements by rememberSaveable { mutableStateOf(false) }
    val titles = Tab.entries.map { stringResource(it.title) }

    AnimatedContent(
        acknowledgements,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(pillSpring()) { it / 4 } + fadeIn(tween(PAGE_FADE_MS))) togetherWith (slideOutHorizontally(pillSpring()) { -it / 8 } + fadeOut(tween(PAGE_FADE_MS)))
            } else {
                (slideInHorizontally(pillSpring()) { -it / 8 } + fadeIn(tween(PAGE_FADE_MS))) togetherWith (slideOutHorizontally(pillSpring()) { it / 4 } + fadeOut(tween(PAGE_FADE_MS)))
            }
        },
        label = "page",
    ) { showingAcknowledgements ->
        if (showingAcknowledgements) {
            AcknowledgementsPage(onBack = { acknowledgements = false })
            return@AnimatedContent
        }
        Scaffold(
            topBar = {
                val ownPackage = LocalContext.current.packageName
                TopBar(titles, pager) {
                    ScreenShareAction(graph.screenShare, snapshot.senders.firstOrNull { it.packageName == ownPackage }?.watchers ?: 0, startScreenShare)
                }
            },
            bottomBar = {
                BottomBar(
                    Tab.entries.mapIndexed { index, tab -> NavItem(tab.icon, titles[index], if (tab == Tab.Live) snapshot.pending.size else 0) },
                    pager,
                ) { index -> scope.launch { pager.animateScrollToPage(index, animationSpec = pillSpring()) } }
            },
        ) { padding ->
            HorizontalPager(pager, Modifier.padding(padding).fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page ->
                when (Tab.entries[page]) {
                    Tab.Live -> LiveScreen(graph.registry, snapshot, networkState.feedTitles, previewStats, sources, notificationsOn, openNotificationSettings, Modifier)
                    Tab.Apps -> AppsScreen(graph.registry, snapshot.apps, snapshot.pending, Modifier)
                    Tab.Devices -> DevicesScreen(network, graph.streamPreference, networkState, devices, Modifier)
                    Tab.Settings -> SettingsScreen(graph.updater, graph.theme, notificationsOn, openNotificationSettings, openAcknowledgements = { acknowledgements = true }, Modifier)
                }
            }
        }
    }
    UpdateAnnouncement(graph.updater)
}
