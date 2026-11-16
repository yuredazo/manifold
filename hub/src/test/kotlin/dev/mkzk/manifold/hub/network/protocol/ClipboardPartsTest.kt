package dev.mkzk.manifold.hub.network.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPartsTest {

    private fun putBack(assembler: ClipboardAssembler, parts: List<Control.Clipboard>): String? {
        var done: String? = null
        for (part in parts) {
            val decoded = ControlCodec.decode(ControlCodec.encode(1, part)) as Decoded.Message
            done = assembler.add(decoded.control as Control.Clipboard) ?: done
        }
        return done
    }

    @Test
    fun textLongerThanOneDatagramComesBackWhole() {
        val text = "Grüße ✓ ".repeat(ControlCodec.CLIPBOARD_CHUNK / 4)
        val parts = ClipboardParts.of(2, 1, text)!!
        assertTrue(parts.size > 1)
        assertEquals(text, putBack(ClipboardAssembler(), parts))
    }

    @Test
    fun partsOutOfOrderStillMakeTheText() {
        val text = "x".repeat(ControlCodec.CLIPBOARD_CHUNK * 3 + 10)
        assertEquals(text, putBack(ClipboardAssembler(), ClipboardParts.of(2, 4, text)!!.reversed()))
    }

    @Test
    fun aCopyOverTheLimitIsNotSent() {
        assertNull(ClipboardParts.of(2, 1, "x".repeat(ControlCodec.MAX_CLIPBOARD_BYTES + 1)))
        assertNull(ClipboardParts.of(2, 1, ""))
    }

    @Test
    fun aNewerCopyReplacesOneStillArriving() {
        val assembler = ClipboardAssembler()
        assertNull(assembler.add(ClipboardParts.of(2, 1, "o".repeat(ControlCodec.CLIPBOARD_CHUNK * 2))!!.first()))
        assertEquals("new", putBack(assembler, ClipboardParts.of(2, 2, "new")!!))
    }
}
