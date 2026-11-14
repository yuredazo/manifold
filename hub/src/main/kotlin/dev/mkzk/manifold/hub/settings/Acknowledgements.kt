package dev.mkzk.manifold.hub.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.ui.ButtonKind
import dev.mkzk.manifold.hub.ui.ChoiceModal
import dev.mkzk.manifold.hub.ui.ModalAction
import dev.mkzk.manifold.hub.ui.PageCard
import dev.mkzk.manifold.hub.ui.PageDivider
import dev.mkzk.manifold.hub.ui.secondaryText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Credit(val name: String, val use: Int, val license: String, val url: String, val text: String, val covers: List<String>)

private class CreditGroup(val title: Int, val icon: ImageVector, val credits: List<Credit>)

private class Shown(val title: String, val text: String, val url: String?)

private val RowShape = RoundedCornerShape(18.dp)
private val TitleFadeFrom = 16.dp
private val TitleFade = 32.dp

private fun apache(holder: String) = """
Copyright $holder

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License. You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
""".trim()

private const val BOUNCY_CASTLE = """Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sub license, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions: The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

private val credits = listOf(
    CreditGroup(
        R.string.ack_group_encryption,
        Icons.Outlined.Lock,
        listOf(
            Credit("Bouncy Castle", R.string.ack_bouncy_castle, "MIT", "https://www.bouncycastle.org", BOUNCY_CASTLE, listOf("org.bouncycastle:")),
        ),
    ),
    CreditGroup(
        R.string.ack_group_interface,
        Icons.Outlined.Palette,
        listOf(
            Credit("Jetpack Compose", R.string.ack_compose, "Apache 2.0", "https://developer.android.com/compose", apache("The Android Open Source Project"), emptyList()),
            Credit("Material Icons", R.string.ack_icons, "Apache 2.0", "https://fonts.google.com/icons", apache("Google LLC"), listOf("androidx.compose.material:material-icons")),
            Credit("Reorderable", R.string.ack_reorderable, "Apache 2.0", "https://github.com/Calvin-LL/Reorderable", apache("Calvin Liang"), listOf("sh.calvin.reorderable:")),
        ),
    ),
    CreditGroup(
        R.string.ack_group_language,
        Icons.Outlined.Code,
        listOf(
            Credit("Kotlin", R.string.ack_kotlin, "Apache 2.0", "https://kotlinlang.org", apache("JetBrains s.r.o. and Kotlin Programming Language contributors"), listOf("org.jetbrains.kotlin:")),
            Credit("kotlinx.coroutines", R.string.ack_coroutines, "Apache 2.0", "https://github.com/Kotlin/kotlinx.coroutines", apache("JetBrains s.r.o. and contributors"), listOf("org.jetbrains.kotlinx:kotlinx-coroutines")),
        ),
    ),
)

private fun loadLibraries(context: Context): List<Library> {
    val json = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
    return Libs.Builder().withJson(json).build().libraries
}

private fun Library.shown(): Shown {
    val license = licenses.firstOrNull()
    val header = listOfNotNull(artifactVersion, license?.name, developers.mapNotNull { it.name }.joinToString(", ").ifEmpty { null }).joinToString("  |  ")
    val body = license?.licenseContent ?: license?.url.orEmpty()
    return Shown(name, if (header.isEmpty()) body else "$header\n\n$body", website)
}

@Composable
internal fun AcknowledgementsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val libraries by produceState<List<Library>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { loadLibraries(context) }.getOrDefault(emptyList()) }
    }
    val others = remember(libraries) {
        libraries.orEmpty()
            .filter { library -> credits.none { group -> group.credits.any { credit -> credit.covers.any { library.uniqueId.startsWith(it) } } } }
            .sortedBy { it.name.lowercase() }
    }
    var shown by remember { mutableStateOf<Shown?>(null) }
    var allOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (allOpen) allOpen = false else onBack() }
    if (allOpen) {
        TitledPage(stringResource(R.string.ack_all), { allOpen = false }, stringResource(R.string.ack_all_subtitle, others.size)) {
            PageCard {
                others.forEachIndexed { index, library ->
                    if (index > 0) PageDivider()
                    LinkRow(
                        library.name,
                        Icons.Outlined.Inventory2,
                        value = library.licenses.firstOrNull()?.let { it.spdxId ?: it.name },
                        note = library.artifactVersion,
                    ) { shown = library.shown() }
                }
            }
        }
    } else {
        val total = credits.sumOf { it.credits.size } + others.size
        TitledPage(stringResource(R.string.ack_title), onBack, stringResource(R.string.ack_subtitle, total)) {
            credits.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(group.title), style = MaterialTheme.typography.titleSmall, color = secondaryText(), modifier = Modifier.padding(start = 4.dp))
                    PageCard {
                        group.credits.forEachIndexed { index, credit ->
                            if (index > 0) PageDivider()
                            CreditRow(credit, group.icon) { shown = Shown(credit.name, credit.text, credit.url) }
                        }
                    }
                }
            }
            if (others.isNotEmpty()) {
                PageCard {
                    LinkRow(stringResource(R.string.ack_all), Icons.Outlined.Widgets, value = "${others.size}", note = stringResource(R.string.ack_all_note)) { allOpen = true }
                }
            }
        }
    }
    shown?.let { LicenseModal(it) { shown = null } }
}

@Composable
private fun TitledPage(title: String, onBack: () -> Unit, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val barTitle = titleAlpha(scroll)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.statusBarsPadding().fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back)) }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.graphicsLayer { alpha = barTitle })
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(Modifier.padding(start = 4.dp, top = 12.dp).graphicsLayer { alpha = 1f - barTitle }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.displaySmall)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = secondaryText())
                }
                content()
            }
        }
    }
}

@Composable
private fun titleAlpha(scroll: ScrollState): Float {
    val density = LocalDensity.current
    val alpha by remember { derivedStateOf { with(density) { ((scroll.value - TitleFadeFrom.toPx()) / TitleFade.toPx()).coerceIn(0f, 1f) } } }
    return alpha
}

@Composable
private fun CreditRow(credit: Credit, icon: ImageVector, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RowShape).clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = secondaryText(), modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(credit.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
                Text(credit.license, style = MaterialTheme.typography.labelMedium, color = secondaryText())
            }
            Text(stringResource(credit.use), style = MaterialTheme.typography.bodySmall, color = secondaryText())
        }
    }
}

@Composable
private fun LinkRow(label: String, icon: ImageVector, value: String?, note: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 6.dp).clip(RowShape).clickable(onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = secondaryText(), modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.labelMedium, color = secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = secondaryText(), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LicenseModal(shown: Shown, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ChoiceModal(
        title = shown.title,
        message = null,
        actions = listOfNotNull(
            shown.url?.let { url -> ModalAction(stringResource(R.string.ack_website), ButtonKind.Quiet) { runCatching { uriHandler.openUri(url) } } },
            ModalAction(stringResource(R.string.common_close), onClick = onDismiss),
        ),
        onDismiss = onDismiss,
        body = { Text(shown.text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
    )
}
