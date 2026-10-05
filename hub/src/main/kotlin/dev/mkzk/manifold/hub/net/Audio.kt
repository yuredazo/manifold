package dev.mkzk.manifold.hub.net

import java.nio.ByteBuffer

internal class AudioPacket(val streamId: Int, val timestamp: Int, val frame: ByteArray) {

    companion object {
        const val HEADER_LENGTH = 6

        /** AAC-LC at 48 kHz in two channels is the only format, so it is not sent along. */
        fun encode(streamId: Int, timestamp: Int, frame: ByteArray): ByteArray =
            ByteBuffer.allocate(HEADER_LENGTH + frame.size).putShort(streamId.toShort()).putInt(timestamp).put(frame).array()

        fun decode(payload: ByteArray): AudioPacket? {
            if (payload.size <= HEADER_LENGTH) return null
            val reader = ByteBuffer.wrap(payload)
            val streamId = reader.getShort().toInt() and 0xFFFF
            val timestamp = reader.getInt()
            return AudioPacket(streamId, timestamp, payload.copyOfRange(HEADER_LENGTH, payload.size))
        }
    }
}
