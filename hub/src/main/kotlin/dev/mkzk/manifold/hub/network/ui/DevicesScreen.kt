package dev.mkzk.manifold.hub.network.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.network.Network
import dev.mkzk.manifold.hub.network.NetworkService
import dev.mkzk.manifold.hub.network.NetworkState
import dev.mkzk.manifold.hub.network.PairingUi
import dev.mkzk.manifold.hub.network.StreamPreference
import dev.mkzk.manifold.hub.network.fetchPublicAddress
import dev.mkzk.manifold.hub.network.protocol.Control
import dev.mkzk.manifold.hub.network.protocol.Device
import dev.mkzk.manifold.hub.network.protocol.readableFingerprint
import dev.mkzk.manifold.hub.network.stream.StreamSnapshot
import dev.mkzk.manifold.hub.network.stream.details
import dev.mkzk.manifold.hub.network.stream.headline
import dev.mkzk.manifold.hub.ui.Banner
import dev.mkzk.manifold.hub.ui.IconBadge
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.Panel
import dev.mkzk.manifold.hub.ui.PanelDivider
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetTitle
import dev.mkzk.manifold.hub.ui.StatusDot
import dev.mkzk.manifold.hub.ui.SwitchRow
import dev.mkzk.manifold.hub.ui.secondaryText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevicesScreen(network: Network, streaming: StreamPreference, state: NetworkState, devices: List<Device>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var typingAddress by remember { mutableStateOf(false) }
    var pairSheet by remember { mutableStateOf(false) }
    var detailsSheet by remember { mutableStateOf(false) }
    var openDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Panel(title = stringResource(R.string.devices_this_phone)) {
                    SwitchRow(
                        title = stringResource(R.string.devices_accept),
                        checked = state.listening,
                        onChange = { on -> if (on) NetworkService.start(context) else NetworkService.stop(context) },
                        icon = Icons.Outlined.Wifi,
                        subtitle = when {
                            !state.listening -> stringResource(R.string.devices_off)
                            state.addresses.isEmpty() -> stringResource(R.string.devices_no_address)
                            else -> stringResource(R.string.devices_reachable_at, state.addresses.first())
                        },
                    )
                    PanelDivider()
                    ListRow(
                        title = stringResource(R.string.devices_details),
                        subtitle = readableFingerprint(network.identity.fingerprint),
                        leading = { IconBadge(Icons.Outlined.Fingerprint) },
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        onClick = { detailsSheet = true },
                    )
                    PanelDivider()
                    DelayRow(streaming)
                }
            }
            item {
                Panel(title = stringResource(R.string.devices_paired_header)) {
                    if (devices.isEmpty()) {
                        ListRow(title = stringResource(R.string.devices_none), leading = { IconBadge(Icons.Outlined.Devices) })
                    }
                    devices.forEachIndexed { index, device ->
                        if (index > 0) PanelDivider()
                        DeviceRow(device, online = device.publicKey in state.online, latency = state.latencyMs[device.publicKey], onClick = { openDevice = device.publicKey })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { pairSheet = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.devices_pair)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (pairSheet) {
        Sheet(onDismiss = { pairSheet = false }) {
            DisposableEffect(Unit) {
                network.listenForNearby()
                onDispose { network.stopListeningForNearby() }
            }
            val nearby by network.nearby.collectAsStateWithLifecycle()
            SheetTitle(stringResource(R.string.devices_pair))
            Panel(title = stringResource(R.string.pair_nearby_title), meta = nearby.size.takeIf { it > 0 }?.toString()) {
                if (nearby.isEmpty()) {
                    ListRow(
                        title = stringResource(R.string.pair_nearby_looking),
                        subtitle = stringResource(R.string.pair_nearby_hint),
                        leading = { IconBadge(Icons.Outlined.Search) },
                    )
                }
                nearby.forEachIndexed { index, found ->
                    if (index > 0) PanelDivider()
                    ListRow(
                        title = found.name,
                        subtitle = "${found.host} · ${readableFingerprint(found.id)}",
                        leading = { IconBadge(Icons.Outlined.Devices) },
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        onClick = {
                            pairSheet = false
                            NetworkService.start(context)
                            network.pair("${found.host}:${found.port}")
                        },
                    )
                }
            }
            Panel {
                ListRow(
                    title = stringResource(R.string.pair_enter_title),
                    subtitle = stringResource(R.string.pair_enter_body),
                    leading = { IconBadge(Icons.Outlined.Keyboard) },
                    onClick = {
                        pairSheet = false
                        typingAddress = true
                    },
                )
                PanelDivider()
                val openFor = state.pairingOpenUntil - now
                if (openFor > 0) {
                    ListRow(
                        title = stringResource(R.string.devices_pairing_open, "%d:%02d".format(openFor / 60_000, openFor / 1000 % 60)),
                        subtitle = stringResource(R.string.pair_open_hint),
                        leading = { IconBadge(Icons.Outlined.Wifi, active = true) },
                        trailing = { TextButton(onClick = network::closePairing) { Text(stringResource(R.string.common_close)) } },
                    )
                } else {
                    ListRow(
                        title = stringResource(R.string.pair_show_title),
                        subtitle = stringResource(R.string.pair_show_body),
                        leading = { IconBadge(Icons.Outlined.Wifi) },
                        onClick = {
                            NetworkService.start(context)
                            network.openPairing()
                        },
                    )
                }
            }
        }
    }

    if (detailsSheet) {
        Sheet(onDismiss = { detailsSheet = false }) {
            SheetTitle(stringResource(R.string.devices_details))
            Panel {
                ListRow(
                    title = stringResource(R.string.device_fingerprint),
                    subtitle = readableFingerprint(network.identity.fingerprint),
                    leading = { IconBadge(Icons.Outlined.Fingerprint) },
                )
                if (state.listening) {
                    state.addresses.forEach {
                        PanelDivider()
                        AddressRow(stringResource(R.string.address_local), it)
                    }
                    PanelDivider()
                    PublicAddress(state.port)
                }
            }
            if (!state.listening) {
                Text(
                    stringResource(R.string.devices_details_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryText(),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    val shown = devices.firstOrNull { it.publicKey == openDevice }
    if (shown != null) {
        DeviceSheet(
            network,
            shown,
            online = shown.publicKey in state.online,
            latency = state.latencyMs[shown.publicKey],
            offered = state.remoteFeeds[shown.publicKey].orEmpty().map { it.name },
            streams = state.streams.filter { it.deviceKey == shown.publicKey },
            refused = state.refused[shown.publicKey].orEmpty(),
            onDismiss = { openDevice = null },
        )
    }

    if (typingAddress) {
        AddressDialog(
            onDismiss = { typingAddress = false },
            onPair = {
                typingAddress = false
                NetworkService.start(context)
                network.pair(it)
            },
        )
    }
    PairingDialog(state.pairing, network)
}

@Composable
private fun DeviceRow(device: Device, online: Boolean, latency: Int?, onClick: () -> Unit) {
    val summary = buildList {
        add(stringResource(if (online) R.string.device_online else R.string.device_offline))
        if (online && latency != null) add(stringResource(R.string.device_latency, latency))
        if (device.receive) add(stringResource(R.string.device_receiving))
        if (device.send) add(stringResource(R.string.device_sharing))
    }.joinToString(" · ").replaceFirstChar { it.uppercase() }
    ListRow(
        title = device.name,
        subtitle = summary,
        leading = { IconBadge(Icons.Outlined.Devices, active = online) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(online)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun DeviceSheet(
    network: Network,
    device: Device,
    online: Boolean,
    latency: Int?,
    offered: List<String>,
    streams: List<StreamSnapshot>,
    refused: Map<String, Control.Refusal>,
    onDismiss: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    Sheet(onDismiss) {
        SheetTitle(device.name)
        Panel(
            title = listOfNotNull(
                stringResource(if (online) R.string.device_online else R.string.device_offline),
                latency?.takeIf { online }?.let { stringResource(R.string.device_latency, it) },
            ).joinToString(" · ").replaceFirstChar { it.uppercase() },
            online = online,
        ) {
            ListRow(
                title = stringResource(R.string.device_fingerprint),
                subtitle = readableFingerprint(device.fingerprint),
                leading = { IconBadge(Icons.Outlined.Fingerprint) },
            )
            device.address?.let {
                PanelDivider()
                ListRow(title = stringResource(R.string.device_address), subtitle = it, leading = { IconBadge(Icons.Outlined.Wifi) })
            }
        }
        Panel {
            SwitchRow(
                title = stringResource(R.string.device_receive),
                checked = device.receive,
                onChange = { network.setReceive(device.publicKey, it) },
                subtitle = if (online && device.receive) offersText(offered) else stringResource(R.string.device_receive_hint),
                icon = Icons.Outlined.Download,
            )
            PanelDivider()
            SwitchRow(
                title = stringResource(R.string.device_send),
                checked = device.send,
                onChange = { network.setSend(device.publicKey, it) },
                subtitle = stringResource(R.string.device_send_hint),
                icon = Icons.Outlined.Upload,
            )
        }
        if (streams.isNotEmpty()) {
            Panel(title = stringResource(R.string.device_streams)) {
                streams.forEachIndexed { index, stream ->
                    if (index > 0) PanelDivider()
                    StreamReadout(stream)
                }
            }
        }
        refused.forEach { (feed, reason) ->
            Banner(Icons.Outlined.ErrorOutline, error = true) {
                Text(stringResource(refusalText(reason), feed), style = MaterialTheme.typography.bodySmall)
            }
        }
        Panel {
            ListRow(
                title = stringResource(R.string.device_unpair),
                titleColor = MaterialTheme.colorScheme.error,
                leading = { IconBadge(Icons.Outlined.LinkOff) },
                onClick = { confirming = true },
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.device_unpair_title, device.name)) },
            text = { Text(stringResource(R.string.device_unpair_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onDismiss()
                    network.unpair(device.publicKey)
                }) { Text(stringResource(R.string.device_unpair), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun AddressRow(label: String, address: String) {
    val context = LocalContext.current
    val copied = stringResource(R.string.address_copied)
    ListRow(
        title = label,
        subtitle = address,
        leading = { IconBadge(Icons.Outlined.Wifi) },
        trailing = { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.address_copy), modifier = Modifier.size(18.dp)) },
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, address))
            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
        },
    )
}

/** Looked up only when asked, since it tells an outside service this phone's address. */
@Composable
private fun PublicAddress(port: Int) {
    var address by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val known = address
    if (known != null) {
        AddressRow(stringResource(R.string.address_public), "$known:$port")
        return
    }
    ListRow(
        title = stringResource(R.string.address_public),
        subtitle = if (failed) stringResource(R.string.address_failed) else stringResource(if (looking) R.string.address_looking else R.string.address_lookup),
        leading = { IconBadge(Icons.Outlined.Public) },
        onClick = if (looking) {
            null
        } else {
            {
                looking = true
                failed = false
                scope.launch {
                    address = withContext(Dispatchers.IO) { fetchPublicAddress() }
                    failed = address == null
                    looking = false
                }
            }
        },
    )
}

@Composable
private fun offersText(offered: List<String>) =
    if (offered.isEmpty()) stringResource(R.string.device_offers_nothing) else stringResource(R.string.device_offers, offered.size)

private fun refusalText(reason: Control.Refusal) = when (reason) {
    Control.Refusal.NOT_SHARED -> R.string.device_refused_not_shared
    Control.Refusal.NOT_FOUND -> R.string.device_refused_not_found
    Control.Refusal.BUSY -> R.string.device_refused_busy
    Control.Refusal.FAILED -> R.string.device_refused_failed
}

@Composable
private fun StreamReadout(stream: StreamSnapshot) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(stream.headline(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(stream.details(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = secondaryText())
    }
}

@Composable
private fun AddressDialog(onDismiss: () -> Unit, onPair: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pair_address_title)) },
        text = {
            Column {
                Text(stringResource(R.string.pair_address_help), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.pair_address_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { Button(onClick = { onPair(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.pair_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun PairingDialog(pairing: PairingUi, network: Network) {
    when (pairing) {
        PairingUi.None -> Unit
        is PairingUi.Connecting -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.pair_address_title)) },
            text = { Text(stringResource(R.string.pair_connecting, pairing.address)) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = network::cancelPairing) { Text(stringResource(R.string.common_cancel)) } },
        )
        is PairingUi.Code -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.pair_code_title, pairing.remoteName)) },
            text = {
                Column {
                    Text(stringResource(R.string.pair_code_body), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        pairing.code.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    if (pairing.confirmed) {
                        Text(stringResource(R.string.pair_waiting, pairing.remoteName), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                if (!pairing.confirmed) Button(onClick = { network.confirmPairing(true) }) { Text(stringResource(R.string.pair_match)) }
            },
            dismissButton = {
                TextButton(onClick = { if (pairing.confirmed) network.cancelPairing() else network.confirmPairing(false) }) {
                    Text(stringResource(if (pairing.confirmed) R.string.common_cancel else R.string.pair_nomatch))
                }
            },
        )
        is PairingUi.Failed -> AlertDialog(
            onDismissRequest = network::dismissPairing,
            title = { Text(stringResource(R.string.pair_failed_title)) },
            text = { Text(stringResource(R.string.pair_failed_body, pairing.reason)) },
            confirmButton = { TextButton(onClick = network::dismissPairing) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}
