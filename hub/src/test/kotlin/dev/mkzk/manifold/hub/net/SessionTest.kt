package dev.mkzk.manifold.hub.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionTest {

    private class Sessions(val a: Session, val b: Session)

    private fun connected(): Sessions {
        val alice = NoiseHandshake(Pattern.XX, true, Crypto.generateKeyPair())
        val bob = NoiseHandshake(Pattern.XX, false, Crypto.generateKeyPair())
        bob.readMessage(alice.writeMessage())
        alice.readMessage(bob.writeMessage())
        bob.readMessage(alice.writeMessage())
        val aliceKeys = alice.split()
        val bobKeys = bob.split()
        return Sessions(Session(localIndex = 1, remoteIndex = 2, keys = aliceKeys), Session(localIndex = 2, remoteIndex = 1, keys = bobKeys))
    }

    @Test
    fun aPacketArrivesIntactInBothDirections() {
        val pair = connected()

        val toBob = pair.a.seal(Stream.Video, "frame".toByteArray())
        val atBob = pair.b.open(toBob)!!
        assertEquals(Stream.Video, atBob.stream)
        assertArrayEquals("frame".toByteArray(), atBob.payload)

        val toAlice = pair.b.seal(Stream.Control, "ack".toByteArray())
        assertArrayEquals("ack".toByteArray(), pair.a.open(toAlice)!!.payload)
    }

    @Test
    fun theContentIsNotVisibleOnTheWire() {
        val datagram = connected().a.seal(Stream.Video, "secret secret secret".toByteArray())

        assertFalse(String(datagram, Charsets.ISO_8859_1).contains("secret"))
    }

    @Test
    fun aRecordedPacketCannotBePlayedAgain() {
        val pair = connected()
        val datagram = pair.a.seal(Stream.Control, "once".toByteArray())

        assertNotNull(pair.b.open(datagram))
        assertNull(pair.b.open(datagram))
    }

    @Test
    fun anyChangedByteIsRejected() {
        val pair = connected()
        val datagram = pair.a.seal(Stream.Video, "payload".toByteArray())

        for (position in datagram.indices) {
            val tampered = datagram.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            assertNull("byte $position", pair.b.open(tampered))
        }
        assertNotNull("the untouched packet still opens", pair.b.open(datagram))
    }

    @Test
    fun aPacketForAnotherSessionIsIgnored() {
        val first = connected()
        val second = connected()

        assertNull(second.b.open(first.a.seal(Stream.Control, "x".toByteArray())))
    }

    @Test
    fun aPacketCannotBeTurnedAroundAndSentBack() {
        val pair = connected()
        val datagram = pair.a.seal(Stream.Control, "x".toByteArray())

        assertNull(pair.a.open(datagram))
    }

    @Test
    fun packetsMayArriveOutOfOrder() {
        val pair = connected()
        val sent = List(5) { pair.a.seal(Stream.Video, byteArrayOf(it.toByte())) }

        listOf(2, 0, 4, 1, 3).forEach { assertArrayEquals(byteArrayOf(it.toByte()), pair.b.open(sent[it])!!.payload) }
    }

    @Test
    fun junkAndShortDatagramsAreDropped() {
        val pair = connected()

        assertNull(pair.b.open(ByteArray(0)))
        assertNull(pair.b.open(ByteArray(5)))
        assertNull(pair.b.open(ByteArray(200) { 7 }))
    }

    @Test
    fun theLargestPayloadFitsInOneDatagram() {
        val pair = connected()
        val payload = ByteArray(Session.MAX_PAYLOAD) { it.toByte() }

        val datagram = pair.a.seal(Stream.Video, payload)

        assertEquals(Session.MAX_DATAGRAM, datagram.size)
        assertArrayEquals(payload, pair.b.open(datagram)!!.payload)
        try {
            pair.a.seal(Stream.Video, ByteArray(Session.MAX_PAYLOAD + 1))
            fail("an oversized payload must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun anUnknownHandshakeKeyGivesNoSharedSession() {
        val pair = connected()
        val stranger = Session(
            localIndex = 2,
            remoteIndex = 1,
            keys = TransportKeys(send = ByteArray(32) { 1 }, receive = ByteArray(32) { 2 }),
        )

        assertNull(stranger.open(pair.a.seal(Stream.Control, "x".toByteArray())))
    }

    @Test
    fun pairingGivesBothSidesTheSameCodeToCompare() {
        val alice = NoiseHandshake(Pattern.XX, true, Crypto.generateKeyPair())
        val bob = NoiseHandshake(Pattern.XX, false, Crypto.generateKeyPair())
        bob.readMessage(alice.writeMessage())
        alice.readMessage(bob.writeMessage())
        bob.readMessage(alice.writeMessage())

        assertArrayEquals(alice.handshakeHash, bob.handshakeHash)
        assertTrue(alice.complete && bob.complete)
    }
}
