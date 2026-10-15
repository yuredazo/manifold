package dev.mkzk.manifold.hub.broker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.broker.Access
import dev.mkzk.manifold.hub.broker.Registry
import dev.mkzk.manifold.hub.broker.SenderRow
import dev.mkzk.manifold.hub.broker.Snapshot
import dev.mkzk.manifold.hub.broker.SubscriptionRow
import dev.mkzk.manifold.hub.network.REMOTE_PREFIX
import dev.mkzk.manifold.hub.ui.Banner
import dev.mkzk.manifold.hub.ui.IconBadge
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.Panel
import dev.mkzk.manifold.hub.ui.PanelDivider
import dev.mkzk.manifold.hub.ui.secondaryText

internal class SourceStatus(val online: Boolean, val latencyMs: Int?)

private data class Tile(val name: String, val feed: SenderRow?, val watchers: List<SubscriptionRow>)

private fun tilesOf(snapshot: Snapshot): List<Tile> {
    // A name somebody watches stays on screen even while nobody sends it.
    val names = (snapshot.senders.map { it.name } + snapshot.subscriptions.map { it.senderName }).distinct()
    return names.map { name ->
        Tile(name, snapshot.senders.firstOrNull { it.name == name }, snapshot.subscriptions.filter { it.senderName == name })
    }
}

private const val LOCAL = ""
private const val WAITING = "?"

// Remote feeds are registered under the other device's key, so that key is the source.
private fun Tile.sourceKey() = when {
    feed == null -> WAITING
    feed.packageName.startsWith(REMOTE_PREFIX) -> feed.packageName
    else -> LOCAL
}

@Composable
internal fun LiveScreen(
    registry: Registry,
    snapshot: Snapshot,
    titles: Map<String, String>,
    stats: Map<String, PreviewStats>,
    sources: Map<String, SourceStatus>,
    notificationsOn: Boolean,
    openNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot.pending.isEmpty() && snapshot.senders.isEmpty() && snapshot.subscriptions.isEmpty()) {
        EmptyLive(notificationsOn, openNotificationSettings, modifier)
        return
    }
    val ownPackage = LocalContext.current.packageName

    val bySource = tilesOf(snapshot).groupBy { it.sourceKey() }
    // Saved, so turning the phone does not close the preview.
    var previewed by rememberSaveable { mutableStateOf<String?>(null) }
    // A feed that stops being sent leaves the preview waiting, since its sender usually comes back.
    var lastSeen by remember { mutableStateOf<SenderRow?>(null) }
    val current = snapshot.senders.firstOrNull { it.name == previewed }
    SideEffect { if (current != null) lastSeen = current }
    previewed?.let { name ->
        val known = current ?: lastSeen?.takeIf { it.name == name } ?: SenderRow(name, "", "", 0, 0, 0, false, 0)
        FeedPreview(registry, known, title = titles[name] ?: name, stats = stats[name], live = current != null) { previewed = null }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!notificationsOn) {
            item { NotificationsOff(openNotificationSettings) }
        }
        items(snapshot.pending, key = { "pending-${it.packageName}" }) { PendingBanner(it, registry) }

        items(bySource.keys.filter { it.startsWith(REMOTE_PREFIX) }, key = { "source-$it" }) { key ->
            val tiles = bySource.getValue(key)
            val status = sources[key]
            SourcePanel(
                title = tiles.first().feed?.app.orEmpty(),
                online = status?.online ?: true,
                meta = statusText(status),
                tiles = tiles,
                titles = titles,
                ownPackage = ownPackage,
                onPreview = { previewed = it },
            )
        }
        bySource[LOCAL]?.let { tiles ->
            item(key = "source-local") {
                SourcePanel(stringResource(R.string.devices_this_phone), online = true, meta = null, tiles = tiles, titles = titles, ownPackage = ownPackage, onPreview = { previewed = it })
            }
        }
        bySource[WAITING]?.let { tiles ->
            item(key = "source-waiting") {
                SourcePanel(stringResource(R.string.live_waiting_sender), online = null, meta = null, tiles = tiles, titles = titles, ownPackage = ownPackage, onPreview = { previewed = it })
            }
        }
    }
}

@Composable
private fun statusText(status: SourceStatus?): String? {
    if (status == null) return null
    if (!status.online) return stringResource(R.string.device_offline)
    return listOfNotNull(stringResource(R.string.device_online), status.latencyMs?.let { stringResource(R.string.device_latency, it) }).joinToString(" · ")
}

@Composable
private fun SourcePanel(
    title: String,
    online: Boolean?,
    meta: String?,
    tiles: List<Tile>,
    titles: Map<String, String>,
    ownPackage: String,
    onPreview: (String) -> Unit,
) {
    Panel(title = title, online = online, meta = meta) {
        tiles.forEachIndexed { index, tile ->
            if (index > 0) PanelDivider()
            FeedRow(tile, titles[tile.name], previewable = tile.feed != null && tile.feed.packageName != ownPackage, onPreview = { onPreview(tile.name) })
        }
    }
}

@Composable
private fun EmptyLive(notificationsOn: Boolean, openNotificationSettings: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (!notificationsOn) NotificationsOff(openNotificationSettings)
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.live_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.live_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryText(),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun NotificationsOff(openSettings: () -> Unit) {
    Banner(Icons.Outlined.NotificationsOff) {
        Text(stringResource(R.string.notifications_off), style = MaterialTheme.typography.bodyMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = openSettings) { Text(stringResource(R.string.notifications_turn_on)) }
        }
    }
}

private const val MAX_WATCHERS = 3

@Composable
private fun FeedRow(tile: Tile, announcedTitle: String?, previewable: Boolean, onPreview: () -> Unit) {
    val feed = tile.feed
    val remote = feed?.packageName?.startsWith(REMOTE_PREFIX) == true
    // A feed from another device is named "window (device)", and the device is the title of its panel.
    val title = announcedTitle ?: feed?.let { tile.name.removeSuffix(" (${it.app})") } ?: tile.name
    val meta = if (feed == null) {
        stringResource(R.string.feed_not_announced)
    } else {
        buildList {
            if (!remote) add(feed.app)
            if (feed.soundOnly) {
                add(stringResource(R.string.feed_sound_only))
            } else {
                if (feed.width > 0 && feed.height > 0) add("${feed.width}x${feed.height}")
                if (feed.fps > 0) add("${feed.fps} fps")
            }
        }.joinToString(" · ")
    }
    val icon = when {
        feed == null -> Icons.Outlined.HourglassEmpty
        feed.soundOnly -> Icons.AutoMirrored.Outlined.VolumeUp
        else -> Icons.Outlined.Videocam
    }
    val watchers = if (tile.watchers.isEmpty()) {
        null
    } else {
        val names = tile.watchers.take(MAX_WATCHERS).map { watcherLabel(it) }
        val more = tile.watchers.size - MAX_WATCHERS
        stringResource(R.string.feed_watched_by, (names + listOfNotNull(if (more > 0) stringResource(R.string.feed_more_watchers, more) else null)).joinToString(", "))
    }
    // The hub's own screen would show itself, so it has no preview.
    ListRow(
        title = title,
        titleMaxLines = 1,
        subtitle = meta,
        subtitleMaxLines = 1,
        leading = { IconBadge(icon, active = tile.watchers.isNotEmpty()) },
        trailing = if (previewable) {
            { Icon(Icons.Outlined.PlayCircle, contentDescription = stringResource(R.string.feed_tap_preview)) }
        } else {
            null
        },
        onClick = if (previewable) onPreview else null,
        below = {
            if (watchers != null) Text(watchers, style = MaterialTheme.typography.bodySmall, color = secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}

@Composable
private fun watcherLabel(row: SubscriptionRow): String {
    val status = when {
        row.access == Access.BLOCKED -> R.string.watch_short_blocked
        row.access == Access.ASK -> R.string.watch_short_pending
        row.live -> null
        else -> R.string.watch_short_waiting
    }
    return if (status == null) row.receiverApp else "${row.receiverApp} (${stringResource(status)})"
}
