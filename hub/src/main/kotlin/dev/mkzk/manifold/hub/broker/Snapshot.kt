package dev.mkzk.manifold.hub.broker

internal data class SenderRow(
    val name: String,
    val app: String,
    val packageName: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hasAudio: Boolean,
    val watchers: Int,
    val soundOnly: Boolean = false,
)

internal data class SubscriptionRow(
    val receiverApp: String,
    val senderName: String,
    val width: Int,
    val height: Int,
    val access: Access,
    val live: Boolean,
)

internal data class PendingRow(
    val packageName: String,
    val app: String,
    val feeds: List<String>,
    val certificateChanged: Boolean,
)

internal data class AppRow(
    val packageName: String,
    val app: String,
    val access: Access,
    val certificateChanged: Boolean,
    val connected: Boolean,
    val subscriptions: Int,
)

internal data class Snapshot(
    val senders: List<SenderRow>,
    val subscriptions: List<SubscriptionRow>,
    val pending: List<PendingRow>,
    val apps: List<AppRow>,
)
