package dev.mkzk.manifold.hub.camera

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraFront
import androidx.compose.material.icons.outlined.CameraRear
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.ScreenLockRotation
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.ui.ButtonKind
import dev.mkzk.manifold.hub.ui.LiveShareButton
import dev.mkzk.manifold.hub.ui.ModalButton
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetList
import dev.mkzk.manifold.hub.ui.SheetListDivider
import dev.mkzk.manifold.hub.ui.SheetListRow
import dev.mkzk.manifold.hub.ui.SheetTitle
import dev.mkzk.manifold.hub.ui.SquareIcon

@Composable
internal fun CameraShareAction(cameraShare: CameraShare, watchersOf: (feed: String?) -> Int, start: () -> Unit) {
    val state by cameraShare.state.collectAsStateWithLifecycle()
    val watchers = watchersOf(state.feed)
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val failed = stringResource(R.string.camera_failed)
    LaunchedEffect(state.failed) {
        if (state.failed) {
            Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
            cameraShare.clearFailure()
        }
    }

    if (state.sharing) {
        val label = if (watchers > 0) stringResource(R.string.camera_live, watchers) else stringResource(R.string.camera_live_idle)
        LiveShareButton(Icons.Outlined.Videocam, watchers, label) { sheetOpen = true }
    } else {
        IconButton(onClick = { sheetOpen = true }) {
            Icon(Icons.Outlined.Videocam, stringResource(R.string.camera_share), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (!sheetOpen) return
    val canFlip = remember { cameraShare.canFlip }
    val front = state.lens == Lens.Front
    Sheet(onDismiss = { sheetOpen = false }) { modal ->
        SheetTitle(stringResource(if (state.sharing) R.string.camera_sharing_title else R.string.camera_share))
        SheetList {
            if (state.sharing) {
                SheetListRow(
                    title = if (watchers > 0) stringResource(R.string.camera_watching, watchers) else stringResource(R.string.camera_nobody_watching),
                    onClick = null,
                    leading = { SquareIcon(Icons.Outlined.Visibility, active = watchers > 0) },
                )
                SheetListDivider()
            }
            SheetListRow(
                title = stringResource(if (front) R.string.camera_front else R.string.camera_back),
                subtitle = if (state.sharing && state.taken) stringResource(R.string.camera_taken) else null,
                onClick = null,
                leading = { SquareIcon(if (front) Icons.Outlined.CameraFront else Icons.Outlined.CameraRear) },
                trailing = {
                    IconButton(onClick = cameraShare::flip, enabled = canFlip) {
                        Icon(Icons.Outlined.Cameraswitch, stringResource(if (front) R.string.camera_flip_to_back else R.string.camera_flip_to_front))
                    }
                },
            )
            SheetListDivider()
            SheetListRow(
                title = stringResource(R.string.camera_lock),
                subtitle = stringResource(R.string.camera_lock_hint),
                role = Role.Switch,
                leading = { SquareIcon(Icons.Outlined.ScreenLockRotation, active = state.locked) },
                trailing = { Switch(checked = state.locked, onCheckedChange = null) },
                onClick = { cameraShare.setLocked(!state.locked) },
            )
            SheetListDivider()
            SheetListRow(
                title = stringResource(R.string.camera_mirror),
                subtitle = stringResource(R.string.camera_mirror_hint),
                role = Role.Switch,
                leading = { SquareIcon(Icons.Outlined.Flip, active = state.mirrored) },
                trailing = { Switch(checked = state.mirrored, onCheckedChange = null) },
                onClick = { cameraShare.setMirrored(!state.mirrored) },
            )
        }
        if (state.sharing) {
            ModalButton(stringResource(R.string.camera_stop), ButtonKind.Danger, Modifier.fillMaxWidth()) {
                modal.close {
                    cameraShare.stop()
                    sheetOpen = false
                }
            }
        } else {
            ModalButton(stringResource(R.string.camera_start), ButtonKind.Main, Modifier.fillMaxWidth()) {
                modal.close {
                    sheetOpen = false
                    start()
                }
            }
        }
    }
}
