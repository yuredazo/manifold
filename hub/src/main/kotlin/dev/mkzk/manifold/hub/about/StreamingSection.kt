package dev.mkzk.manifold.hub.about

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.network.StreamPreference
import dev.mkzk.manifold.hub.network.stream.StreamProfile
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetTitle

@Composable
internal fun StreamingSection(preference: StreamPreference) {
    val chosen by preference.profile.collectAsState()
    var open by remember { mutableStateOf(false) }
    ListRow(
        title = stringResource(titleOf(chosen)),
        subtitle = stringResource(bodyOf(chosen)),
        trailing = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        onClick = { open = true },
    )
    if (open) {
        Sheet(onDismiss = { open = false }) {
            SheetTitle(stringResource(R.string.about_streaming))
            StreamProfile.entries.forEach { profile ->
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
