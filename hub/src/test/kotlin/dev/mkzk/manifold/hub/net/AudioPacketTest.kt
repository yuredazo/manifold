package dev.mkzk.manifold.hub.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPacketTest {

    @Test
    fun aPacketSurvivesEncoding() {
        val frame = ByteArray(300) { it.toByte() }

        val decoded = AudioPacket.decode(AudioPacket.encode(65_535, -1_000, frame))!!

        assertEquals(65_535, decoded.streamId)
        assertEquals(-1_000, decoded.timestamp)
        assertArrayEquals(frame, decoded.frame)
    }

    @Test
    fun aPacketWithoutAFrameIsNotDecoded() {
        assertNull(AudioPacket.decode(ByteArray(0)))
        assertNull(AudioPacket.decode(ByteArray(AudioPacket.HEADER_LENGTH)))
    }
}
