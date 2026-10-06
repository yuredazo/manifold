package dev.mkzk.manifold.hub.network.protocol

import dev.mkzk.manifold.Manifold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.fromHex(): ByteArray? {
    if (length % 2 != 0) return null
    return try {
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    } catch (_: NumberFormatException) {
        null
    }
}

internal class Identity(val keys: KeyPair, val name: String) {
    val fingerprint: String get() = fingerprintOf(keys.public)
}

/** SHA-256 of a public key, in hex. This, not an address or a name, is what identifies a device. */
internal fun fingerprintOf(publicKey: ByteArray): String = Crypto.sha256(publicKey).toHex()

internal fun readableFingerprint(fingerprint: String): String = fingerprint.take(16).chunked(4).joinToString(" ")

internal data class Device(
    val publicKey: String,
    val name: String,
    val address: String?,
    val receive: Boolean = false,
    val send: Boolean = false,
) {
    val fingerprint: String get() = publicKey.fromHex()?.let(::fingerprintOf).orEmpty()
}

internal class DeviceBook(stored: String?, private val save: (String) -> Unit) {

    private val entries = LinkedHashMap<String, Device>()
    private val flow = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = flow

    init {
        stored?.lineSequence()?.forEach(::parse)
        flow.value = entries.values.toList()
    }

    fun find(publicKey: ByteArray): Device? = entries[publicKey.toHex()]

    fun find(publicKeyHex: String): Device? = entries[publicKeyHex]

    fun put(device: Device) {
        entries[device.publicKey] = device.copy(name = clean(device.name))
        changed()
    }

    fun update(publicKeyHex: String, change: (Device) -> Device) {
        val current = entries[publicKeyHex] ?: return
        entries[publicKeyHex] = change(current).copy(publicKey = publicKeyHex)
        changed()
    }

    fun remove(publicKeyHex: String) {
        if (entries.remove(publicKeyHex) != null) changed()
    }

    private fun changed() {
        flow.value = entries.values.toList()
        save(entries.values.joinToString("\n") { listOf(it.publicKey, it.name, it.address.orEmpty(), it.receive, it.send).joinToString("\t") })
    }

    private fun parse(line: String) {
        val parts = line.split('\t')
        if (parts.size != 5) return
        val key = parts[0]
        if (key.length != 2 * Crypto.KEY_LENGTH || key.fromHex() == null) return
        entries[key] = Device(key, parts[1], parts[2].ifEmpty { null }, parts[3] == "true", parts[4] == "true")
    }

    private fun clean(name: String) = name.replace('\t', ' ').replace('\n', ' ').trim().take(Manifold.MAX_NAME_LENGTH)
}
