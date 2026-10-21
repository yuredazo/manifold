package dev.mkzk.manifold.hub.screen

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.ui.ControlShape

@Composable
internal fun ScreenShareAction(screenShare: ScreenShare, watchers: Int, start: (withSound: Boolean) -> Unit) {
    val state by screenShare.state.collectAsStateWithLifecycle()
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
        SharingButton(watchers) { sheetOpen = true }
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

@Composable
private fun SharingButton(watchers: Int, onClick: () -> Unit) {
    val live = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .padding(end = 6.dp)
            .height(40.dp)
            .clip(ControlShape)
            .border(1.dp, live.copy(alpha = 0.6f), ControlShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ScreenShare, contentDescription = null, tint = live, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (watchers > 0) stringResource(R.string.screen_watching, watchers) else stringResource(R.string.screen_sharing),
            style = MaterialTheme.typography.labelLarge,
            color = live,
        )
    }
}
