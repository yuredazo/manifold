package dev.mkzk.manifold.hub

import android.os.ParcelFileDescriptor
import android.view.Surface
import dev.mkzk.manifold.Manifold
import dev.mkzk.manifold.SenderInfo
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class Owner(val uid: Int, val packageName: String, val label: String, val certSha256: String = "")

internal interface SenderLink {
    val key: Any
    fun deliver(subscriptionId: String, surface: Surface?, width: Int, height: Int, audioSink: ParcelFileDescriptor?)
    fun revoke(subscriptionId: String)
}

internal interface ReceiverLink {
    val key: Any
    fun sendersChanged(senders: List<SenderInfo>)
    fun accessChanged(subscriptionId: String, access: Access)
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

/** Frames never touch it. The hub's own code ([ownUid]) is not asked for permission, and a subscription outlives its sender. */
internal class Registry(
    private val limits: Limits = Limits(),
    private val book: AccessBook = AccessBook(null) {},
    private val ownUid: Int = -1,
) {

    private class SenderEntry(val link: SenderLink, var announced: SenderInfo, val owner: Owner)

    private class ReceiverEntry(val link: ReceiverLink, val owner: Owner)

    private class SubscriptionEntry(
        val id: String,
        val receiver: ReceiverEntry,
        val senderName: String,
        val surface: Surface?,
        val width: Int,
        val height: Int,
        val audioSink: ParcelFileDescriptor?,
        var access: Access,
    ) {
        var delivered = false
    }

    private val lock = Any()
    private val senders = LinkedHashMap<Any, SenderEntry>()
    private val receivers = LinkedHashMap<Any, ReceiverEntry>()
    private val subscriptions = LinkedHashMap<String, SubscriptionEntry>()

    private val snapshot = MutableStateFlow(Snapshot(emptyList(), emptyList(), emptyList(), emptyList()))
    val state: StateFlow<Snapshot> = snapshot

    companion object {
        lateinit var instance: Registry
            private set

        fun install(book: AccessBook) {
            instance = Registry(book = book, ownUid = android.os.Process.myUid())
        }
    }

    fun addSender(link: SenderLink, requested: SenderInfo, owner: Owner): String? = synchronized(lock) {
        val base = cleanName(requested.name) ?: return null

        senders.remove(link.key)?.let(::detach)
        // An app announcing the name it already holds is a restart, not a clash.
        senders.values.firstOrNull { it.owner.uid == owner.uid && it.announced.name == base }?.let {
            senders.remove(it.link.key)
            detach(it)
        }
        if (senders.size >= limits.maxSenders) return null

        val entry = SenderEntry(link, describe(requested, uniqueName(base), owner), owner)
        senders[link.key] = entry
        subscriptions.values
            .filter { it.senderName == entry.announced.name && !it.delivered && it.access == Access.ALLOWED }
            .forEach { deliver(entry, it) }
        publishSenders()
        entry.announced.name
    }

    fun updateSender(key: Any, requested: SenderInfo, uid: Int) {
        synchronized(lock) {
            val entry = senders[key] ?: return
            if (entry.owner.uid != uid) return
            entry.announced = describe(requested, entry.announced.name, entry.owner)
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

    fun addReceiver(link: ReceiverLink, owner: Owner): Boolean = synchronized(lock) {
        dropReceiver(link.key)
        if (receivers.size >= limits.maxReceivers) return false
        if (owner.uid != ownUid) book.see(owner)
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

        val access = if (receiver.owner.uid == ownUid) Access.ALLOWED else book.decide(receiver.owner)
        val entry = SubscriptionEntry(
            id = UUID.randomUUID().toString(),
            receiver = receiver,
            senderName = name,
            surface = surface,
            width = width.coerceIn(1, limits.maxDimension),
            height = height.coerceIn(1, limits.maxDimension),
            audioSink = audioSink,
            access = access,
        )
        subscriptions[entry.id] = entry
        if (access == Access.ALLOWED) senderNamed(name)?.let { deliver(it, entry) }
        receiver.link.accessChanged(entry.id, access)
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

    /** Applies at once: a blocked app loses what it had, an allowed app gets what was waiting. */
    fun setAccess(packageName: String, access: Access) {
        synchronized(lock) {
            book.set(packageName, access)
            receivers.values.filter { it.owner.packageName == packageName }.forEach { receiver ->
                val now = book.decide(receiver.owner)
                subscriptions.values.filter { it.receiver === receiver }.forEach { apply(it, now) }
            }
            publishSenders()
        }
    }

    fun forget(packageName: String) {
        synchronized(lock) {
            book.remove(packageName)
            receivers.values.filter { it.owner.packageName == packageName }.forEach { receiver ->
                subscriptions.values.filter { it.receiver === receiver }.forEach { apply(it, Access.ASK) }
            }
            publishSenders()
        }
    }

    private fun apply(subscription: SubscriptionEntry, access: Access) {
        subscription.access = access
        if (access == Access.ALLOWED && !subscription.delivered) {
            senderNamed(subscription.senderName)?.let { deliver(it, subscription) }
        } else if (access != Access.ALLOWED && subscription.delivered) {
            senderNamed(subscription.senderName)?.link?.revoke(subscription.id)
            subscription.delivered = false
        }
        subscription.receiver.link.accessChanged(subscription.id, access)
    }

    private fun deliver(sender: SenderEntry, subscription: SubscriptionEntry) {
        sender.link.deliver(subscription.id, subscription.surface, subscription.width, subscription.height, subscription.audioSink)
        subscription.delivered = true
    }

    /** Not revoked, since the sender is gone: its subscriptions go back to waiting. */
    private fun detach(sender: SenderEntry) {
        subscriptions.values
            .filter { it.senderName == sender.announced.name && it.delivered }
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

    private fun senderNamed(name: String) = senders.values.firstOrNull { it.announced.name == name }

    private fun cleanName(raw: String?): String? {
        val name = raw?.trim() ?: return null
        if (!Manifold.isValidName(name) || name.length > limits.maxNameLength) return null
        return name
    }

    private fun uniqueName(base: String): String {
        val taken = senders.values.mapTo(HashSet()) { it.announced.name }
        if (base !in taken) return base
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val candidate = base.take(limits.maxNameLength - suffix.length) + suffix
            if (candidate !in taken) return candidate
            n++
        }
    }

    private fun describe(raw: SenderInfo, name: String, owner: Owner) = SenderInfo().also {
        it.name = name
        it.label = owner.label
        it.packageName = owner.packageName
        it.width = raw.width.coerceIn(0, limits.maxDimension)
        it.height = raw.height.coerceIn(0, limits.maxDimension)
        it.fps = raw.fps.coerceIn(0, limits.maxFps)
        it.hasAudio = raw.hasAudio
        it.soundOnly = raw.soundOnly && raw.hasAudio
    }

    /** A blocked app is not told what is running either. */
    private fun publishSenders() {
        val list = senders.values.map { describe(it.announced, it.announced.name, it.owner) }
        receivers.values.forEach {
            it.link.sendersChanged(if (book.decide(it.owner) == Access.BLOCKED) emptyList() else list)
        }
        publishState()
    }

    private fun publishState() {
        val connected = receivers.values.map { it.owner.packageName }.toSet()
        snapshot.value = Snapshot(
            senders = senders.values.map { s ->
                SenderRow(
                    name = s.announced.name,
                    app = s.owner.label,
                    packageName = s.owner.packageName,
                    width = s.announced.width,
                    height = s.announced.height,
                    fps = s.announced.fps,
                    hasAudio = s.announced.hasAudio,
                    watchers = subscriptions.values.count { it.senderName == s.announced.name && it.delivered },
                    soundOnly = s.announced.soundOnly,
                )
            },
            subscriptions = subscriptions.values.map {
                SubscriptionRow(it.receiver.owner.label, it.senderName, it.width, it.height, it.access, it.delivered)
            },
            pending = subscriptions.values
                .filter { it.access == Access.ASK }
                .groupBy { it.receiver.owner }
                .map { (owner, subs) ->
                    PendingRow(
                        packageName = owner.packageName,
                        app = owner.label,
                        feeds = subs.map { it.senderName }.distinct(),
                        certificateChanged = book.apps.value.firstOrNull { it.packageName == owner.packageName }?.certificateChanged == true,
                    )
                },
            apps = book.apps.value.map { a ->
                AppRow(
                    packageName = a.packageName,
                    app = a.label,
                    access = a.access,
                    certificateChanged = a.certificateChanged,
                    connected = a.packageName in connected,
                    subscriptions = subscriptions.values.count { it.receiver.owner.packageName == a.packageName },
                )
            },
        )
    }
}
