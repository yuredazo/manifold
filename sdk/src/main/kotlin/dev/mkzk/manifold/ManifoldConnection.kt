package dev.mkzk.manifold

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PatternMatcher
import android.os.RemoteException
import android.util.Log

private const val TAG = "Manifold"

/**
 * One binding to the hub that heals itself: if the hub is missing, updated,
 * force-stopped or killed, it keeps trying until the hub is back. The system
 * restarts a killed service by itself, but a force-stopped one is silently
 * dropped from every binding, so waiting for the system is not enough. While
 * the hub is not installed at all it polls rarely and instead listens for the
 * install, so a hub installed from inside the app is picked up at once.
 *
 * Everything, including the listener callbacks, runs on [worker], so callers
 * can use [IManifoldHub] without worrying about the main thread.
 *
 * One-shot: build it, [open] it, [close] it, and make a new one to start over.
 */
internal class ManifoldConnection(context: Context, private val listener: Listener) {

    interface Listener {
        fun onHubReady(hub: IManifoldHub)
        fun onHubLost()
    }

    private val appContext = context.applicationContext
    private val thread = HandlerThread("manifold-client").apply { start() }
    val worker = Handler(thread.looper)

    private var closed = false
    private var hub: IManifoldHub? = null
    private var retryDelayMs = RETRY_MIN_MS
    private var hubInstalled = true
    private var watchingInstalls = false
    private val retry = Runnable { connect() }

    private val installWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed || hub != null) return
            worker.removeCallbacks(retry)
            retryDelayMs = RETRY_MIN_MS
            connect()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            worker.post { hubConnected(service) }
        }

        // The system reconnects on its own once the hub process is back.
        override fun onServiceDisconnected(name: ComponentName) {
            worker.post { hubDisconnected() }
        }

        override fun onBindingDied(name: ComponentName) {
            worker.post { rebind() }
        }

        override fun onNullBinding(name: ComponentName) {
            worker.post { rebind() }
        }
    }

    fun open() {
        worker.post {
            closed = false
            connect()
        }
    }

    fun close() {
        worker.post {
            closed = true
            worker.removeCallbacks(retry)
            stopWatchingInstalls()
            unbind()
            hub = null
            thread.quitSafely()
        }
    }

    private fun connect() {
        if (closed) return
        unbind()
        val intent = Intent(Manifold.ACTION_BIND).setPackage(Manifold.HUB_PACKAGE)
        val bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "binding to the hub was refused", e)
            false
        }
        if (bound) {
            hubInstalled = true
            stopWatchingInstalls()
        } else {
            // No matching service: the hub is not installed (or not visible to us).
            unbind()
            hubInstalled = false
            watchForInstalls()
            scheduleRetry()
        }
    }

    private fun hubConnected(service: IBinder) {
        if (closed) return
        val candidate = IManifoldHub.Stub.asInterface(service)
        val version = try {
            candidate.protocolVersion()
        } catch (e: RemoteException) {
            Log.w(TAG, "hub went away while connecting", e)
            return
        }
        if (version != Manifold.PROTOCOL_VERSION) {
            Log.e(TAG, "hub speaks protocol $version, this app speaks ${Manifold.PROTOCOL_VERSION}")
            return
        }
        worker.removeCallbacks(retry)
        retryDelayMs = RETRY_MIN_MS
        stopWatchingInstalls()
        hub = candidate
        listener.onHubReady(candidate)
    }

    private fun hubDisconnected() {
        if (closed) return
        val wasConnected = hub != null
        hub = null
        if (wasConnected) listener.onHubLost()
        // If the system has not brought the hub back by then, bind again ourselves.
        scheduleRetry()
    }

    private fun rebind() {
        if (closed) return
        unbind()
        hubDisconnected()
    }

    private fun scheduleRetry() {
        worker.removeCallbacks(retry)
        worker.postDelayed(retry, retryDelayMs)
        val cap = if (hubInstalled) RETRY_MAX_MS else RETRY_MAX_NOT_INSTALLED_MS
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(cap)
    }

    /** Wakes the retry the moment the hub package is installed or updated. */
    private fun watchForInstalls() {
        if (watchingInstalls) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
            addDataSchemeSpecificPart(Manifold.HUB_PACKAGE, PatternMatcher.PATTERN_LITERAL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(installWatcher, filter, null, worker, Context.RECEIVER_EXPORTED)
        } else {
            appContext.registerReceiver(installWatcher, filter, null, worker)
        }
        watchingInstalls = true
    }

    private fun stopWatchingInstalls() {
        if (!watchingInstalls) return
        watchingInstalls = false
        try {
            appContext.unregisterReceiver(installWatcher)
        } catch (_: IllegalArgumentException) {
            // Already gone.
        }
    }

    private fun unbind() {
        try {
            appContext.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Was not bound.
        }
    }

    private companion object {
        const val RETRY_MIN_MS = 1_000L
        const val RETRY_MAX_MS = 30_000L

        /** With no hub installed there is nothing to find by polling; the install broadcast does the work. */
        const val RETRY_MAX_NOT_INSTALLED_MS = 300_000L
    }
}
