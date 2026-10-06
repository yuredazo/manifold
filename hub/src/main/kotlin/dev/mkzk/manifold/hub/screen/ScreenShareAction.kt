package dev.mkzk.manifold.hub.screen

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.StopScreenShare
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R

@Composable
internal fun ScreenShareAction(screenShare: ScreenShare, watchers: Int, start: (withSound: Boolean) -> Unit) {
    val state by screenShare.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val failed = stringResource(R.string.screen_failed)
    LaunchedEffect(state.failed) {
        if (state.failed) {
            Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
            screenShare.clearFailure()
        }
    }

    if (state.sharing) {
        IconButton(onClick = screenShare::stop) {
            BadgedBox(badge = { if (watchers > 0) Badge { Text(watchers.toString()) } }) {
                Icon(Icons.Outlined.StopScreenShare, stringResource(R.string.screen_stop), tint = MaterialTheme.colorScheme.error)
            }
        }
        return
    }

    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Outlined.ScreenShare, stringResource(R.string.screen_share))
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.widthIn(min = 240.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                stringResource(R.string.screen_menu_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            Text(
                stringResource(R.string.screen_menu_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            // Playback capture needs Android 10.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.screen_with_sound)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null) },
                    onClick = { menuOpen = false; start(true) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.screen_without_sound)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.VolumeOff, contentDescription = null) },
                    onClick = { menuOpen = false; start(false) },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.screen_share)) },
                    leadingIcon = { Icon(Icons.Outlined.ScreenShare, contentDescription = null) },
                    onClick = { menuOpen = false; start(false) },
                )
            }
        }
    }
}
