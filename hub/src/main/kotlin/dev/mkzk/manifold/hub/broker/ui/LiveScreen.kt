package dev.mkzk.manifold.hub.broker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.broker.Access
import dev.mkzk.manifold.hub.broker.PendingRow
import dev.mkzk.manifold.hub.broker.Registry
import dev.mkzk.manifold.hub.broker.SenderRow
import dev.mkzk.manifold.hub.broker.Snapshot
import dev.mkzk.manifold.hub.broker.SubscriptionRow
import dev.mkzk.manifold.hub.ui.Group
import dev.mkzk.manifold.hub.ui.GroupDivider
import dev.mkzk.manifold.hub.ui.IconBadge
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.SectionLabel
import dev.mkzk.manifold.hub.ui.secondaryText

private data class Tile(val name: String, val feed: SenderRow?, val watchers: List<SubscriptionRow>)

private fun tilesOf(snapshot: Snapshot): List<Tile> {
    // A name somebody watches stays on screen even while nobody sends it.
    val names = (snapshot.senders.map { it.name } + snapshot.subscriptions.map { it.senderName }).distinct()
    return names.map { name ->
        Tile(name, snapshot.senders.firstOrNull { it.name == name }, snapshot.subscriptions.filter { it.senderName == name })
    }
}

@Composable
internal fun LiveScreen(
    registry: Registry,
    snapshot: Snapshot,
    titles: Map<String, String>,
    notificationsOn: Boolean,
    openNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot.pending.isEmpty() && snapshot.senders.isEmpty() && snapshot.subscriptions.isEmpty()) {
        EmptyLive(notificationsOn, openNotificationSettings, modifier)
        return
    }
    val ownPackage = LocalContext.current.packageName

    val tiles = tilesOf(snapshot)
    // Saved, so turning the phone does not close the preview.
    var previewed by rememberSaveable { mutableStateOf<String?>(null) }
    // A feed that stops being sent leaves the preview waiting, since its sender usually comes back.
    var lastSeen by remember { mutableStateOf<SenderRow?>(null) }
    val current = snapshot.senders.firstOrNull { it.name == previewed }
    SideEffect { if (current != null) lastSeen = current }
    previewed?.let { name ->
        val known = current ?: lastSeen?.takeIf { it.name == name } ?: SenderRow(name, "", "", 0, 0, 0, false, 0)
        FeedPreview(registry, known, title = titles[name] ?: name, live = current != null) { previewed = null }
    }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
        if (!notificationsOn) {
            item { NotificationsOff(openNotificationSettings) }
        }

        if (snapshot.pending.isNotEmpty()) {
            item { SectionLabel(R.string.live_waiting_header) }
            items(snapshot.pending, key = { "pending-${it.packageName}" }) { PendingRequest(it, registry) }
        }

        item { SectionLabel(R.string.live_feeds_header) }
        item {
            Group {
                if (tiles.isEmpty()) {
                    ListRow(title = stringResource(R.string.live_no_feeds), leading = { IconBadge(Icons.Outlined.Videocam) })
                }
                tiles.forEachIndexed { index, tile ->
                    if (index > 0) GroupDivider()
                    FeedRow(tile, titles[tile.name], previewable = tile.feed != null && tile.feed.packageName != ownPackage, onPreview = { previewed = tile.name })
                }
            }
        }
    }
}

@Composable
private fun EmptyLive(notificationsOn: Boolean, openNotificationSettings: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(top = 8.dp)) {
        if (!notificationsOn) NotificationsOff(openNotificationSettings)
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(16.dp))
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
    Group {
        ListRow(
            title = stringResource(R.string.notifications_off),
            leading = { IconBadge(Icons.Outlined.NotificationsOff) },
            trailing = { TextButton(onClick = openSettings) { Text(stringResource(R.string.notifications_turn_on)) } },
        )
    }
}

@Composable
private fun PendingRequest(row: PendingRow, registry: Registry) {
    Group(modifier = Modifier.padding(bottom = 8.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(row.app, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pending_wants, row.feeds.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
            Text(row.packageName, style = MaterialTheme.typography.bodySmall, color = secondaryText())
            if (row.certificateChanged) {
                Text(
                    stringResource(R.string.cert_changed_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { registry.setAccess(row.packageName, Access.BLOCKED) }) { Text(stringResource(R.string.action_block)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { registry.setAccess(row.packageName, Access.ALLOWED) }) { Text(stringResource(R.string.action_allow)) }
            }
        }
    }
}

private const val MAX_WATCHERS = 3

@Composable
private fun FeedRow(tile: Tile, announcedTitle: String?, previewable: Boolean, onPreview: () -> Unit) {
    val feed = tile.feed
    // A feed from another device is named "window (device)", and the device is already in the line below.
    val title = announcedTitle ?: feed?.let { tile.name.removeSuffix(" (${it.app})") } ?: tile.name
    val meta = if (feed == null) {
        stringResource(R.string.feed_not_announced)
    } else {
        buildList {
            add(feed.app)
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
        leading = { IconBadge(icon) },
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
