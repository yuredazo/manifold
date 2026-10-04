package dev.mkzk.manifold

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "Manifold"

/**
 * Watches Manifold senders: delivers the live list of them and subscribes to
 * their video, and optionally audio.
 *
 * A subscription survives hub restarts and a sender that is not running yet:
 * it is re-issued when the hub is back and delivered when the sender appears.
 * The receiver keeps ownership of the surface and audio pipe it passes in and
 * releases them after [unsubscribe]. Methods can be called from any thread.
 * Listener methods run on [callbackExecutor], which is the main thread by
 * default. Start it once and always stop it.
 */
public class ManifoldReceiver @JvmOverloads constructor(
    context: Context,
    private val listener: Listener,
    private val callbackExecutor: Executor = MainThreadExecutor,
) {
    public interface Listener {
        /**
         * Every sender that is announced right now, sent on each change. Each
         * [SenderInfo.name] is unique; [SenderInfo.label] and
         * [SenderInfo.packageName] come from the system, not from the sender.
         * The list is empty while the hub is unreachable.
         */
        public fun onSenders(senders: List<SenderInfo>)

        public fun onConnectionChanged(connected: Boolean) {}

        /** The hub turned this receiver down, for example because it already has too many. */
        public fun onRegistrationRefused() {}

        /** The hub turned a subscription down, for example because this app already has too many. */
        public fun onSubscriptionRefused(subscription: Subscription) {}
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
     * Watches [senderName], which may not be running yet. The sender renders
     * into [surface] at [width] x [height] and, if [audioSink] is given, writes
     * [Manifold.AUDIO_SAMPLE_RATE] Hz stereo 16-bit PCM into it.
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

    private fun issue(hub: IManifoldHub, subscription: Subscription) {
        subscription.hubId = try {
            hub.subscribe(
                callback,
                subscription.senderName,
                subscription.surface,
                subscription.width,
                subscription.height,
                subscription.audioSink,
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "subscribe failed", e)
            null
        } catch (e: RuntimeException) {
            Log.e(TAG, "hub refused the subscription to '${subscription.senderName}'", e)
            null
        }
        if (subscription.hubId == null) {
            callbackExecutor.execute { listener.onSubscriptionRefused(subscription) }
        }
    }
}
