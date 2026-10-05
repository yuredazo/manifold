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
 * Publishes a video feed, and optionally audio. Draw only between [Listener.onSubscribe] and [Listener.onUnsubscribe], and
 * release each subscription's surface and audio pipe when it ends. Callbacks run on [callbackExecutor].
 */
public class ManifoldSender @JvmOverloads constructor(
    context: Context,
    config: Config,
    private val listener: Listener,
    private val callbackExecutor: Executor = MainThreadExecutor,
) {
    /**
     * What the feed announces about itself. A [width] or [height] of 0 means "no preference". A [soundOnly] feed has no
     * picture: it needs [hasAudio], its subscribers' surfaces stay empty, and receivers that understand it play only the sound.
     */
    public class Config @JvmOverloads constructor(
        public val name: String,
        public val width: Int = 0,
        public val height: Int = 0,
        public val fps: Int = 0,
        public val hasAudio: Boolean = false,
        public val soundOnly: Boolean = false,
    ) {
        init {
            require(Manifold.isValidName(name)) {
                "name must be 1 to ${Manifold.MAX_NAME_LENGTH} characters without control characters"
            }
            require(width in 0..Manifold.MAX_DIMENSION && height in 0..Manifold.MAX_DIMENSION) {
                "width and height must be between 0 and ${Manifold.MAX_DIMENSION}"
            }
            require(fps in 0..Manifold.MAX_FPS) { "fps must be between 0 and ${Manifold.MAX_FPS}" }
            require(!soundOnly || hasAudio) { "a sound-only feed needs hasAudio" }
        }

        @JvmOverloads
        public fun copy(
            name: String = this.name,
            width: Int = this.width,
            height: Int = this.height,
            fps: Int = this.fps,
            hasAudio: Boolean = this.hasAudio,
            soundOnly: Boolean = this.soundOnly,
        ): Config = Config(name, width, height, fps, hasAudio, soundOnly)

        override fun equals(other: Any?): Boolean =
            other is Config && name == other.name && width == other.width && height == other.height &&
                fps == other.fps && hasAudio == other.hasAudio && soundOnly == other.soundOnly

        override fun hashCode(): Int {
            var hash = name.hashCode()
            hash = 31 * hash + width
            hash = 31 * hash + height
            hash = 31 * hash + fps
            hash = 31 * hash + hasAudio.hashCode()
            hash = 31 * hash + soundOnly.hashCode()
            return hash
        }

        override fun toString(): String =
            "Config(name=$name, width=$width, height=$height, fps=$fps, hasAudio=$hasAudio, soundOnly=$soundOnly)"
    }

    public class Subscription internal constructor(
        public val id: String,
        public val surface: Surface,
        public val width: Int,
        public val height: Int,
        /**
         * Write [Manifold.AUDIO_SAMPLE_RATE] Hz stereo 16-bit PCM here, or null if the receiver wants no
         * audio.
         */
        public val audioSink: ParcelFileDescriptor?,
    ) {
        override fun toString(): String = "Subscription(id=$id, ${width}x$height, audio=${audioSink != null})"
    }

    public interface Listener {
        /**
         * The hub accepted the feed as [registeredName], which has a suffix if another app holds the name.
         */
        public fun onRegistered(registeredName: String) {}

        /** The hub refused the feed, for example because it has too many senders. */
        public fun onRegistrationRefused() {}

        /** The hub is unreachable. Existing subscriptions have ended; the sender reconnects by itself. */
        public fun onHubLost() {}

        public fun onSubscribe(subscription: Subscription)

        public fun onUnsubscribe(subscriptionId: String)
    }

    private enum class State { NEW, STARTED, STOPPED }

    @Volatile private var config: Config = config
    @Volatile private var hub: IManifoldHub? = null
    private val state = AtomicReference(State.NEW)
    private val activeIds = LinkedHashSet<String>()

    private val callback = object : IManifoldSender.Stub() {
        override fun onSubscribe(
            subscriptionId: String,
            surface: Surface,
            width: Int,
            height: Int,
            audioSink: ParcelFileDescriptor?,
        ) {
            // Going through the worker keeps "ended" to exactly one report per subscription.
            connection.worker.post {
                activeIds.add(subscriptionId)
                callbackExecutor.execute {
                    listener.onSubscribe(Subscription(subscriptionId, surface, width, height, audioSink))
                }
            }
        }

        override fun onUnsubscribe(subscriptionId: String) {
            connection.worker.post {
                if (activeIds.remove(subscriptionId)) {
                    callbackExecutor.execute { listener.onUnsubscribe(subscriptionId) }
                }
            }
        }
    }

    private val connection: ManifoldConnection = ManifoldConnection(context, object : ManifoldConnection.Listener {
        override fun onHubReady(hub: IManifoldHub) {
            this@ManifoldSender.hub = hub
            val name = try {
                hub.registerSender(info(), callback)
            } catch (e: RemoteException) {
                Log.w(TAG, "register failed", e)
                return
            } catch (e: RuntimeException) {
                Log.e(TAG, "hub refused the sender", e)
                null
            }
            if (name != null) {
                callbackExecutor.execute { listener.onRegistered(name) }
            } else {
                callbackExecutor.execute { listener.onRegistrationRefused() }
            }
        }

        override fun onHubLost() {
            hub = null
            val ended = activeIds.toList()
            activeIds.clear()
            callbackExecutor.execute {
                ended.forEach { listener.onUnsubscribe(it) }
                listener.onHubLost()
            }
        }
    })

    /** Announces the feed. Throws [IllegalStateException] if called twice. */
    public fun start() {
        check(state.compareAndSet(State.NEW, State.STARTED)) { "start() can only be called once" }
        connection.open()
    }

    /** Changes the announced size, frame rate or audio flag; the name cannot change. */
    public fun update(config: Config) {
        check(state.get() == State.STARTED) { "the sender is not running" }
        require(config.name == this.config.name) { "the name of a running sender cannot change" }
        this.config = config
        connection.worker.post {
            val current = hub ?: return@post
            try {
                current.updateSender(info(), callback)
            } catch (e: RemoteException) {
                Log.w(TAG, "update failed", e)
            }
        }
    }

    /**
     * Unannounces the feed. Subscriptions still running end with [Listener.onUnsubscribe]. Safe to call
     * more than once.
     */
    public fun stop() {
        if (state.getAndSet(State.STOPPED) == State.STOPPED) return
        connection.worker.post {
            try {
                hub?.unregisterSender(callback)
            } catch (e: RemoteException) {
                Log.w(TAG, "unregister failed", e)
            }
            val ended = activeIds.toList()
            activeIds.clear()
            callbackExecutor.execute { ended.forEach { listener.onUnsubscribe(it) } }
        }
        connection.close()
    }

    override fun toString(): String = "ManifoldSender(name=${config.name}, state=${state.get()})"

    private fun info() = SenderInfo().also {
        val c = config
        it.name = c.name
        it.width = c.width
        it.height = c.height
        it.fps = c.fps
        it.hasAudio = c.hasAudio
        it.soundOnly = c.soundOnly
    }
}
