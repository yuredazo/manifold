package dev.mkzk.manifold.hub.settings

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.ui.ButtonKind
import dev.mkzk.manifold.hub.ui.ChoiceModal
import dev.mkzk.manifold.hub.ui.ModalAction
import dev.mkzk.manifold.hub.ui.ModalButton
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.secondaryText
import dev.mkzk.manifold.hub.update.Problem
import dev.mkzk.manifold.hub.update.UpdateState
import dev.mkzk.manifold.hub.update.Updater

@Composable
internal fun UpdateAnnouncement(updater: Updater) {
    val release by updater.announcement.collectAsStateWithLifecycle()
    val found = release ?: return
    ChoiceModal(
        title = stringResource(R.string.update_announce_title, found.version),
        message = stringResource(R.string.update_you_have, updater.version),
        actions = listOf(
            ModalAction(stringResource(R.string.update_later), ButtonKind.Quiet, onClick = updater::dismissAnnouncement),
            ModalAction(stringResource(R.string.update_install)) {
                updater.dismissAnnouncement()
                updater.install()
            },
        ),
        onDismiss = updater::dismissAnnouncement,
    )
}

private const val RELEASES = "https://github.com/yuredazo/manifold/releases"

private class UpdateLook(
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val tone: Color?,
    val busy: Boolean,
    val progress: Float?,
    val action: Int,
    val main: Boolean,
    val run: () -> Unit,
)

@Composable
private fun lookOf(state: UpdateState, updater: Updater): UpdateLook {
    val context = LocalContext.current
    val time = { millis: Long -> DateFormat.getTimeFormat(context).format(millis) }
    val scheme = MaterialTheme.colorScheme
    val installed = stringResource(R.string.update_you_have, updater.version)
    return when (state) {
        UpdateState.Idle -> UpdateLook(stringResource(R.string.update_idle), installed, Icons.Outlined.SystemUpdate, null, false, null, R.string.update_check_now, false, updater::check)
        UpdateState.Checking -> UpdateLook(stringResource(R.string.update_checking), installed, Icons.Outlined.SystemUpdate, null, true, null, R.string.update_busy_checking, false) {}
        is UpdateState.UpToDate -> UpdateLook(
            stringResource(R.string.update_uptodate), stringResource(R.string.update_checked_at, time(state.checkedAt)),
            Icons.Outlined.CheckCircle, null, false, null, R.string.update_check_again, false, updater::check,
        )
        is UpdateState.Available -> UpdateLook(
            stringResource(R.string.update_available, state.release.version), installed,
            Icons.Outlined.SystemUpdate, scheme.primary, false, null, R.string.update_install, true, updater::install,
        )
        is UpdateState.Downloading -> UpdateLook(
            stringResource(R.string.update_downloading, state.release.version), state.fraction?.let { "${(it * 100).toInt()}%" } ?: installed,
            Icons.Outlined.Download, null, true, state.fraction ?: 0f, R.string.update_busy_downloading, true,
        ) {}
        is UpdateState.AwaitingConfirmation -> UpdateLook(
            stringResource(R.string.update_confirming), stringResource(R.string.update_confirming_detail),
            Icons.Outlined.HourglassEmpty, null, true, null, R.string.update_confirming, true,
        ) {}
        is UpdateState.Failed -> UpdateLook(
            stringResource(if (state.problem.whileInstalling) R.string.update_failed else R.string.update_check_failed), problemText(state, time),
            Icons.Outlined.ErrorOutline, scheme.error, false, null, R.string.update_try_again, false, updater::check,
        )
    }
}

@Composable
internal fun updateValue(updater: Updater): Pair<String, Color?> {
    val state by updater.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    return when (val current = state) {
        UpdateState.Idle -> stringResource(R.string.update_value_idle) to null
        UpdateState.Checking -> stringResource(R.string.update_busy_checking) to null
        is UpdateState.UpToDate -> stringResource(R.string.update_uptodate) to null
        is UpdateState.Available -> stringResource(R.string.update_value_available, current.release.version) to scheme.primary
        is UpdateState.Downloading -> (current.fraction?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.update_busy_downloading)) to null
        is UpdateState.AwaitingConfirmation -> stringResource(R.string.update_confirming) to null
        is UpdateState.Failed -> stringResource(R.string.update_value_failed) to scheme.error
    }
}

@Composable
internal fun UpdateSheet(updater: Updater, onDismiss: () -> Unit) {
    val state by updater.state.collectAsStateWithLifecycle()
    val look = lookOf(state, updater)
    val uri = LocalUriHandler.current
    val release = (state as? UpdateState.Available)?.release
    val scheme = MaterialTheme.colorScheme
    val tone = look.tone ?: scheme.onSurface
    Sheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(64.dp).background(tone.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(look.icon, contentDescription = null, tint = tone, modifier = Modifier.size(28.dp))
                }
                look.progress?.let { fraction ->
                    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(72.dp), strokeWidth = 3.dp, trackColor = Color.Transparent)
                }
            }
            Text(
                look.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp),
            )
            Text(
                look.detail,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryText(),
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
            )
        }
        ModalButton(stringResource(look.action), if (look.main) ButtonKind.Main else ButtonKind.Quiet, Modifier.fillMaxWidth().padding(top = 8.dp), busy = look.busy, onClick = look.run)
        Text(
            stringResource(if (release != null) R.string.update_release_page else R.string.update_all_releases),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(CircleShape).clickable(role = Role.Button) { runCatching { uri.openUri(release?.page ?: RELEASES) } }.padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun problemText(failed: UpdateState.Failed, time: (Long) -> String): String {
    val detail = failed.detail
    return when (failed.problem) {
        Problem.OFFLINE -> stringResource(R.string.problem_offline)
        Problem.TIMEOUT -> stringResource(R.string.problem_timeout)
        Problem.RATE_LIMITED -> detail?.toLongOrNull()?.let { stringResource(R.string.problem_rate_limited_at, time(it * 1000)) } ?: stringResource(R.string.problem_rate_limited)
        Problem.GITHUB_STATUS -> stringResource(R.string.problem_status, detail.orEmpty())
        Problem.BAD_ANSWER -> stringResource(R.string.problem_bad_answer)
        Problem.NO_DOWNLOAD -> stringResource(R.string.problem_no_download, detail.orEmpty())
        Problem.DOWNLOAD_FAILED -> stringResource(R.string.problem_download_failed)
        Problem.TOO_LARGE -> stringResource(R.string.problem_too_large)
        Problem.BAD_CHECKSUM -> stringResource(R.string.problem_bad_checksum)
        Problem.DIFFERENT_SIGNATURE -> stringResource(R.string.problem_signature)
        Problem.INSTALL_FAILED -> detail?.let { stringResource(R.string.problem_install_failed_detail, it) } ?: stringResource(R.string.problem_install_failed)
    }
}
