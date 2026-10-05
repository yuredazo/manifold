package dev.mkzk.manifold.hub.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class Stream(val id: Byte) {
    Control(1),
    Video(2),
    Audio(3),
    ;

    companion object {
        fun of(id: Byte) = entries.firstOrNull { it.id == id }
    }
}

internal class Opened(val stream: Stream, val payload: ByteArray)

/** Remembers which counters were seen, so a recorded packet cannot be replayed. Packets may arrive out of order within [size]. */
internal class ReplayWindow(private val size: Int = 1024) {
    private val seen = BooleanArray(size)
    private var newest = -1L

    fun accept(counter: Long): Boolean {
        if (counter < 0) return false
        if (counter > newest) {
            if (counter - newest >= size) {
                seen.fill(false)
            } else {
                for (c in newest + 1..counter) seen[(c % size).toInt()] = false
            }
            newest = counter
            seen[(counter % size).toInt()] = true
            return true
        }
        if (newest - counter >= size) return false
        val slot = (counter % size).toInt()
        if (seen[slot]) return false
        seen[slot] = true
        return true
    }
}

/** The counter is the nonce and must never repeat, so [seal] throws long before it could run out. */
internal class Session(
    private val localIndex: Int,
    private val remoteIndex: Int,
    private val keys: TransportKeys,
) {
    private var sendCounter = 0L
    private val window = ReplayWindow()

    fun seal(stream: Stream, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload of ${payload.size} bytes does not fit one datagram" }
        check(sendCounter < REKEY_LIMIT) { "this session has sent too many packets and must be replaced" }
        val counter = sendCounter++
        val header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
            .put(TYPE_DATA).putInt(remoteIndex).putLong(counter).array()
        val body = Crypto.seal(keys.send, counter, header, byteArrayOf(stream.id) + payload)
        return header + body
    }

    fun open(datagram: ByteArray): Opened? {
        if (datagram.size < HEADER_LENGTH + Crypto.TAG_LENGTH + 1) return null
        val header = datagram.copyOfRange(0, HEADER_LENGTH)
        val reader = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (reader.get() != TYPE_DATA) return null
        if (reader.getInt() != localIndex) return null
        val counter = reader.getLong()
        val plain = Crypto.open(keys.receive, counter, header, datagram.copyOfRange(HEADER_LENGTH, datagram.size)) ?: return null
        val stream = Stream.of(plain[0]) ?: return null
        if (!window.accept(counter)) return null
        return Opened(stream, plain.copyOfRange(1, plain.size))
    }

    companion object {
        const val TYPE_DATA: Byte = 3
        const val HEADER_LENGTH = 13
        const val MAX_DATAGRAM = 1200

        const val MAX_PAYLOAD = MAX_DATAGRAM - HEADER_LENGTH - Crypto.TAG_LENGTH - 1

        private const val REKEY_LIMIT = 1L shl 40
    }
}
