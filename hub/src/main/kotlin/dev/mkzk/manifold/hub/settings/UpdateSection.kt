package dev.mkzk.manifold.hub.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.ui.ControlShape
import dev.mkzk.manifold.hub.ui.Panel
import dev.mkzk.manifold.hub.ui.secondaryText
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
internal fun UpdateCard(updater: Updater, version: String) {
    val state by updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val time = { millis: Long -> DateFormat.getTimeFormat(context).format(millis) }
    val colors = MaterialTheme.colorScheme

    val status: String
    var detail: String? = null
    var action: (@Composable () -> Unit)? = null
    var busy = false

    when (val current = state) {
        UpdateState.Idle -> {
            status = stringResource(R.string.update_idle)
            action = { OutlinedButton(onClick = updater::check, shape = ControlShape, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_check_now)) } }
        }
        UpdateState.Checking -> {
            status = stringResource(R.string.update_checking)
            busy = true
        }
        is UpdateState.UpToDate -> {
            status = stringResource(R.string.update_uptodate)
            detail = stringResource(R.string.update_checked_at, time(current.checkedAt))
            action = { OutlinedButton(onClick = updater::check, shape = ControlShape, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_check_again)) } }
        }
        is UpdateState.Available -> {
            status = stringResource(R.string.update_available, current.release.version)
            detail = stringResource(R.string.update_you_have, updater.version)
            action = { Button(onClick = updater::install, shape = ControlShape, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_install)) } }
        }
        is UpdateState.Downloading -> {
            status = stringResource(R.string.update_downloading, current.release.version)
            detail = current.fraction?.let { "${(it * 100).toInt()}%" }
            busy = true
        }
        is UpdateState.AwaitingConfirmation -> {
            status = stringResource(R.string.update_confirming)
            detail = stringResource(R.string.update_confirming_detail)
            busy = true
        }
        is UpdateState.Failed -> {
            status = stringResource(if (current.problem.whileInstalling) R.string.update_failed else R.string.update_check_failed)
            detail = problemText(current, time)
            action = { OutlinedButton(onClick = updater::check, shape = ControlShape, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_try_again)) } }
        }
    }
    val failed = state is UpdateState.Failed

    Panel {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(40.dp), tint = colors.primary)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.about_title, version), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (failed) status else listOfNotNull(status, detail).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) colors.error else secondaryText(),
                    )
                }
            }
            if (failed && detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.error)
            when (val current = state) {
                is UpdateState.Downloading -> {
                    val fraction = current.fraction
                    if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                else -> if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            action?.invoke()
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodySmall, color = secondaryText())
        }
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
