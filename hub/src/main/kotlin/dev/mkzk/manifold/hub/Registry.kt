package dev.mkzk.manifold.hub

import android.os.ParcelFileDescriptor
import android.view.Surface
import dev.mkzk.manifold.Manifold
import dev.mkzk.manifold.SenderInfo
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Who is on the other end of a Binder call, as the system sees them. */
internal data class Owner(val uid: Int, val packageName: String, val label: String)

/** The hub's way to reach a sender, whatever sits behind it. */
internal interface SenderLink {
    val key: Any
    fun deliver(subscriptionId: String, surface: Surface?, width: Int, height: Int, audioSink: ParcelFileDescriptor?)
    fun revoke(subscriptionId: String)
}

internal interface ReceiverLink {
    val key: Any
    fun sendersChanged(senders: List<SenderInfo>)
}

internal data class Limits(
    val maxSenders: Int = 32,
    val maxReceivers: Int = 16,
    val maxSubscriptionsPerApp: Int = 32,
    val maxNameLength: Int = Manifold.MAX_NAME_LENGTH,
    val maxDimension: Int = Manifold.MAX_DIMENSION,
    val maxFps: Int = Manifold.MAX_FPS,
)

internal data class SenderRow(
    val name: String,
    val app: String,
    val packageName: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hasAudio: Boolean,
    val watchers: Int,
)

internal data class ReceiverRow(val app: String, val packageName: String, val subscriptions: Int)

internal data class SubscriptionRow(
    val receiverApp: String,
    val senderName: String,
    val width: Int,
    val height: Int,
    val live: Boolean,
)

internal data class Snapshot(
    val senders: List<SenderRow>,
    val receivers: List<ReceiverRow>,
    val subscriptions: List<SubscriptionRow>,
)

/**
 * Who is announced, who is watching, and which watcher is wired to which
 * sender. Pure bookkeeping: it never touches frames, only passes along the
 * surface and audio pipe a receiver provided.
 *
 * A subscription outlives its sender. If the sender goes away it waits, and
 * the same surface is delivered to the next sender that announces that name.
 */
internal class Registry(private val limits: Limits = Limits()) {

    private class SenderEntry(val link: SenderLink, var info: SenderInfo, val owner: Owner)

    private class ReceiverEntry(val link: ReceiverLink, val owner: Owner)

    private class SubscriptionEntry(
        val id: String,
        val receiver: ReceiverEntry,
        val senderName: String,
        val surface: Surface?,
        val width: Int,
        val height: Int,
        val audioSink: ParcelFileDescriptor?,
    ) {
        var delivered = false
    }

    private val lock = Any()
    private val senders = LinkedHashMap<Any, SenderEntry>()
    private val receivers = LinkedHashMap<Any, ReceiverEntry>()
    private val subscriptions = LinkedHashMap<String, SubscriptionEntry>()

    private val snapshot = MutableStateFlow(Snapshot(emptyList(), emptyList(), emptyList()))
    val state: StateFlow<Snapshot> = snapshot

    companion object {
        /** The registry the service and the status screen share. */
        val instance = Registry()
    }

    /** Returns the name the sender was registered under, or null if it was refused. */
    fun addSender(link: SenderLink, requested: SenderInfo, owner: Owner): String? = synchronized(lock) {
        val base = cleanName(requested.name) ?: return null

        senders.remove(link.key)?.let(::detach)
        // An app announcing the name it already holds is a restart, not a clash.
        senders.values.firstOrNull { it.owner.uid == owner.uid && it.info.name == base }?.let {
            senders.remove(it.link.key)
            detach(it)
        }
        if (senders.size >= limits.maxSenders) return null

        val entry = SenderEntry(link, describe(requested, uniqueName(base), owner), owner)
        senders[link.key] = entry
        subscriptions.values
            .filter { it.senderName == entry.info.name && !it.delivered }
            .forEach { deliver(entry, it) }
        publishSenders()
        entry.info.name
    }

    fun updateSender(key: Any, requested: SenderInfo, uid: Int) {
        synchronized(lock) {
            val entry = senders[key] ?: return
            if (entry.owner.uid != uid) return
            entry.info = describe(requested, entry.info.name, entry.owner)
            publishSenders()
        }
    }

    fun removeSender(key: Any, uid: Int? = null) {
        synchronized(lock) {
            val entry = senders[key] ?: return
            if (uid != null && entry.owner.uid != uid) return
            senders.remove(key)
            detach(entry)
            publishSenders()
        }
    }

    /** Returns false if the receiver was refused. */
    fun addReceiver(link: ReceiverLink, owner: Owner): Boolean = synchronized(lock) {
        dropReceiver(link.key)
        if (receivers.size >= limits.maxReceivers) return false
        receivers[link.key] = ReceiverEntry(link, owner)
        publishSenders()
        true
    }

    fun removeReceiver(key: Any, uid: Int? = null) {
        synchronized(lock) {
            val entry = receivers[key] ?: return
            if (uid != null && entry.owner.uid != uid) return
            dropReceiver(key)
            publishState()
        }
    }

    /** Returns the subscription id, or null if it was refused. */
    fun subscribe(
        receiverKey: Any,
        uid: Int,
        senderName: String,
        surface: Surface?,
        width: Int,
        height: Int,
        audioSink: ParcelFileDescriptor?,
    ): String? = synchronized(lock) {
        val receiver = receivers[receiverKey] ?: return null
        if (receiver.owner.uid != uid) return null
        val name = cleanName(senderName) ?: return null
        if (subscriptions.values.count { it.receiver.owner.uid == uid } >= limits.maxSubscriptionsPerApp) return null

        val entry = SubscriptionEntry(
            id = UUID.randomUUID().toString(),
            receiver = receiver,
            senderName = name,
            surface = surface,
            width = width.coerceIn(1, limits.maxDimension),
            height = height.coerceIn(1, limits.maxDimension),
            audioSink = audioSink,
        )
        subscriptions[entry.id] = entry
        senderNamed(name)?.let { deliver(it, entry) }
        publishState()
        entry.id
    }

    fun unsubscribe(id: String, uid: Int) {
        synchronized(lock) {
            val entry = subscriptions[id] ?: return
            if (entry.receiver.owner.uid != uid) return
            drop(entry)
            publishState()
        }
    }

    private fun deliver(sender: SenderEntry, subscription: SubscriptionEntry) {
        sender.link.deliver(subscription.id, subscription.surface, subscription.width, subscription.height, subscription.audioSink)
        subscription.delivered = true
    }

    /** The sender is gone: its subscriptions go back to waiting for the next one. */
    private fun detach(sender: SenderEntry) {
        subscriptions.values
            .filter { it.senderName == sender.info.name && it.delivered }
            .forEach { it.delivered = false }
    }

    private fun drop(subscription: SubscriptionEntry) {
        subscriptions.remove(subscription.id)
        if (subscription.delivered) senderNamed(subscription.senderName)?.link?.revoke(subscription.id)
        subscription.surface?.release()
        subscription.audioSink?.close()
    }

    private fun dropReceiver(key: Any) {
        val entry = receivers.remove(key) ?: return
        subscriptions.values.filter { it.receiver === entry }.forEach(::drop)
    }

    private fun senderNamed(name: String) = senders.values.firstOrNull { it.info.name == name }

    private fun cleanName(raw: String?): String? {
        val name = raw?.trim() ?: return null
        if (!Manifold.isValidName(name) || name.length > limits.maxNameLength) return null
        return name
    }

    private fun uniqueName(base: String): String {
        val taken = senders.values.mapTo(HashSet()) { it.info.name }
        if (base !in taken) return base
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val candidate = base.take(limits.maxNameLength - suffix.length) + suffix
            if (candidate !in taken) return candidate
            n++
        }
    }

    /** What receivers see: the announced numbers kept in range, identity from the system. */
    private fun describe(raw: SenderInfo, name: String, owner: Owner) = SenderInfo().also {
        it.name = name
        it.label = owner.label
        it.packageName = owner.packageName
        it.width = raw.width.coerceIn(0, limits.maxDimension)
        it.height = raw.height.coerceIn(0, limits.maxDimension)
        it.fps = raw.fps.coerceIn(0, limits.maxFps)
        it.hasAudio = raw.hasAudio
    }

    private fun publishSenders() {
        val list = senders.values.map { describe(it.info, it.info.name, it.owner) }
        receivers.values.forEach { it.link.sendersChanged(list) }
        publishState()
    }

    private fun publishState() {
        snapshot.value = Snapshot(
            senders = senders.values.map { s ->
                SenderRow(
                    name = s.info.name,
                    app = s.owner.label,
                    packageName = s.owner.packageName,
                    width = s.info.width,
                    height = s.info.height,
                    fps = s.info.fps,
                    hasAudio = s.info.hasAudio,
                    watchers = subscriptions.values.count { it.senderName == s.info.name && it.delivered },
                )
            },
            receivers = receivers.values.map { r ->
                ReceiverRow(r.owner.label, r.owner.packageName, subscriptions.values.count { it.receiver === r })
            },
            subscriptions = subscriptions.values.map {
                SubscriptionRow(it.receiver.owner.label, it.senderName, it.width, it.height, it.delivered)
            },
        )
    }
}
