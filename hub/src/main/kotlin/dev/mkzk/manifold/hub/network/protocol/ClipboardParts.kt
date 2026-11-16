package dev.mkzk.manifold.hub.network.protocol

import kotlin.math.min

internal object ClipboardParts {
    // Null when the text is over the size limit; such a copy is not sent at all.
    fun of(streamId: Int, seq: Int, text: String): List<Control.Clipboard>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > ControlCodec.MAX_CLIPBOARD_BYTES) return null
        val chunk = ControlCodec.CLIPBOARD_CHUNK
        val count = (bytes.size + chunk - 1) / chunk
        return (0 until count).map { part ->
            Control.Clipboard(streamId, seq, part, count, bytes.copyOfRange(part * chunk, min(bytes.size, (part + 1) * chunk)))
        }
    }
}

// Parts can arrive out of order or twice. A newer copy replaces one still being put together.
internal class ClipboardAssembler {
    private var seq = 0
    private var received = arrayOfNulls<ByteArray>(0)

    fun add(part: Control.Clipboard): String? {
        if (part.count <= 0 || part.count > ControlCodec.MAX_CLIPBOARD_PARTS || part.part !in 0 until part.count) return null
        if (part.seq != seq || received.size != part.count) {
            seq = part.seq
            received = arrayOfNulls(part.count)
        }
        received[part.part] = part.data
        if (received.any { it == null }) return null
        val bytes = received.fold(ByteArray(0)) { all, piece -> all + piece!! }
        received = arrayOfNulls(0)
        if (bytes.size > ControlCodec.MAX_CLIPBOARD_BYTES) return null
        return bytes.toString(Charsets.UTF_8)
    }
}
