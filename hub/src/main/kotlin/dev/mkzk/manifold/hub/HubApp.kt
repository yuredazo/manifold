package dev.mkzk.manifold.hub

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import dev.mkzk.manifold.hub.net.DeviceBook
import dev.mkzk.manifold.hub.net.Identity
import dev.mkzk.manifold.hub.net.KeyPair

private const val BOOK_KEY = "book"
private const val PRIVATE_KEY = "private"
private const val DEVICES_KEY = "devices"
private const val CHECK_KEY = "update_check"

class HubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("access", MODE_PRIVATE)
        Registry.install(AccessBook(prefs.getString(BOOK_KEY, null)) { prefs.edit().putString(BOOK_KEY, it).apply() })

        val network = getSharedPreferences("network", MODE_PRIVATE)
        val name = Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL
        Network.install(
            Identity(identityKeys(network), name),
            DeviceBook(network.getString(DEVICES_KEY, null)) { network.edit().putString(DEVICES_KEY, it).apply() },
        )

        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        Updater.register(
            Updater(
                currentVersion = version,
                installer = SessionInstaller(this),
                checkOnLaunch = settings.getBoolean(CHECK_KEY, true),
                saveCheckOnLaunch = { settings.edit().putBoolean(CHECK_KEY, it).apply() },
            ),
        )
    }

    /** The Keystore cannot hold X25519 keys before Android 13, so it seals the key instead of generating it. */
    private fun identityKeys(prefs: SharedPreferences): KeyPair =
        loadIdentityKeys({ prefs.getString(PRIVATE_KEY, null) }, { prefs.edit().putString(PRIVATE_KEY, it).apply() }, KeystoreSealer())
}
