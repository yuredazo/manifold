package dev.mkzk.manifold.hub.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.ui.Group
import dev.mkzk.manifold.hub.ui.GroupDivider
import dev.mkzk.manifold.hub.ui.IconBadge
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.SectionLabel
import dev.mkzk.manifold.hub.ui.secondaryText
import dev.mkzk.manifold.hub.update.Updater

private const val REPO = "https://github.com/yuredazo/manifold"

@Composable
internal fun AboutScreen(updater: Updater, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium, color = secondaryText())
            }
        }
        Text(
            stringResource(R.string.about_body),
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryText(),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )

        SectionLabel(R.string.about_updates)
        Group { UpdateSection(updater) }

        SectionLabel(R.string.about_project)
        Group {
            LinkRow(Icons.Outlined.Code, R.string.about_source, REPO)
            GroupDivider()
            LinkRow(Icons.Outlined.Description, R.string.about_how, "$REPO/blob/main/docs/PROTOCOL.md")
            GroupDivider()
            LinkRow(Icons.Outlined.BugReport, R.string.about_report, "$REPO/blob/main/SECURITY.md")
            GroupDivider()
            LinkRow(Icons.Outlined.Gavel, R.string.about_license, "$REPO/blob/main/LICENSE")
        }
    }
}

@Composable
internal fun LinkRow(icon: ImageVector, label: Int, url: String) {
    val links = LocalUriHandler.current
    ListRow(title = stringResource(label), leading = { IconBadge(icon) }, onClick = { runCatching { links.openUri(url) } })
}
