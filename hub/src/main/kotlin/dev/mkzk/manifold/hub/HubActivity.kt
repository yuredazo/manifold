package dev.mkzk.manifold.hub

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Shows what is connected to the hub right now; it has no controls. */
class HubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HubTheme { HubScreen() }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubScreen() {
    val snapshot by Registry.instance.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Manifold") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle("Senders") }
            if (snapshot.senders.isEmpty()) item { Empty("No sender is announced") }
            items(snapshot.senders, key = { "sender-${it.name}" }) { sender ->
                Entry(
                    title = sender.name,
                    detail = buildString {
                        append(sender.app)
                        if (sender.width > 0 && sender.height > 0) append("  ${sender.width}x${sender.height}")
                        if (sender.fps > 0) append("  ${sender.fps} fps")
                        if (sender.hasAudio) append("  audio")
                        append("  ${sender.watchers} watching")
                    },
                )
            }

            item { SectionTitle("Receivers") }
            if (snapshot.receivers.isEmpty()) item { Empty("No receiver is connected") }
            items(snapshot.receivers, key = { "receiver-${it.packageName}" }) { receiver ->
                Entry(title = receiver.app, detail = if (receiver.subscriptions == 1) "1 subscription" else "${receiver.subscriptions} subscriptions")
            }

            item { SectionTitle("Subscriptions") }
            if (snapshot.subscriptions.isEmpty()) item { Empty("Nothing is being watched") }
            items(snapshot.subscriptions) { subscription ->
                Entry(
                    title = "${subscription.receiverApp}  ->  ${subscription.senderName}",
                    detail = "${subscription.width}x${subscription.height}  " +
                        if (subscription.live) "live" else "waiting for the sender",
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Entry(title: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
