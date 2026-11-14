package dev.mkzk.manifold.hub.broker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.broker.Access
import dev.mkzk.manifold.hub.broker.PendingRow
import dev.mkzk.manifold.hub.broker.Registry
import dev.mkzk.manifold.hub.ui.Banner
import dev.mkzk.manifold.hub.ui.ButtonKind
import dev.mkzk.manifold.hub.ui.ModalButton
import dev.mkzk.manifold.hub.ui.secondaryText

@Composable
internal fun PendingBanner(row: PendingRow, registry: Registry) {
    Banner(Icons.Outlined.Notifications) {
        Text(row.app, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.pending_wants, row.feeds.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
        Text(row.packageName, style = MaterialTheme.typography.bodySmall, color = secondaryText())
        if (row.certificateChanged) {
            Text(stringResource(R.string.cert_changed_pending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
        AnswerButtons(row.packageName, registry, Modifier.padding(top = 10.dp))
    }
}

@Composable
internal fun AnswerButtons(packageName: String, registry: Registry, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        ModalButton(stringResource(R.string.action_block), ButtonKind.Quiet, Modifier) { registry.setAccess(packageName, Access.BLOCKED) }
        ModalButton(stringResource(R.string.action_allow), ButtonKind.Main, Modifier) { registry.setAccess(packageName, Access.ALLOWED) }
    }
}
