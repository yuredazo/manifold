package dev.mkzk.manifold.hub

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    snapshot: Snapshot,
    notificationsOn: Boolean,
    openNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot.pending.isEmpty() && snapshot.senders.isEmpty() && snapshot.subscriptions.isEmpty()) {
        EmptyLive(notificationsOn, openNotificationSettings, modifier)
        return
    }

    val tiles = tilesOf(snapshot)
    // Saved, so turning the phone does not close the preview.
    var previewed by rememberSaveable { mutableStateOf<String?>(null) }
    // A feed that stops being sent leaves the preview waiting, since its sender usually comes back.
    var lastSeen by remember { mutableStateOf<SenderRow?>(null) }
    val current = snapshot.senders.firstOrNull { it.name == previewed }
    SideEffect { if (current != null) lastSeen = current }
    previewed?.let { name ->
        val known = current ?: lastSeen?.takeIf { it.name == name } ?: SenderRow(name, "", "", 0, 0, 0, false, 0)
        FeedPreview(known, live = current != null) { previewed = null }
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(140.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
    ) {
        if (!notificationsOn) {
            item(span = StaggeredGridItemSpan.FullLine) { NotificationsOff(openNotificationSettings) }
        }

        if (snapshot.pending.isNotEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) { SectionHeader(R.string.live_waiting_header) }
            items(snapshot.pending, key = { "pending-${it.packageName}" }, span = { StaggeredGridItemSpan.FullLine }) {
                PendingRequest(it)
            }
        }

        item(span = StaggeredGridItemSpan.FullLine) { SectionHeader(R.string.live_feeds_header) }
        if (tiles.isEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) { Hint(R.string.live_no_feeds) }
        }
        items(tiles, key = { "feed-${it.name}" }) { tile -> FeedTile(tile, onPreview = { previewed = tile.name }) }
    }
}

@Composable
private fun EmptyLive(notificationsOn: Boolean, openNotificationSettings: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun NotificationsOff(openSettings: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.notifications_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = openSettings) { Text(stringResource(R.string.notifications_turn_on)) }
    }
}

@Composable
private fun SectionHeader(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PendingRequest(row: PendingRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(row.app, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pending_wants, row.feeds.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
            Text(row.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (row.certificateChanged) {
                Text(
                    stringResource(R.string.cert_changed_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { Registry.instance.setAccess(row.packageName, Access.BLOCKED) }) {
                    Text(stringResource(R.string.action_block))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { Registry.instance.setAccess(row.packageName, Access.ALLOWED) }) {
                    Text(stringResource(R.string.action_allow))
                }
            }
        }
    }
}

@Composable
private fun FeedTile(tile: Tile, onPreview: () -> Unit) {
    val feed = tile.feed
    Card(
        onClick = onPreview,
        enabled = feed != null,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(tile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = if (feed == null) {
                stringResource(R.string.feed_not_announced)
            } else {
                buildList {
                    add(feed.app)
                    if (feed.width > 0 && feed.height > 0) add("${feed.width}x${feed.height}")
                    if (feed.fps > 0) add("${feed.fps} fps")
                    if (feed.hasAudio) add(stringResource(R.string.feed_audio))
                }.joinToString(" · ")
            }
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            if (tile.watchers.isEmpty()) {
                Text(stringResource(R.string.feed_no_watchers), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }
            tile.watchers.take(MAX_WATCHERS).forEach { Watcher(it) }
            if (tile.watchers.size > MAX_WATCHERS) {
                Text(
                    stringResource(R.string.feed_more_watchers, tile.watchers.size - MAX_WATCHERS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (feed != null) {
                Text(
                    stringResource(R.string.feed_tap_preview),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private const val MAX_WATCHERS = 3

@Composable
private fun Watcher(row: SubscriptionRow) {
    val status = when {
        row.access == Access.BLOCKED -> R.string.watch_short_blocked
        row.access == Access.ASK -> R.string.watch_short_pending
        row.live -> null
        else -> R.string.watch_short_waiting
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            row.receiverApp,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (status != null) {
            Text(
                stringResource(status),
                style = MaterialTheme.typography.labelSmall,
                color = if (row.access == Access.BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
