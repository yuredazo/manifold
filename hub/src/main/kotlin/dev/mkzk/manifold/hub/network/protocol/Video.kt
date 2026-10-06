package dev.mkzk.manifold.hub.network.protocol

import java.nio.ByteBuffer

/** [timestamp] counts 90 kHz ticks. [captureLagMs] never leaves the publishing side. */
internal class Frame(val id: Int, val timestamp: Int, val keyframe: Boolean, val encoded: ByteArray, val captureLagMs: Float = -1f)

internal object VideoPacket {
    const val STREAM_ID_LENGTH = 2

    fun encode(streamId: Int, fragment: ByteArray): ByteArray =
        ByteBuffer.allocate(STREAM_ID_LENGTH + fragment.size).putShort(streamId.toShort()).put(fragment).array()

    fun streamOf(payload: ByteArray): Int? =
        if (payload.size <= STREAM_ID_LENGTH) null else ByteBuffer.wrap(payload).getShort().toInt() and 0xFFFF

    fun fragmentOf(payload: ByteArray): ByteArray = payload.copyOfRange(STREAM_ID_LENGTH, payload.size)
}

internal object Fragmenter {
    const val HEADER_LENGTH = 13
    const val MAX_CHUNK = Session.MAX_PAYLOAD - VideoPacket.STREAM_ID_LENGTH - HEADER_LENGTH
    const val MAX_FRAGMENTS = 4096

    private const val FLAG_KEYFRAME = 1

    fun split(frame: Frame): List<ByteArray> {
        val count = maxOf(1, (frame.encoded.size + MAX_CHUNK - 1) / MAX_CHUNK)
        require(count <= MAX_FRAGMENTS) { "a frame of ${frame.encoded.size} bytes is too large" }
        return List(count) { index ->
            val from = index * MAX_CHUNK
            val to = minOf(frame.encoded.size, from + MAX_CHUNK)
            ByteBuffer.allocate(HEADER_LENGTH + (to - from))
                .putInt(frame.id)
                .putInt(frame.timestamp)
                .put(if (frame.keyframe) FLAG_KEYFRAME.toByte() else 0)
                .putShort(index.toShort())
                .putShort(count.toShort())
                .put(frame.encoded, from, to - from)
                .array()
        }
    }

    fun isKeyframe(flags: Int) = flags and FLAG_KEYFRAME != 0
}
