package dev.mkzk.manifold.hub.network.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconTest {
    private val id = "0123456789abcdef"

    @Test
    fun aBeaconSurvivesTheRoundTrip() {
        val decoded = BeaconCodec.decode(BeaconCodec.encode(Beacon(id, 47200, "Pixel")))

        assertNotNull(decoded)
        assertEquals(id, decoded!!.id)
        assertEquals(47200, decoded.port)
        assertEquals("Pixel", decoded.name)
    }

    @Test
    fun aLongNameIsCutToTheLimitWithoutSplittingACharacter() {
        val bytes = BeaconCodec.encode(Beacon(id, 1, "é".repeat(50)))

        val name = BeaconCodec.decode(bytes)!!.name

        assertTrue(name.toByteArray().size <= BeaconCodec.MAX_NAME_BYTES)
        assertTrue(name.all { it == 'é' })
    }

    @Test
    fun controlCharactersAreDroppedFromTheName() {
        val bytes = BeaconCodec.encode(Beacon(id, 1, "Pi\u0000x\nel"))

        assertEquals("Pixel", BeaconCodec.decode(bytes)!!.name)
    }

    @Test
    fun datagramsThatAreNotBeaconsAreIgnored() {
        val good = BeaconCodec.encode(Beacon(id, 47200, "Pixel"))

        assertNull(BeaconCodec.decode(good.copyOf(10)))
        assertNull(BeaconCodec.decode(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(BeaconCodec.decode(good.copyOf().also { it[4] = 2 }))
        assertNull(BeaconCodec.decode(good.copyOf().also { it[5] = 0; it[6] = 0 }))
        assertNull(BeaconCodec.decode(good.copyOf(good.size - 1)))
        assertNull(BeaconCodec.decode(good.copyOf().also { it[15] = 65 }))
        assertNull(BeaconCodec.decode(BeaconCodec.encode(Beacon(id, 1, "\u0001 "))))
    }

    @Test
    fun trailingBytesFromALaterVersionDoNotMatter() {
        val longer = BeaconCodec.encode(Beacon(id, 47200, "Pixel")) + byteArrayOf(1, 2, 3)

        assertEquals("Pixel", BeaconCodec.decode(longer)!!.name)
    }

    @Test
    fun theEncodingRejectsAnIdThatIsNotEightBytesOfHex() {
        val bad = listOf("xyz", "00", "0123456789abcdef00")
        bad.forEach { value ->
            val failed = runCatching { BeaconCodec.encode(Beacon(value, 1, "n")) }.isFailure
            assertTrue(value, failed)
        }
    }
}
