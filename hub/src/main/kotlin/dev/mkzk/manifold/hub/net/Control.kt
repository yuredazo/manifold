package dev.mkzk.manifold.hub.net

import dev.mkzk.manifold.Manifold
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/** [soundOnly] has no picture, and its size is 0. It travels in bit 1 of the audio byte, so an older hub still reads "has audio". */
internal data class FeedInfo(
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hasAudio: Boolean,
    val soundOnly: Boolean = false,
)

internal sealed interface Control {
    data object Ping : Control

    data object PairConfirm : Control

    data object PairReject : Control

    data object Bye : Control

    data class FeedList(val feeds: List<FeedInfo>) : Control

    data class Subscribe(
        val streamId: Int,
        val feed: String,
        val width: Int,
        val height: Int,
        val bitrateKbps: Int,
        val audio: Boolean = false,
        /** Older senders of this message leave it out, which means [DEFAULT_FPS]. */
        val fps: Int = DEFAULT_FPS,
    ) : Control {
        companion object {
            const val DEFAULT_FPS = 30
            const val MAX_FPS = 60
        }
    }

    /** A reason this version does not know reads as [FAILED]. */
    enum class Refusal(val code: Int) {
        NOT_SHARED(1),
        NOT_FOUND(2),
        BUSY(3),
        FAILED(4),
        ;

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code } ?: FAILED
        }
    }

    data class SubscribeRefused(val streamId: Int, val reason: Refusal) : Control

    data class Unsubscribe(val streamId: Int) : Control

    data class KeyframeRequest(val streamId: Int) : Control

    data class TimeRequest(val sentAt: Long) : Control

    data class TimeReply(val sentAt: Long) : Control

    /** Empty [indexes] means the whole frame. Never resent itself. */
    data class Nack(val streamId: Int, val frameId: Int, val indexes: List<Int>) : Control

    /** [jitterMs10] is in tenths of a millisecond. Never resent. */
    data class StreamReport(
        val streamId: Int,
        val windowMs: Int,
        val fragments: Int,
        val resendRequests: Int,
        val lostFrames: Int,
        val jitterMs10: Int,
    ) : Control

    /**
     * What the publisher sees of a stream, once a second. Times are in tenths of a millisecond so a fast
     * encoder still shows a number.
     */
    data class SenderStats(val streamId: Int, val bitrateKbps: Int, val fps10: Int, val encodeMs10: Int, val sendMs10: Int) : Control
}

internal sealed interface Decoded {
    data class Ack(val id: Int) : Decoded
    data class Message(val id: Int, val control: Control) : Decoded

    /**
     * A header this version can read with a kind or body it cannot. It is still acknowledged, or its sender
     * would resend it until it gave up on the link.
     */
    data class Unrecognized(val id: Int) : Decoded
}

/** Input from the other device is parsed defensively: nothing throws. */
internal object ControlCodec {
    const val MAX_FEEDS = 64

    private const val ACK = 0
    private const val PING = 1
    private const val PAIR_CONFIRM = 2
    private const val PAIR_REJECT = 3
    private const val BYE = 4
    private const val FEED_LIST = 5
    private const val SUBSCRIBE = 6
    private const val UNSUBSCRIBE = 7
    private const val KEYFRAME_REQUEST = 8
    private const val TIME_REQUEST = 9
    private const val TIME_REPLY = 10
    private const val SENDER_STATS = 11
    private const val NACK = 12
    private const val STREAM_REPORT = 13
    private const val SUBSCRIBE_REFUSED = 14
    private const val AUDIO_BIT = 1
    private const val SOUND_ONLY_BIT = 2
    const val MAX_NACK_INDEXES = 200

    /** Pings and measurements are never acknowledged or resent: a late measurement is worth nothing. */
    fun needsAck(control: Control) = when (control) {
        Control.Ping, is Control.TimeRequest, is Control.TimeReply, is Control.SenderStats, is Control.Nack, is Control.StreamReport -> false
        else -> true
    }

    fun encodeAck(id: Int): ByteArray = ByteBuffer.allocate(5).put(ACK.toByte()).putInt(id).array()

    fun encode(id: Int, control: Control): ByteArray {
        val (kind, body) = when (control) {
            Control.Ping -> PING to ByteArray(0)
            Control.PairConfirm -> PAIR_CONFIRM to ByteArray(0)
            Control.PairReject -> PAIR_REJECT to ByteArray(0)
            Control.Bye -> BYE to ByteArray(0)
            is Control.FeedList -> FEED_LIST to encodeFeeds(control.feeds)
            is Control.Subscribe -> SUBSCRIBE to encodeSubscribe(control)
            is Control.SubscribeRefused -> SUBSCRIBE_REFUSED to ByteBuffer.allocate(3)
                .putShort(control.streamId.toShort())
                .put(control.reason.code.toByte())
                .array()
            is Control.Unsubscribe -> UNSUBSCRIBE to ByteBuffer.allocate(2).putShort(control.streamId.toShort()).array()
            is Control.KeyframeRequest -> KEYFRAME_REQUEST to ByteBuffer.allocate(2).putShort(control.streamId.toShort()).array()
            is Control.TimeRequest -> TIME_REQUEST to ByteBuffer.allocate(8).putLong(control.sentAt).array()
            is Control.TimeReply -> TIME_REPLY to ByteBuffer.allocate(8).putLong(control.sentAt).array()
            is Control.Nack -> NACK to encodeNack(control)
            is Control.StreamReport -> STREAM_REPORT to ByteBuffer.allocate(12)
                .putShort(control.streamId.toShort())
                .putShort(control.windowMs.coerceIn(0, 0xFFFF).toShort())
                .putShort(control.fragments.coerceIn(0, 0xFFFF).toShort())
                .putShort(control.resendRequests.coerceIn(0, 0xFFFF).toShort())
                .putShort(control.lostFrames.coerceIn(0, 0xFFFF).toShort())
                .putShort(control.jitterMs10.coerceIn(0, 0xFFFF).toShort())
                .array()
            is Control.SenderStats -> SENDER_STATS to ByteBuffer.allocate(10)
                .putShort(control.streamId.toShort())
                .putShort(control.bitrateKbps.coerceIn(0, 0xFFFF).toShort())
                .putShort(control.fps10.coerceIn(0, 0xFFFF).toShort())
                .putShort(control.encodeMs10.coerceIn(0, 0xFFFF).toShort())
                .putShort(control.sendMs10.coerceIn(0, 0xFFFF).toShort())
                .array()
        }
        return ByteBuffer.allocate(5 + body.size).put(kind.toByte()).putInt(id).put(body).array()
    }

    /** A request for more pieces than fit is a request for the whole frame. */
    private fun encodeNack(request: Control.Nack): ByteArray {
        val indexes = if (request.indexes.size > MAX_NACK_INDEXES) emptyList() else request.indexes
        val out = ByteBuffer.allocate(8 + 2 * indexes.size)
            .putShort(request.streamId.toShort())
            .putInt(request.frameId)
            .putShort(indexes.size.toShort())
        indexes.forEach { out.putShort(it.toShort()) }
        return out.array()
    }

    private fun readNack(reader: ByteBuffer): Control.Nack? {
        val streamId = reader.getShort().toInt() and 0xFFFF
        val frameId = reader.getInt()
        val count = reader.getShort().toInt() and 0xFFFF
        if (count > MAX_NACK_INDEXES) return null
        val indexes = List(count) { reader.getShort().toInt() and 0xFFFF }
        return Control.Nack(streamId, frameId, indexes)
    }

    private fun encodeSubscribe(request: Control.Subscribe): ByteArray {
        val name = request.feed.toByteArray(Charsets.UTF_8)
        require(name.size <= 255) { "feed name too long" }
        val fpsBytes = if (request.fps != Control.Subscribe.DEFAULT_FPS) 1 else 0
        return ByteBuffer.allocate(10 + name.size + fpsBytes)
            .putShort(request.streamId.toShort())
            .putShort(request.width.toShort())
            .putShort(request.height.toShort())
            .putShort(request.bitrateKbps.toShort())
            .put(name.size.toByte())
            .put(name)
            .put(if (request.audio) 1 else 0)
            .also { if (request.fps != Control.Subscribe.DEFAULT_FPS) it.put(request.fps.coerceIn(1, Control.Subscribe.MAX_FPS).toByte()) }
            .array()
    }

    /** A request with a name or a size the hub would not accept from a local app is not a request. */
    private fun readSubscribe(reader: ByteBuffer): Control.Subscribe? {
        val streamId = reader.getShort().toInt() and 0xFFFF
        val width = reader.getShort().toInt() and 0xFFFF
        val height = reader.getShort().toInt() and 0xFFFF
        val bitrate = reader.getShort().toInt() and 0xFFFF
        val name = ByteArray(reader.get().toInt() and 0xFF).also { reader.get(it) }.toString(Charsets.UTF_8)
        if (!Manifold.isValidName(name)) return null
        if (width !in 1..Manifold.MAX_DIMENSION || height !in 1..Manifold.MAX_DIMENSION || bitrate < 1) return null
        // Older senders of this message stop after the name, or after the audio byte.
        val audio = reader.hasRemaining() && reader.get().toInt() != 0
        val fps = if (reader.hasRemaining()) (reader.get().toInt() and 0xFF).coerceIn(1, Control.Subscribe.MAX_FPS) else Control.Subscribe.DEFAULT_FPS
        return Control.Subscribe(streamId, name.trim(), width, height, bitrate, audio, fps)
    }

    private fun encodeFeeds(feeds: List<FeedInfo>): ByteArray {
        val encoded = ArrayList<ByteArray>()
        var used = 2
        for (feed in feeds.take(MAX_FEEDS)) {
            val name = feed.name.toByteArray(Charsets.UTF_8)
            if (name.size > 255) continue
            val entry = ByteBuffer.allocate(1 + name.size + 6)
                .put(name.size.toByte()).put(name)
                .putShort(feed.width.toShort()).putShort(feed.height.toShort())
                .put(feed.fps.toByte()).put(((if (feed.hasAudio) AUDIO_BIT else 0) or (if (feed.soundOnly) SOUND_ONLY_BIT else 0)).toByte())
                .array()
            if (used + entry.size > Session.MAX_PAYLOAD - 5) break
            encoded += entry
            used += entry.size
        }
        return ByteBuffer.allocate(used).putShort(encoded.size.toShort()).also { out -> encoded.forEach(out::put) }.array()
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < 5) return null
        val reader = ByteBuffer.wrap(bytes)
        val kind = reader.get().toInt()
        val id = reader.getInt()
        return try {
            when (kind) {
                ACK -> Decoded.Ack(id)
                PING -> Decoded.Message(id, Control.Ping)
                PAIR_CONFIRM -> Decoded.Message(id, Control.PairConfirm)
                PAIR_REJECT -> Decoded.Message(id, Control.PairReject)
                BYE -> Decoded.Message(id, Control.Bye)
                FEED_LIST -> Decoded.Message(id, Control.FeedList(readFeeds(reader)))
                SUBSCRIBE -> readSubscribe(reader)?.let { Decoded.Message(id, it) }
                SUBSCRIBE_REFUSED -> Decoded.Message(id, Control.SubscribeRefused(
                    streamId = reader.getShort().toInt() and 0xFFFF,
                    reason = Control.Refusal.of(reader.get().toInt() and 0xFF),
                ))
                UNSUBSCRIBE -> Decoded.Message(id, Control.Unsubscribe(reader.getShort().toInt() and 0xFFFF))
                KEYFRAME_REQUEST -> Decoded.Message(id, Control.KeyframeRequest(reader.getShort().toInt() and 0xFFFF))
                TIME_REQUEST -> Decoded.Message(id, Control.TimeRequest(reader.getLong()))
                TIME_REPLY -> Decoded.Message(id, Control.TimeReply(reader.getLong()))
                NACK -> readNack(reader)?.let { Decoded.Message(id, it) }
                STREAM_REPORT -> Decoded.Message(id, Control.StreamReport(
                    streamId = reader.getShort().toInt() and 0xFFFF,
                    windowMs = reader.getShort().toInt() and 0xFFFF,
                    fragments = reader.getShort().toInt() and 0xFFFF,
                    resendRequests = reader.getShort().toInt() and 0xFFFF,
                    lostFrames = reader.getShort().toInt() and 0xFFFF,
                    jitterMs10 = reader.getShort().toInt() and 0xFFFF,
                ))
                SENDER_STATS -> Decoded.Message(id, Control.SenderStats(
                    streamId = reader.getShort().toInt() and 0xFFFF,
                    bitrateKbps = reader.getShort().toInt() and 0xFFFF,
                    fps10 = reader.getShort().toInt() and 0xFFFF,
                    encodeMs10 = reader.getShort().toInt() and 0xFFFF,
                    sendMs10 = reader.getShort().toInt() and 0xFFFF,
                ))
                else -> null
            } ?: Decoded.Unrecognized(id)
        } catch (_: BufferUnderflowException) {
            Decoded.Unrecognized(id)
        }
    }

    private fun readFeeds(reader: ByteBuffer): List<FeedInfo> {
        val count = reader.getShort().toInt() and 0xFFFF
        val feeds = ArrayList<FeedInfo>()
        repeat(minOf(count, MAX_FEEDS)) {
            val name = ByteArray(reader.get().toInt() and 0xFF).also { reader.get(it) }.toString(Charsets.UTF_8)
            val width = reader.getShort().toInt() and 0xFFFF
            val height = reader.getShort().toInt() and 0xFFFF
            val fps = reader.get().toInt() and 0xFF
            val flags = reader.get().toInt() and 0xFF
            if (Manifold.isValidName(name) && width <= Manifold.MAX_DIMENSION && height <= Manifold.MAX_DIMENSION) {
                feeds += FeedInfo(name.trim(), width, height, fps, flags != 0, soundOnly = flags and SOUND_ONLY_BIT != 0)
            }
        }
        return feeds
    }
}
