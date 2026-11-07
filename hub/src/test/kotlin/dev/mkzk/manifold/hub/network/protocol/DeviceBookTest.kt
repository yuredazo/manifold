package dev.mkzk.manifold.hub.network.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBookTest {

    private val key = Crypto.generateKeyPair().public
    private val keyHex = key.toHex()

    private fun book(stored: String? = null, saved: MutableList<String> = mutableListOf()) = DeviceBook(stored) { saved += it }

    @Test
    fun aDeviceIsFoundByItsPublicKey() {
        val book = book()
        book.put(Device(keyHex, "Beta", "10.0.0.2:4000"))

        assertEquals("Beta", book.find(key)!!.name)
        assertNull(book.find(Crypto.generateKeyPair().public))
    }

    @Test
    fun changesSurviveARestart() {
        val saved = mutableListOf<String>()
        val first = book(saved = saved)
        first.put(Device(keyHex, "Beta", "10.0.0.2:4000"))
        first.update(keyHex) { it.copy(receive = true, send = false) }

        val second = book(stored = saved.last())

        val device = second.find(key)!!
        assertEquals("Beta", device.name)
        assertEquals("10.0.0.2:4000", device.address)
        assertTrue(device.receive)
        assertFalse(device.send)
    }

    @Test
    fun removingForgetsTheDevice() {
        val saved = mutableListOf<String>()
        val book = book(saved = saved)
        book.put(Device(keyHex, "Beta", null))

        book.remove(keyHex)

        assertNull(book.find(key))
        assertFalse(saved.last().contains(keyHex))
    }

    @Test
    fun aNameCannotBreakTheStoredFormat() {
        val saved = mutableListOf<String>()
        book(saved = saved).put(Device(keyHex, "Evil\tName\nnext line", null))

        val reloaded = book(stored = saved.last())

        assertEquals(1, reloaded.devices.value.size)
        assertNotNull(reloaded.find(key))
    }

    @Test
    fun brokenLinesAndBadKeysAreIgnored() {
        val stored = "garbage\nnot-a-key\tName\t\ttrue\tfalse\n$keyHex\tOk\t\ttrue\tfalse"

        val book = book(stored)

        assertEquals(listOf("Ok"), book.devices.value.map { it.name })
    }

    @Test
    fun theFingerprintIsAStableHashOfTheKey() {
        val device = Device(keyHex, "Beta", null)

        assertEquals(64, device.fingerprint.length)
        assertEquals(fingerprintOf(key), device.fingerprint)
        assertEquals(4, readableFingerprint(device.fingerprint).split(" ").size)
    }

    @Test
    fun otherAddressesSurviveARestartAndTheLastUsedOneStaysFirst() {
        val saved = mutableListOf<String>()
        val first = book(saved = saved)
        first.put(Device(keyHex, "Beta", "192.168.0.5:47200"))
        first.update(keyHex) { it.alsoAt(listOf("192.168.0.5:47200", "100.64.0.9:47200")) }
        first.update(keyHex) { it.reachedAt("100.64.0.9:47200") }

        val device = book(stored = saved.last()).find(key)!!

        assertEquals("100.64.0.9:47200", device.address)
        assertEquals(listOf("100.64.0.9:47200", "192.168.0.5:47200"), device.addresses)
    }

    @Test
    fun aLineFromBeforeOtherAddressesStillReads() {
        val device = book(stored = "$keyHex\tBeta\t10.0.0.2:4000\ttrue\tfalse").find(key)!!

        assertEquals("10.0.0.2:4000", device.address)
        assertEquals(emptyList<String>(), device.others)
    }

    @Test
    fun aTypedAddressStaysBehindTheOnesTheDeviceReports() {
        val device = Device(keyHex, "Beta", "203.0.113.7:47200")
            .reachedAt("192.168.0.5:47200")
            .alsoAt(listOf("192.168.0.5:47200", "100.64.0.9:47200"))

        assertEquals(listOf("192.168.0.5:47200", "100.64.0.9:47200", "203.0.113.7:47200"), device.addresses)
    }
}
