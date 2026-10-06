package dev.mkzk.manifold.hub.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.mkzk.manifold.hub.network.NetworkService
import dev.mkzk.manifold.hub.screen.ScreenShareRequest

class HubActivity : ComponentActivity() {
    private var notificationsOn by mutableStateOf(true)

    private val askForNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshNotificationState() }

    private val screenShareRequest = ScreenShareRequest(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission && savedInstanceState == null) askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        val graph = hubGraph
        graph.updater.checkOnLaunchIfWanted()
        NetworkService.restore(this)

        setContent {
            HubTheme { HubShell(graph, notificationsOn, ::openNotificationSettings, screenShareRequest::begin) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationState()
    }

    private fun refreshNotificationState() {
        notificationsOn = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }
}
