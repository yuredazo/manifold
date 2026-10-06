package dev.mkzk.manifold.hub.network

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mkzk.manifold.hub.app.hubGraph
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.app.HubActivity
import dev.mkzk.manifold.hub.notify.ensureChannel

private const val CHANNEL = "network"
private const val ID = 2
private const val PREFS = "network"
private const val WANTED_KEY = "listening"

/**
 * Keeps the network side alive while the owner has it switched on. Android only lets an app hold a socket
 * in the background from a foreground service, hence the notification.
 */
class NetworkService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this, CHANNEL, getString(R.string.channel_network), NotificationManager.IMPORTANCE_LOW)
        val open = PendingIntent.getActivity(this, 0, Intent(this, HubActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(getString(R.string.network_notice_title))
            .setContentText(getString(R.string.network_notice_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: IllegalStateException) {
            // Android refuses a foreground service started from the background. The choice stays saved and is restored when the app opens.
            stopSelf()
            return
        }
        holdWifiAwake()
        hubGraph.network.start()
    }

    // Android restarts it after killing the app, so the switch stays on until the owner turns it off.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        hubGraph.network.stop()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    /**
     * Power saving can hold a packet for a hundred milliseconds while the radio wakes. Low latency mode prevents that, but only
     * while the app is in the foreground with the screen on; older versions only have high performance mode.
     */
    @Suppress("DEPRECATION")
    private fun holdWifiAwake() {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(mode, "manifold:network")
            ?.apply { setReferenceCounted(false) }
            ?.also { it.acquire() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            prefs(context).edit().putBoolean(WANTED_KEY, true).apply()
            ContextCompat.startForegroundService(context, Intent(context, NetworkService::class.java))
        }

        fun stop(context: Context) {
            prefs(context).edit().putBoolean(WANTED_KEY, false).apply()
            context.stopService(Intent(context, NetworkService::class.java))
        }

        fun restore(context: Context) {
            if (prefs(context).getBoolean(WANTED_KEY, false) && !context.hubGraph.network.state.value.listening) {
                ContextCompat.startForegroundService(context, Intent(context, NetworkService::class.java))
            }
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
