package dev.mkzk.manifold.hub.network.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class Address(val host: String, val port: Int) {
    override fun toString() = "$host:$port"

    companion object {
        fun parse(text: String, defaultPort: Int): Address? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val colon = trimmed.lastIndexOf(':')
            val host = if (colon < 0) trimmed else trimmed.substring(0, colon)
            val port = if (colon < 0) defaultPort else trimmed.substring(colon + 1).toIntOrNull() ?: return null
            if (host.isEmpty() || host.contains(':') || port !in 1..65535) return null
            return Address(host, port)
        }
    }
}

/** The receiver index is 0 in the first message, before the other side has picked one. */
internal object Wire {
    const val TYPE_PAIR: Byte = 1
    const val TYPE_HELLO: Byte = 2

    const val HANDSHAKE_HEADER = 10

    class Handshake(val type: Byte, val step: Int, val senderIndex: Int, val receiverIndex: Int, val message: ByteArray)

    fun encode(packet: Handshake): ByteArray =
        ByteBuffer.allocate(HANDSHAKE_HEADER + packet.message.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(packet.type)
            .put(packet.step.toByte())
            .putInt(packet.senderIndex)
            .putInt(packet.receiverIndex)
            .put(packet.message)
            .array()

    fun decodeHandshake(datagram: ByteArray): Handshake? {
        if (datagram.size < HANDSHAKE_HEADER) return null
        val reader = ByteBuffer.wrap(datagram).order(ByteOrder.LITTLE_ENDIAN)
        val type = reader.get()
        if (type != TYPE_PAIR && type != TYPE_HELLO) return null
        val step = reader.get().toInt()
        val sender = reader.getInt()
        val receiver = reader.getInt()
        return Handshake(type, step, sender, receiver, datagram.copyOfRange(HANDSHAKE_HEADER, datagram.size))
    }

    fun dataReceiver(datagram: ByteArray): Int? {
        if (datagram.size < Session.HEADER_LENGTH || datagram[0] != Session.TYPE_DATA) return null
        return ByteBuffer.wrap(datagram, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }
}
