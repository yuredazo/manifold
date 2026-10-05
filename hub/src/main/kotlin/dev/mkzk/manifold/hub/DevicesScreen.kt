package dev.mkzk.manifold.hub

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.net.Device
import dev.mkzk.manifold.hub.net.StreamSnapshot
import dev.mkzk.manifold.hub.net.details
import dev.mkzk.manifold.hub.net.headline
import dev.mkzk.manifold.hub.net.readableFingerprint
import kotlinx.coroutines.delay

@Composable
internal fun DevicesScreen(network: Network, state: NetworkState, devices: List<Device>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var typingAddress by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            ThisDevice(network, state) { on -> if (on) NetworkService.start(context) else NetworkService.stop(context) }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { typingAddress = true }) { Text(stringResource(R.string.devices_pair)) }
                val openFor = state.pairingOpenUntil - now
                if (openFor > 0) {
                    OutlinedButton(onClick = network::closePairing) {
                        Text(stringResource(R.string.devices_pairing_open, "%d:%02d".format(openFor / 60_000, openFor / 1000 % 60)))
                    }
                } else {
                    OutlinedButton(onClick = {
                        NetworkService.start(context)
                        network.openPairing()
                    }) { Text(stringResource(R.string.devices_allow_pairing)) }
                }
            }
        }
        item { Header(R.string.devices_paired_header) }
        if (devices.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.devices_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(devices, key = { it.publicKey }) { device ->
            DeviceRow(
                network,
                device,
                online = device.publicKey in state.online,
                offered = state.remoteFeeds[device.publicKey].orEmpty().map { it.name },
                streams = state.streams.filter { it.deviceKey == device.publicKey },
            )
        }
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
private fun ThisDevice(network: Network, state: NetworkState, onListening: (Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(network.identity.name, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.devices_fingerprint, readableFingerprint(network.identity.fingerprint)),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.devices_accept), style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        !state.listening -> stringResource(R.string.devices_off)
                        state.addresses.isEmpty() -> stringResource(R.string.devices_no_address)
                        else -> stringResource(R.string.devices_listening, state.addresses.joinToString(", "))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.listening, onCheckedChange = onListening)
        }
    }
}

@Composable
private fun Header(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun DeviceRow(network: Network, device: Device, online: Boolean, offered: List<String>, streams: List<StreamSnapshot>) {
    var confirming by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(device.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (online) R.string.device_online else R.string.device_offline),
                style = MaterialTheme.typography.labelMedium,
                color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            readableFingerprint(device.fingerprint) + (device.address?.let { "  ·  $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(R.string.device_receive, device.receive) { network.setReceive(device.publicKey, it) }
        if (online && device.receive) {
            Text(
                if (offered.isEmpty()) stringResource(R.string.device_offers_nothing) else stringResource(R.string.device_offers, offered.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        streams.forEach { StreamReadout(it) }
        SwitchRow(R.string.device_send, device.send) { network.setSend(device.publicKey, it) }
        TextButton(onClick = { confirming = true }) {
            Text(stringResource(R.string.device_unpair), color = MaterialTheme.colorScheme.error)
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
                    network.unpair(device.publicKey)
                }) { Text(stringResource(R.string.device_unpair), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    HorizontalDivider()
}

@Composable
private fun StreamReadout(stream: StreamSnapshot) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text(stream.headline(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(
            stream.details(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
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
