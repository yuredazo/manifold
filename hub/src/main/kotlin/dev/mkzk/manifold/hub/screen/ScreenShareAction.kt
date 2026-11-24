package dev.mkzk.manifold.hub.screen

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.ui.LiveShareButton

@Composable
internal fun ScreenShareAction(screenShare: ScreenShare, watchersOf: (feed: String?) -> Int, start: (withSound: Boolean) -> Unit) {
    val state by screenShare.state.collectAsStateWithLifecycle()
    val watchers = watchersOf(state.feed)
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val failed = stringResource(R.string.screen_failed)
    LaunchedEffect(state.failed) {
        if (state.failed) {
            Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
            screenShare.clearFailure()
        }
    }

    if (state.sharing) {
        val label = if (watchers > 0) stringResource(R.string.screen_live, watchers) else stringResource(R.string.screen_live_idle)
        LiveShareButton(Icons.AutoMirrored.Outlined.ScreenShare, watchers, label) { sheetOpen = true }
    } else {
        IconButton(onClick = { sheetOpen = true }) {
            Icon(Icons.AutoMirrored.Outlined.ScreenShare, stringResource(R.string.screen_share), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (sheetOpen) {
        ScreenShareSheet(
            screenShare = screenShare,
            state = state,
            watchers = watchers,
            onDismiss = { sheetOpen = false },
            start = { sound ->
                sheetOpen = false
                start(sound)
            },
        )
    }
}
