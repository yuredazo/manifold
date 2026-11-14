package dev.mkzk.manifold.hub.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.ui.PageCardShape
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetList
import dev.mkzk.manifold.hub.ui.SheetListDivider
import dev.mkzk.manifold.hub.ui.SheetListRow
import dev.mkzk.manifold.hub.ui.SquareIcon
import dev.mkzk.manifold.hub.ui.secondaryText

private val IconShape = RoundedCornerShape(18.dp)

@Composable
internal fun ManifoldTile(version: String, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onOpen, shape = PageCardShape, color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIcon(48)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name_plain), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(stringResource(R.string.about_tile_note, version), style = MaterialTheme.typography.bodySmall, color = secondaryText(), maxLines = 1)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = secondaryText(), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AppIcon(size: Int) {
    val shape = if (size < 64) CircleShape else IconShape
    Box(Modifier.size(size.dp).clip(shape).background(colorResource(R.color.ic_launcher_background)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize().scale(1.5f))
    }
}

@Composable
internal fun AboutSheet(version: String, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(80)
            Text(stringResource(R.string.app_name_plain), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodySmall, color = secondaryText(), modifier = Modifier.padding(top = 2.dp))
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
            )
        }
        SheetList {
            SheetListRow(stringResource(R.string.about_local_title), onClick = null, subtitle = stringResource(R.string.about_local_body), leading = { SquareIcon(Icons.Outlined.SwapHoriz) })
            SheetListDivider()
            SheetListRow(stringResource(R.string.about_remote_title), onClick = null, subtitle = stringResource(R.string.about_remote_body), leading = { SquareIcon(Icons.Outlined.Devices) })
            SheetListDivider()
            SheetListRow(stringResource(R.string.about_open_title), onClick = null, subtitle = stringResource(R.string.about_open_body), leading = { SquareIcon(Icons.Outlined.Code) })
        }
    }
}
