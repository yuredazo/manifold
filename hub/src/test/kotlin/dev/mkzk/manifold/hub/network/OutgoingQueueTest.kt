package dev.mkzk.manifold.hub.network

import dev.mkzk.manifold.hub.network.protocol.Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutgoingQueueTest {

    private val peer = Address("192.168.0.2", 47200)
    private val millisecond = 1_000_000L

    private fun packet(first: Byte, queuedAt: Long, size: Int = 1) = OutgoingQueue.Packet(peer, ByteArray(size) { first }, queuedAt)

    private fun OutgoingQueue.nextByte(now: Long) = next(now)?.bytes?.first()

    @Test
    fun controlGoesBeforeMediaThatWasQueuedEarlier() {
        val queue = OutgoingQueue(250 * millisecond, 1_000)
        queue.addMedia(packet(1, 0))
        queue.addControl(packet(2, 1))

        assertEquals(2.toByte(), queue.nextByte(2))
        assertEquals(1.toByte(), queue.nextByte(2))
        assertNull(queue.next(2))
    }

    @Test
    fun mediaKeepsItsOrder() {
        val queue = OutgoingQueue(250 * millisecond, 1_000)
        (1..3).forEach { queue.addMedia(packet(it.toByte(), 0)) }

        assertEquals(listOf<Byte>(1, 2, 3), List(3) { queue.nextByte(1)!! })
    }

    @Test
    fun mediaThatWaitedTooLongIsDroppedAndCounted() {
        val queue = OutgoingQueue(250 * millisecond, 1_000)
        queue.addMedia(packet(1, 0))
        queue.addMedia(packet(2, 100 * millisecond))
        queue.addMedia(packet(3, 400 * millisecond))

        assertEquals(3.toByte(), queue.nextByte(500 * millisecond))
        assertEquals(2, queue.droppedMedia)
    }

    @Test
    fun controlIsSentHoweverLateItIs() {
        val queue = OutgoingQueue(250 * millisecond, 1_000)
        queue.addControl(packet(1, 0))

        assertEquals(1.toByte(), queue.nextByte(10_000 * millisecond))
        assertEquals(0, queue.droppedMedia)
    }

    @Test
    fun aFullMediaQueueDropsItsOldestPacketsFirst() {
        val queue = OutgoingQueue(250 * millisecond, 10)
        queue.addMedia(packet(1, 0, size = 6))
        queue.addMedia(packet(2, 0, size = 6))
        queue.addMedia(packet(3, 0, size = 6))

        assertEquals(listOf<Byte>(3), generateSequence { queue.nextByte(1) }.toList())
        assertEquals(2, queue.droppedMedia)
    }

    @Test
    fun anEmptyQueueSaysSo() {
        val queue = OutgoingQueue(250 * millisecond, 10)
        assertEquals(true, queue.isEmpty)
        queue.addControl(packet(1, 0))
        assertEquals(false, queue.isEmpty)
    }
}
