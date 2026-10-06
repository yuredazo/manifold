package dev.mkzk.manifold.hub.about

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.ui.GroupDivider
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.SwitchRow
import dev.mkzk.manifold.hub.update.Problem
import dev.mkzk.manifold.hub.update.UpdateState
import dev.mkzk.manifold.hub.update.Updater

@Composable
internal fun UpdateAnnouncement(updater: Updater) {
    val release by updater.announcement.collectAsStateWithLifecycle()
    val found = release ?: return
    AlertDialog(
        onDismissRequest = updater::dismissAnnouncement,
        title = { Text(stringResource(R.string.update_announce_title, found.version)) },
        text = { Text(stringResource(R.string.update_you_have, updater.version)) },
        confirmButton = {
            TextButton(onClick = {
                updater.dismissAnnouncement()
                updater.install()
            }) { Text(stringResource(R.string.update_install)) }
        },
        dismissButton = { TextButton(onClick = updater::dismissAnnouncement) { Text(stringResource(R.string.update_later)) } },
    )
}

@Composable
internal fun UpdateSection(updater: Updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val checkOnLaunch by updater.checkOnLaunch.collectAsStateWithLifecycle()

    StatusRow(state, updater)
    when (val current = state) {
        is UpdateState.Downloading -> {
            val fraction = current.fraction
            if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.AwaitingConfirmation -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else -> Unit
    }
    GroupDivider()
    SwitchRow(stringResource(R.string.update_on_launch), checkOnLaunch, updater::setCheckOnLaunch)
    (state as? UpdateState.Available)?.let { available ->
        GroupDivider()
        LinkRow(Icons.Outlined.SystemUpdate, R.string.update_release_page, available.release.page)
    }
}

@Composable
private fun StatusRow(state: UpdateState, updater: Updater) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val time = { millis: Long -> DateFormat.getTimeFormat(context).format(millis) }

    val title: String
    var detail: String? = null
    val icon: @Composable () -> Unit
    val action: @Composable () -> Unit

    when (state) {
        UpdateState.Idle -> {
            title = stringResource(R.string.update_idle)
            icon = { Icon(Icons.Outlined.SystemUpdate, null) }
            action = { FilledTonalButton(onClick = updater::check) { Text(stringResource(R.string.update_check_now)) } }
        }
        UpdateState.Checking -> {
            title = stringResource(R.string.update_checking)
            icon = { Spinner() }
            action = {}
        }
        is UpdateState.UpToDate -> {
            title = stringResource(R.string.update_uptodate)
            detail = stringResource(R.string.update_checked_at, time(state.checkedAt))
            icon = { Icon(Icons.Outlined.CheckCircle, null, tint = colors.primary) }
            action = { TextButton(onClick = updater::check) { Text(stringResource(R.string.update_check_again)) } }
        }
        is UpdateState.Available -> {
            title = stringResource(R.string.update_available, state.release.version)
            detail = stringResource(R.string.update_you_have, updater.version)
            icon = { Icon(Icons.Outlined.ArrowCircleUp, null, tint = colors.primary) }
            action = { Button(onClick = updater::install) { Text(stringResource(R.string.update_install)) } }
        }
        is UpdateState.Downloading -> {
            title = stringResource(R.string.update_downloading, state.release.version)
            detail = state.fraction?.let { "${(it * 100).toInt()}%" }
            icon = { Spinner() }
            action = {}
        }
        is UpdateState.AwaitingConfirmation -> {
            title = stringResource(R.string.update_confirming)
            detail = stringResource(R.string.update_confirming_detail)
            icon = { Spinner() }
            action = {}
        }
        is UpdateState.Failed -> {
            title = stringResource(if (state.problem.whileInstalling) R.string.update_failed else R.string.update_check_failed)
            detail = problemText(state, time)
            icon = { Icon(Icons.Outlined.ErrorOutline, null, tint = colors.error) }
            action = { FilledTonalButton(onClick = updater::check) { Text(stringResource(R.string.update_try_again)) } }
        }
    }

    ListRow(
        title = title,
        titleColor = if (state is UpdateState.Failed) colors.error else Color.Unspecified,
        subtitle = detail,
        leading = { Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() } },
        trailing = action,
    )
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

@Composable
private fun Spinner() = CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
