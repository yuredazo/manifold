package dev.mkzk.manifold

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "Manifold"

/** Unknown values count as pending, so a newer hub cannot open a door by accident. */
internal fun accessOf(wire: Int): ManifoldReceiver.Access = when (wire) {
    Manifold.ACCESS_ALLOWED -> ManifoldReceiver.Access.ALLOWED
    Manifold.ACCESS_BLOCKED -> ManifoldReceiver.Access.BLOCKED
    else -> ManifoldReceiver.Access.PENDING
}

/**
 * Lists announced senders and subscribes to their video, and optionally audio, surviving hub restarts. Callbacks run on
 * [callbackExecutor]. You keep the surface and audio pipe you pass in and release them after [unsubscribe].
 */
public class ManifoldReceiver @JvmOverloads constructor(
    context: Context,
    private val listener: Listener,
    private val callbackExecutor: Executor = MainThreadExecutor,
) {
    public enum class Access {
        /** Nobody has decided yet; nothing is delivered. */
        PENDING,

        ALLOWED,

        /** Nothing is delivered and the sender list is empty. */
        BLOCKED,
    }

    public interface Listener {
        /** Every announced sender, on each change. Empty while the hub is unreachable. */
        public fun onSenders(senders: List<SenderInfo>)

        public fun onConnectionChanged(connected: Boolean) {}

        /** The hub refused this receiver, for example because it has too many. */
        public fun onRegistrationRefused() {}

        /** The hub refused a subscription, for example because this app has too many. */
        public fun onSubscriptionRefused(subscription: Subscription) {}

        /** Where the owner stands on this app. Use it to tell the user to approve the app in Manifold. */
        public fun onAccessChanged(subscription: Subscription, access: Access) {}
    }

    /** One watched sender. Stays valid across hub restarts until [unsubscribe]. */
    public class Subscription internal constructor(
        public val senderName: String,
        internal val surface: Surface,
        internal val width: Int,
        internal val height: Int,
        internal val audioSink: ParcelFileDescriptor?,
    ) {
        internal var hubId: String? = null

        override fun toString(): String = "Subscription(sender=$senderName, ${width}x$height, audio=${audioSink != null})"
    }

    private enum class State { NEW, STARTED, STOPPED }

    private val state = AtomicReference(State.NEW)
    private var hub: IManifoldHub? = null
    private val wanted = LinkedHashSet<Subscription>()

    private val callback = object : IManifoldReceiver.Stub() {
        override fun onSenders(senders: List<SenderInfo>) {
            callbackExecutor.execute { listener.onSenders(senders) }
        }

        override fun onAccess(subscriptionId: String, access: Int) {
            // Posting to the worker means hubId is set by the time this runs.
            connection.worker.post {
                val subscription = wanted.firstOrNull { it.hubId == subscriptionId } ?: return@post
                callbackExecutor.execute { listener.onAccessChanged(subscription, accessOf(access)) }
            }
        }
    }

    private val connection: ManifoldConnection = ManifoldConnection(context, object : ManifoldConnection.Listener {
        override fun onHubReady(hub: IManifoldHub) {
            this@ManifoldReceiver.hub = hub
            try {
                hub.registerReceiver(callback)
            } catch (e: RemoteException) {
                Log.w(TAG, "register failed", e)
                return
            } catch (e: RuntimeException) {
                Log.e(TAG, "hub refused the receiver", e)
                callbackExecutor.execute { listener.onRegistrationRefused() }
                return
            }
            wanted.forEach { issue(hub, it) }
            callbackExecutor.execute { listener.onConnectionChanged(true) }
        }

        override fun onHubLost() {
            hub = null
            wanted.forEach { it.hubId = null }
            callbackExecutor.execute {
                listener.onSenders(emptyList())
                listener.onConnectionChanged(false)
            }
        }
    })

    /** Connects to the hub. Throws [IllegalStateException] if called twice. */
    public fun start() {
        check(state.compareAndSet(State.NEW, State.STARTED)) { "start() can only be called once" }
        connection.open()
    }

    /**
     * Watches [senderName], which may not be running yet. The sender draws into [surface]; with an
     * [audioSink] it also writes [Manifold.AUDIO_SAMPLE_RATE] Hz stereo 16-bit PCM.
     */
    @JvmOverloads
    public fun subscribe(
        senderName: String,
        surface: Surface,
        width: Int,
        height: Int,
        audioSink: ParcelFileDescriptor? = null,
    ): Subscription {
        check(state.get() != State.STOPPED) { "the receiver is stopped" }
        require(Manifold.isValidName(senderName)) { "invalid sender name" }
        require(surface.isValid) { "the surface is not valid" }
        require(width in 1..Manifold.MAX_DIMENSION && height in 1..Manifold.MAX_DIMENSION) {
            "width and height must be between 1 and ${Manifold.MAX_DIMENSION}"
        }
        val subscription = Subscription(senderName.trim(), surface, width, height, audioSink)
        connection.worker.post {
            wanted.add(subscription)
            hub?.let { issue(it, subscription) }
        }
        return subscription
    }

    public fun unsubscribe(subscription: Subscription) {
        connection.worker.post {
            wanted.remove(subscription)
            val id = subscription.hubId ?: return@post
            subscription.hubId = null
            try {
                hub?.unsubscribe(id)
            } catch (e: RemoteException) {
                Log.w(TAG, "unsubscribe failed", e)
            }
        }
    }

    /** Disconnects and drops every subscription. Safe to call more than once. */
    public fun stop() {
        if (state.getAndSet(State.STOPPED) == State.STOPPED) return
        connection.worker.post {
            try {
                hub?.unregisterReceiver(callback)
            } catch (e: RemoteException) {
                Log.w(TAG, "unregister failed", e)
            }
        }
        connection.close()
    }

    override fun toString(): String = "ManifoldReceiver(state=${state.get()})"

    private fun issue(hub: IManifoldHub, subscription: Subscription) {
        val id = try {
            hub.subscribe(
                callback,
                subscription.senderName,
                subscription.surface,
                subscription.width,
                subscription.height,
                subscription.audioSink,
            )
        } catch (e: RemoteException) {
            // The hub died mid-call. It is not a refusal: the subscription is issued again when the hub is
            // back.
            Log.w(TAG, "subscribe failed", e)
            return
        } catch (e: RuntimeException) {
            Log.e(TAG, "hub refused the subscription to '${subscription.senderName}'", e)
            null
        }
        subscription.hubId = id
        if (id == null) {
            callbackExecutor.execute { listener.onSubscriptionRefused(subscription) }
        }
    }
}
