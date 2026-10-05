package dev.mkzk.manifold.hub.net

internal class ControlChannel(
    private val transmit: (ByteArray) -> Unit,
    private val onMessage: (Control) -> Unit,
    private val onFailed: () -> Unit,
) {
    private class Waiting(val id: Int, val bytes: ByteArray, var sentAt: Long, var attempts: Int)

    private var nextId = 1
    private val waiting = LinkedHashMap<Int, Waiting>()
    private val seen = ReplayWindow(256)
    private var newestFeedList = 0
    private var failed = false

    fun send(control: Control, now: Long) {
        val id = nextId++
        val bytes = ControlCodec.encode(id, control)
        if (ControlCodec.needsAck(control)) waiting[id] = Waiting(id, bytes, now, attempts = 1)
        transmit(bytes)
    }

    fun receive(payload: ByteArray) {
        when (val decoded = ControlCodec.decode(payload)) {
            null -> Unit
            is Decoded.Ack -> waiting.remove(decoded.id)
            is Decoded.Unrecognized -> transmit(ControlCodec.encodeAck(decoded.id))
            is Decoded.Message -> {
                if (ControlCodec.needsAck(decoded.control)) transmit(ControlCodec.encodeAck(decoded.id))
                // The ack above is sent again for a repeat: the first one may have been lost.
                if (!seen.accept(decoded.id.toLong())) return
                val control = decoded.control
                if (control is Control.FeedList) {
                    if (decoded.id < newestFeedList) return
                    newestFeedList = decoded.id
                }
                onMessage(control)
            }
        }
    }

    fun tick(now: Long) {
        if (failed) return
        for (message in waiting.values) {
            if (now - message.sentAt < RESEND_AFTER_MS) continue
            if (message.attempts >= MAX_ATTEMPTS) {
                failed = true
                onFailed()
                return
            }
            message.attempts++
            message.sentAt = now
            transmit(message.bytes)
        }
    }

    companion object {
        const val RESEND_AFTER_MS = 400L
        const val MAX_ATTEMPTS = 8
    }
}
