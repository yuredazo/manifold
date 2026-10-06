package dev.mkzk.manifold.hub.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import dev.mkzk.manifold.hub.broker.AccessBook
import dev.mkzk.manifold.hub.broker.Registry
import dev.mkzk.manifold.hub.network.KeystoreSealer
import dev.mkzk.manifold.hub.network.Network
import dev.mkzk.manifold.hub.network.loadIdentityKeys
import dev.mkzk.manifold.hub.network.protocol.DeviceBook
import dev.mkzk.manifold.hub.network.protocol.Identity
import dev.mkzk.manifold.hub.network.protocol.KeyPair
import dev.mkzk.manifold.hub.screen.ScreenShare
import dev.mkzk.manifold.hub.update.SessionInstaller
import dev.mkzk.manifold.hub.update.Updater
import java.io.Closeable

private const val BOOK_KEY = "book"
private const val PRIVATE_KEY = "private"
private const val DEVICES_KEY = "devices"
private const val CHECK_KEY = "update_check"

/** The one place that knows how the features are connected. Services and receivers get theirs through [hubGraph]. */
internal class HubGraph(app: Application) {
    val registry: Registry
    val network: Network
    val updater: Updater
    val screenShare: ScreenShare

    init {
        val access = app.getSharedPreferences("access", Context.MODE_PRIVATE)
        registry = Registry(
            book = AccessBook(access.getString(BOOK_KEY, null)) { access.edit().putString(BOOK_KEY, it).apply() },
            ownUid = Process.myUid(),
        )

        val net = app.getSharedPreferences("network", Context.MODE_PRIVATE)
        val name = Settings.Global.getString(app.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val wifi = app.getSystemService(WifiManager::class.java)
        network = Network(
            Identity(identityKeys(net), name),
            DeviceBook(net.getString(DEVICES_KEY, null)) { net.edit().putString(DEVICES_KEY, it).apply() },
            registry,
            multicast = { wifi?.let { holdMulticast(it) } },
        )

        val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        updater = Updater(
            currentVersion = version,
            installer = SessionInstaller(app),
            checkOnLaunch = settings.getBoolean(CHECK_KEY, true),
            saveCheckOnLaunch = { settings.edit().putBoolean(CHECK_KEY, it).apply() },
        )

        screenShare = ScreenShare(registry)
    }

    /** Wi-Fi chips often drop broadcast packets to save power, which would hide devices that announce for pairing. */
    private fun holdMulticast(wifi: WifiManager): Closeable {
        val lock = wifi.createMulticastLock("manifold:discovery").apply { setReferenceCounted(false) }
        lock.acquire()
        return Closeable { if (lock.isHeld) lock.release() }
    }

    /** The Keystore cannot hold X25519 keys before Android 13, so it seals the key instead of generating it. */
    private fun identityKeys(prefs: SharedPreferences): KeyPair =
        loadIdentityKeys({ prefs.getString(PRIVATE_KEY, null) }, { prefs.edit().putString(PRIVATE_KEY, it).apply() }, KeystoreSealer())
}

internal val Context.hubGraph: HubGraph get() = (applicationContext as HubApp).graph
