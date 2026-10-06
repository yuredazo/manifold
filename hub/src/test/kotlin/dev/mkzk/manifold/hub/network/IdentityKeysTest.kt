package dev.mkzk.manifold.hub.network

import dev.mkzk.manifold.hub.network.protocol.Crypto
import dev.mkzk.manifold.hub.network.protocol.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityKeysTest {
    private class XorSealer(private val mask: Int = 0x5A) : Sealer {
        override fun seal(plain: ByteArray) = ByteArray(plain.size + 1) { if (it == 0) 1 else (plain[it - 1].toInt() xor mask).toByte() }

        override fun open(sealed: ByteArray) =
            if (sealed.isEmpty() || sealed[0] != 1.toByte()) null else ByteArray(sealed.size - 1) { (sealed[it + 1].toInt() xor mask).toByte() }
    }

    private class Slot(var value: String? = null) {
        fun load(sealer: Sealer) = loadIdentityKeys({ value }, { value = it }, sealer)
    }

    @Test
    fun aNewKeyIsStoredSealedAndComesBackTheSame() {
        val slot = Slot()
        val first = slot.load(XorSealer())

        assertTrue(slot.value!!.startsWith("sealed:"))
        assertFalse(slot.value!!.contains(first.private.toHex()))
        assertArrayEquals(first.private, slot.load(XorSealer()).private)
    }

    @Test
    fun aKeyWrittenUnsealedByAnEarlierVersionIsSealedAndKept() {
        val old = Crypto.generateKeyPair()
        val slot = Slot(old.private.toHex())

        val keys = slot.load(XorSealer())

        assertArrayEquals(old.private, keys.private)
        assertTrue(slot.value!!.startsWith("sealed:"))
        assertFalse(slot.value!!.contains(old.private.toHex()))
    }

    @Test
    fun aKeyThisDeviceCannotOpenIsReplaced() {
        val slot = Slot()
        val first = slot.load(XorSealer(0x11))

        val second = slot.load(object : Sealer {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray): ByteArray? = null
        })

        assertNotEquals(first.private.toHex(), second.private.toHex())
    }

    @Test
    fun aDeviceThatCannotSealStillKeepsItsIdentity() {
        val broken = object : Sealer {
            override fun seal(plain: ByteArray): ByteArray = throw java.security.GeneralSecurityException("no key store")
            override fun open(sealed: ByteArray): ByteArray? = null
        }
        val slot = Slot()

        val first = slot.load(broken)

        assertArrayEquals(first.private, slot.load(broken).private)
    }
}
