package dev.mkzk.manifold.hub.network

import dev.mkzk.manifold.hub.network.protocol.Beacon
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyBookTest {
    private fun beacon(id: String, name: String, port: Int = 47200) = Beacon(id.padEnd(16, '0'), port, name)

    @Test
    fun devicesAreListedByName() {
        val book = NearbyBook()
        book.hear(beacon("b", "tablet"), "192.168.1.5", now = 0)
        book.hear(beacon("a", "Phone"), "192.168.1.6", now = 0)

        assertEquals(listOf("Phone", "tablet"), book.current(now = 100).map { it.name })
    }

    @Test
    fun aDeviceThatStopsAnnouncingDropsOut() {
        val book = NearbyBook(ttlMs = 4_000)
        book.hear(beacon("a", "Phone"), "192.168.1.6", now = 0)
        book.hear(beacon("b", "Tablet"), "192.168.1.5", now = 3_000)

        assertEquals(listOf("Tablet"), book.current(now = 5_000).map { it.name })
        assertEquals(emptyList<String>(), book.current(now = 9_000).map { it.name })
    }

    @Test
    fun theSameDeviceAnnouncingAgainReplacesItsEntryAndKeepsTheNewAddress() {
        val book = NearbyBook()
        book.hear(beacon("a", "Phone"), "192.168.1.6", now = 0)
        book.hear(beacon("a", "Phone", port = 5000), "192.168.1.9", now = 1_000)

        val only = book.current(now = 1_500).single()
        assertEquals("192.168.1.9", only.host)
        assertEquals(5000, only.port)
    }

    @Test
    fun aNoisyNetworkCannotGrowTheListWithoutBound() {
        val book = NearbyBook(limit = 3)
        (0 until 10).forEach { book.hear(beacon("%x".format(it), "d$it"), "10.0.0.$it", now = 0) }

        assertEquals(3, book.current(now = 100).size)
    }

    @Test
    fun clearingEmptiesTheList() {
        val book = NearbyBook()
        book.hear(beacon("a", "Phone"), "192.168.1.6", now = 0)

        book.clear()

        assertEquals(0, book.current(now = 1).size)
    }
}
