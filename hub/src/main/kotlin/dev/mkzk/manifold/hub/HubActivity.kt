package dev.mkzk.manifold.hub

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class HubActivity : ComponentActivity() {
    private var notificationsOn by mutableStateOf(true)

    private val askForNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshNotificationState() }

    private var shareWithSound = false

    // Sound is optional: a refusal still shares the picture.
    private val askForSound = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        shareWithSound = granted
        askForCapture()
    }

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val consent = result.data
        if (result.resultCode == RESULT_OK && consent != null) ScreenShareService.start(this, consent, shareWithSound)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission && savedInstanceState == null) askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        Updater.instance.checkOnLaunchIfWanted()
        NetworkService.restore(this)

        setContent {
            HubTheme { HubShell(notificationsOn, ::openNotificationSettings, ::startScreenShare) }
        }
    }

    private fun startScreenShare(withSound: Boolean) {
        shareWithSound = withSound
        val needsPermission = withSound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) askForSound.launch(Manifest.permission.RECORD_AUDIO) else askForCapture()
    }

    private fun askForCapture() {
        capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
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

@Composable
private fun HubTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Tab(val title: Int, val icon: ImageVector) {
    Live(R.string.tab_live, Icons.Outlined.Sensors),
    Apps(R.string.tab_apps, Icons.Outlined.Apps),
    Devices(R.string.tab_devices, Icons.Outlined.Devices),
    About(R.string.tab_about, Icons.Outlined.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubShell(notificationsOn: Boolean, openNotificationSettings: () -> Unit, startScreenShare: (Boolean) -> Unit) {
    val snapshot by Registry.instance.state.collectAsStateWithLifecycle()
    val network = Network.instance
    val networkState by network.state.collectAsStateWithLifecycle()
    val devices by network.devices.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            val ownPackage = LocalContext.current.packageName
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { ScreenShareAction(snapshot.senders.firstOrNull { it.packageName == ownPackage }?.watchers ?: 0, startScreenShare) },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            if (tab == Tab.Live && snapshot.pending.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text(snapshot.pending.size.toString()) } }) {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            } else {
                                Icon(tab.icon, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(tab.title)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (Tab.entries[selected]) {
            Tab.Live -> LiveScreen(snapshot, notificationsOn, openNotificationSettings, modifier)
            Tab.Apps -> AppsScreen(snapshot.apps, modifier)
            Tab.Devices -> DevicesScreen(network, networkState, devices, modifier)
            Tab.About -> AboutScreen(Updater.instance, modifier)
        }
    }
    UpdateAnnouncement(Updater.instance)
}
