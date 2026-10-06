package dev.mkzk.manifold.hub.network.protocol

/** What a device broadcasts while it lets others pair with it. [id] is the first 8 bytes of its fingerprint, in hex. */
internal class Beacon(val id: String, val port: Int, val name: String)

/**
 * `"MNFD" | version(1) | port(2) | id(8) | name length(1) | name`, big-endian. Nothing in it is authenticated, so it only
 * says where to try; the pairing code is what establishes who answers.
 */
internal object BeaconCodec {
    const val PORT = 47201
    const val MAX_NAME_BYTES = 64

    private val MAGIC = "MNFD".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val ID_BYTES = 8
    private const val HEADER = 16

    fun encode(beacon: Beacon): ByteArray {
        val id = requireNotNull(beacon.id.fromHex()) { "the id is not hex" }
        require(id.size == ID_BYTES) { "the id is $ID_BYTES bytes" }
        require(beacon.port in 1..0xFFFF) { "the port is out of range" }
        var name = beacon.name
        while (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) name = name.dropLast(1)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val head = byteArrayOf(VERSION.toByte(), (beacon.port shr 8).toByte(), beacon.port.toByte())
        return MAGIC + head + id + byteArrayOf(nameBytes.size.toByte()) + nameBytes
    }

    fun decode(bytes: ByteArray, length: Int = bytes.size): Beacon? {
        if (length < HEADER || length > bytes.size) return null
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC) || bytes[4].toInt() != VERSION) return null
        val port = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        val nameLength = bytes[15].toInt() and 0xFF
        if (port == 0 || nameLength > MAX_NAME_BYTES || length < HEADER + nameLength) return null
        val name = String(bytes, HEADER, nameLength, Charsets.UTF_8).filterNot { it.isISOControl() }.trim()
        if (name.isEmpty()) return null
        return Beacon(bytes.copyOfRange(7, 7 + ID_BYTES).toHex(), port, name)
    }
}
