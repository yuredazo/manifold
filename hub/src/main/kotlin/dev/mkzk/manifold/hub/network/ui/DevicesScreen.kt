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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import dev.mkzk.manifold.hub.ui.ButtonKind
import dev.mkzk.manifold.hub.ui.ChoiceModal
import dev.mkzk.manifold.hub.ui.ConfirmModal
import dev.mkzk.manifold.hub.ui.EmptyNotice
import dev.mkzk.manifold.hub.ui.IconBadge
import dev.mkzk.manifold.hub.ui.ListRow
import dev.mkzk.manifold.hub.ui.ModalAction
import dev.mkzk.manifold.hub.ui.ModalButton
import dev.mkzk.manifold.hub.ui.Panel
import dev.mkzk.manifold.hub.ui.PanelDivider
import dev.mkzk.manifold.hub.ui.Sheet
import dev.mkzk.manifold.hub.ui.SheetList
import dev.mkzk.manifold.hub.ui.SheetListDivider
import dev.mkzk.manifold.hub.ui.SheetListRow
import dev.mkzk.manifold.hub.ui.SheetNote
import dev.mkzk.manifold.hub.ui.SheetTitle
import dev.mkzk.manifold.hub.ui.SquareIcon
import dev.mkzk.manifold.hub.ui.StatusDot
import dev.mkzk.manifold.hub.ui.SwitchRow
import dev.mkzk.manifold.hub.ui.TextModal
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
                        EmptyNotice(Icons.Outlined.Devices, stringResource(R.string.devices_none_title), stringResource(R.string.devices_none_body))
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
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (pairSheet) {
        Sheet(onDismiss = { pairSheet = false }) { modal ->
            DisposableEffect(Unit) {
                network.listenForNearby()
                onDispose { network.stopListeningForNearby() }
            }
            val nearby by network.nearby.collectAsStateWithLifecycle()
            SheetTitle(stringResource(R.string.devices_pair))
            SheetList(label = stringResource(R.string.pair_nearby_title), meta = nearby.size.takeIf { it > 0 }?.toString()) {
                if (nearby.isEmpty()) {
                    SheetListRow(
                        title = stringResource(R.string.pair_nearby_looking),
                        subtitle = stringResource(R.string.pair_nearby_hint),
                        onClick = null,
                        leading = { SquareIcon(Icons.Outlined.Search) },
                        trailing = { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) },
                    )
                }
                nearby.forEachIndexed { index, found ->
                    if (index > 0) SheetListDivider()
                    SheetListRow(
                        title = found.name,
                        subtitle = "${found.host} · ${readableFingerprint(found.id)}",
                        leading = { SquareIcon(Icons.Outlined.Devices) },
                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        onClick = {
                            modal.close {
                                pairSheet = false
                                NetworkService.start(context)
                                network.pair("${found.host}:${found.port}")
                            }
                        },
                    )
                }
            }
            SheetList {
                SheetListRow(
                    title = stringResource(R.string.pair_enter_title),
                    subtitle = stringResource(R.string.pair_enter_body),
                    leading = { SquareIcon(Icons.Outlined.Keyboard) },
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    onClick = {
                        modal.close {
                            pairSheet = false
                            typingAddress = true
                        }
                    },
                )
                SheetListDivider()
                val openFor = state.pairingOpenUntil - now
                if (openFor > 0) {
                    SheetListRow(
                        title = stringResource(R.string.devices_pairing_open, "%d:%02d".format(openFor / 60_000, openFor / 1000 % 60)),
                        subtitle = stringResource(R.string.pair_open_hint),
                        onClick = null,
                        leading = { SquareIcon(Icons.Outlined.Wifi, active = true) },
                        trailing = { TextButton(onClick = network::closePairing) { Text(stringResource(R.string.common_close)) } },
                    )
                } else {
                    SheetListRow(
                        title = stringResource(R.string.pair_show_title),
                        subtitle = stringResource(R.string.pair_show_body),
                        leading = { SquareIcon(Icons.Outlined.Wifi) },
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
            SheetList {
                SheetListRow(
                    title = stringResource(R.string.device_fingerprint),
                    subtitle = readableFingerprint(network.identity.fingerprint),
                    onClick = null,
                    leading = { SquareIcon(Icons.Outlined.Fingerprint) },
                )
                if (state.listening) {
                    state.addresses.forEach {
                        SheetListDivider()
                        AddressRow(stringResource(R.string.address_local), it)
                    }
                    SheetListDivider()
                    PublicAddress(state.port)
                }
            }
            if (!state.listening) SheetNote(stringResource(R.string.devices_details_off))
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
        val status = listOfNotNull(
            stringResource(if (online) R.string.device_online else R.string.device_offline),
            latency?.takeIf { online }?.let { stringResource(R.string.device_latency, it) },
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
        SheetTitle(device.name, subtitle = status)
        SheetList {
            SheetListRow(
                title = stringResource(R.string.device_receive),
                subtitle = if (online && device.receive) offersText(offered) else stringResource(R.string.device_receive_hint),
                role = Role.Switch,
                leading = { SquareIcon(Icons.Outlined.Download, active = device.receive) },
                trailing = { Switch(checked = device.receive, onCheckedChange = null) },
                onClick = { network.setReceive(device.publicKey, !device.receive) },
            )
            SheetListDivider()
            SheetListRow(
                title = stringResource(R.string.device_send),
                subtitle = stringResource(R.string.device_send_hint),
                role = Role.Switch,
                leading = { SquareIcon(Icons.Outlined.Upload, active = device.send) },
                trailing = { Switch(checked = device.send, onCheckedChange = null) },
                onClick = { network.setSend(device.publicKey, !device.send) },
            )
        }
        if (streams.isNotEmpty()) {
            SheetList(label = stringResource(R.string.device_streams)) {
                streams.forEachIndexed { index, stream ->
                    if (index > 0) SheetListDivider()
                    StreamReadout(stream)
                }
            }
        }
        refused.forEach { (feed, reason) ->
            Banner(Icons.Outlined.ErrorOutline, error = true) {
                Text(stringResource(refusalText(reason), feed), style = MaterialTheme.typography.bodySmall)
            }
        }
        SheetList {
            SheetListRow(
                title = stringResource(R.string.device_fingerprint),
                subtitle = readableFingerprint(device.fingerprint),
                onClick = null,
                leading = { SquareIcon(Icons.Outlined.Fingerprint) },
            )
            device.address?.let {
                SheetListDivider()
                SheetListRow(title = stringResource(R.string.device_address), subtitle = it, onClick = null, leading = { SquareIcon(Icons.Outlined.Wifi) })
            }
        }
        ModalButton(stringResource(R.string.device_unpair), ButtonKind.Danger, Modifier.fillMaxWidth()) { confirming = true }
    }
    if (confirming) {
        ConfirmModal(
            title = stringResource(R.string.device_unpair_title, device.name),
            message = stringResource(R.string.device_unpair_body),
            action = stringResource(R.string.device_unpair),
            cancel = stringResource(R.string.common_cancel),
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                onDismiss()
                network.unpair(device.publicKey)
            },
        )
    }
}

@Composable
private fun AddressRow(label: String, address: String) {
    val context = LocalContext.current
    val copied = stringResource(R.string.address_copied)
    SheetListRow(
        title = label,
        subtitle = address,
        leading = { SquareIcon(Icons.Outlined.Wifi) },
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
    SheetListRow(
        title = stringResource(R.string.address_public),
        subtitle = if (failed) stringResource(R.string.address_failed) else stringResource(if (looking) R.string.address_looking else R.string.address_lookup),
        leading = { SquareIcon(Icons.Outlined.Public) },
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
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(stream.headline(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(stream.details(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = secondaryText())
    }
}

@Composable
private fun AddressDialog(onDismiss: () -> Unit, onPair: (String) -> Unit) {
    TextModal(
        title = stringResource(R.string.pair_address_title),
        message = stringResource(R.string.pair_address_help),
        placeholder = stringResource(R.string.pair_address_hint),
        action = stringResource(R.string.pair_start),
        onDismiss = onDismiss,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        onDone = onPair,
    )
}

@Composable
private fun PairingDialog(pairing: PairingUi, network: Network) {
    // A new state gets a new modal, so one closed by its button never lingers until the next state arrives.
    key(pairing) {
        when (pairing) {
            PairingUi.None -> Unit
            is PairingUi.Connecting -> ChoiceModal(
                title = stringResource(R.string.pair_address_title),
                message = stringResource(R.string.pair_connecting, pairing.address),
                actions = listOf(ModalAction(stringResource(R.string.common_cancel), ButtonKind.Quiet, onClick = network::cancelPairing)),
                onDismiss = {},
                dismissible = false,
                body = { Spinner() },
            )
            is PairingUi.Code -> ChoiceModal(
                title = stringResource(R.string.pair_code_title, pairing.remoteName),
                message = if (pairing.confirmed) stringResource(R.string.pair_waiting, pairing.remoteName) else stringResource(R.string.pair_code_body),
                actions = if (pairing.confirmed) {
                    listOf(ModalAction(stringResource(R.string.common_cancel), ButtonKind.Quiet, onClick = network::cancelPairing))
                } else {
                    listOf(
                        ModalAction(stringResource(R.string.pair_nomatch), ButtonKind.Quiet) { network.confirmPairing(false) },
                        ModalAction(stringResource(R.string.pair_match)) { network.confirmPairing(true) },
                    )
                },
                onDismiss = {},
                dismissible = false,
                body = {
                    Text(
                        pairing.code.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                },
            )
            is PairingUi.Failed -> ChoiceModal(
                title = stringResource(R.string.pair_failed_title),
                message = stringResource(R.string.pair_failed_body, pairing.reason),
                actions = listOf(ModalAction(stringResource(R.string.common_ok), onClick = network::dismissPairing)),
                onDismiss = network::dismissPairing,
            )
        }
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}
