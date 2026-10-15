package dev.mkzk.manifold.hub.network.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.network.StreamPreference
import dev.mkzk.manifold.hub.network.stream.StreamProfile
import dev.mkzk.manifold.hub.ui.IconBadge
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.Panel
import dev.mkzk.manifold.hub.ui.PanelDivider
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetTitle
import dev.mkzk.manifold.hub.ui.secondaryText

@Composable
internal fun DelayRow(preference: StreamPreference) {
    val chosen by preference.profile.collectAsState()
    var open by remember { mutableStateOf(false) }
    ListRow(
        title = stringResource(R.string.devices_delay),
        subtitle = stringResource(bodyOf(chosen)),
        leading = { IconBadge(Icons.Outlined.Speed) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(titleOf(chosen)), style = MaterialTheme.typography.bodyMedium, color = secondaryText())
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        },
        onClick = { open = true },
    )
    if (open) {
        Sheet(onDismiss = { open = false }) {
            SheetTitle(stringResource(R.string.devices_delay))
            Panel {
                StreamProfile.entries.forEachIndexed { index, profile ->
                    if (index > 0) PanelDivider()
                    ListRow(
                        title = stringResource(titleOf(profile)),
                        subtitle = stringResource(bodyOf(profile)),
                        trailing = { RadioButton(selected = profile == chosen, onClick = null) },
                        onClick = {
                            preference.choose(profile)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

private fun titleOf(profile: StreamProfile) = when (profile) {
    StreamProfile.LowLatency -> R.string.stream_low_title
    StreamProfile.Balanced -> R.string.stream_balanced_title
    StreamProfile.Smooth -> R.string.stream_smooth_title
}

private fun bodyOf(profile: StreamProfile) = when (profile) {
    StreamProfile.LowLatency -> R.string.stream_low_body
    StreamProfile.Balanced -> R.string.stream_balanced_body
    StreamProfile.Smooth -> R.string.stream_smooth_body
}
