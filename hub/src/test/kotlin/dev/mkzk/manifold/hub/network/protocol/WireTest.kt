package dev.mkzk.manifold.hub.network.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireTest {

    @Test
    fun aHandshakePacketKeepsEveryField() {
        val packet = Wire.Handshake(Wire.TYPE_HELLO, 1, senderIndex = -5, receiverIndex = 77, message = byteArrayOf(1, 2, 3))

        val decoded = Wire.decodeHandshake(Wire.encode(packet))!!

        assertEquals(Wire.TYPE_HELLO, decoded.type)
        assertEquals(1, decoded.step)
        assertEquals(-5, decoded.senderIndex)
        assertEquals(77, decoded.receiverIndex)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.message)
    }

    @Test
    fun otherDatagramsAreNotHandshakes() {
        assertNull(Wire.decodeHandshake(ByteArray(0)))
        assertNull(Wire.decodeHandshake(ByteArray(9)))
        assertNull(Wire.decodeHandshake(ByteArray(40) { 0 }))
        assertNull(Wire.decodeHandshake(ByteArray(40) { if (it == 0) Session.TYPE_DATA else 1 }))
    }

    @Test
    fun theReceiverOfADataPacketIsReadWithoutDecrypting() {
        val pair = NoiseHandshake(Pattern.XX, true, Crypto.generateKeyPair())
        val other = NoiseHandshake(Pattern.XX, false, Crypto.generateKeyPair())
        other.readMessage(pair.writeMessage())
        pair.readMessage(other.writeMessage())
        other.readMessage(pair.writeMessage())
        val datagram = Session(localIndex = 1, remoteIndex = 4242, keys = pair.split()).seal(Stream.Control, byteArrayOf(1))

        assertEquals(4242, Wire.dataReceiver(datagram))
        assertNull(Wire.dataReceiver(ByteArray(3)))
        assertNull(Wire.dataReceiver(Wire.encode(Wire.Handshake(Wire.TYPE_PAIR, 0, 1, 0, ByteArray(20)))))
    }

    @Test
    fun addressesTypedByTheOwnerAreParsed() {
        assertEquals(Address("192.168.1.23", 47200), Address.parse("192.168.1.23:47200", 1))
        assertEquals(Address("192.168.1.23", 9), Address.parse("  192.168.1.23  ", 9))
        assertEquals(Address("hub.example.com", 5000), Address.parse("hub.example.com:5000", 1))
    }

    @Test
    fun unusableAddressesAreRefused() {
        listOf("", "   ", ":4000", "host:", "host:abc", "host:0", "host:70000", "two words", "a:b:c", "host:-1").forEach {
            assertNull("'$it'", Address.parse(it, 47200))
        }
    }
}
