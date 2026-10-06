package dev.mkzk.manifold.hub.network.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlChannelTest {

    private class Wired {
        val received = ArrayList<Control>()
        val sentByB = ArrayList<ByteArray>()
        var failed = false
        var lossRule: (ByteArray) -> Boolean = { false }
        private val toB = ArrayList<ByteArray>()
        private val toA = ArrayList<ByteArray>()
        val a = ControlChannel({ toB += it }, { }, { failed = true })
        val b = ControlChannel({ toA += it; sentByB += it }, { received += it }, { })

        fun pump() {
            repeat(10) {
                val forB = toB.toList().also { toB.clear() }
                val forA = toA.toList().also { toA.clear() }
                forB.filterNot(lossRule).forEach(b::receive)
                forA.filterNot(lossRule).forEach(a::receive)
            }
        }
    }

    @Test
    fun aMessageIsDeliveredOnceAndAcknowledged() {
        val wired = Wired()

        wired.a.send(Control.PairConfirm, 0)
        wired.pump()
        wired.a.tick(10_000)
        wired.pump()

        assertEquals(listOf<Control>(Control.PairConfirm), wired.received)
        assertFalse(wired.failed)
    }

    @Test
    fun aLostMessageIsSentAgain() {
        val wired = Wired()
        var first = true
        wired.lossRule = { if (first) { first = false; true } else false }

        wired.a.send(Control.Bye, 0)
        wired.pump()
        assertTrue(wired.received.isEmpty())
        wired.a.tick(ControlChannel.RESEND_AFTER_MS + 1)
        wired.pump()

        assertEquals(listOf<Control>(Control.Bye), wired.received)
    }

    @Test
    fun aLostAckDoesNotDeliverTheMessageTwice() {
        val wired = Wired()
        var acks = 0
        wired.lossRule = { it[0].toInt() == 0 && acks++ == 0 }

        wired.a.send(Control.PairConfirm, 0)
        wired.pump()
        wired.a.tick(ControlChannel.RESEND_AFTER_MS + 1)
        wired.pump()

        assertEquals(1, wired.received.size)
    }

    @Test
    fun theSenderGivesUpWhenNothingGetsThrough() {
        val wired = Wired()
        wired.lossRule = { true }

        wired.a.send(Control.PairConfirm, 0)
        var now = 0L
        repeat(ControlChannel.MAX_ATTEMPTS + 2) {
            now += ControlChannel.RESEND_AFTER_MS + 1
            wired.a.tick(now)
        }

        assertTrue(wired.failed)
    }

    @Test
    fun anOlderFeedListNeverReplacesANewerOne() {
        val wired = Wired()
        val old = ControlCodec.encode(1, Control.FeedList(listOf(FeedInfo("old", 1, 1, 1, false))))
        val new = ControlCodec.encode(2, Control.FeedList(listOf(FeedInfo("new", 1, 1, 1, false))))

        wired.b.receive(new)
        wired.b.receive(old)

        assertEquals(listOf("new"), wired.received.map { (it as Control.FeedList).feeds.single().name })
    }

    @Test
    fun pingsAreNotAcknowledgedOrResent() {
        val wired = Wired()
        var sent = 0
        wired.lossRule = { sent++; true }

        wired.a.send(Control.Ping, 0)
        wired.pump()
        wired.a.tick(100_000)
        wired.pump()

        assertEquals(1, sent)
        assertFalse(wired.failed)
    }

    @Test
    fun aMessageOfAnUnknownKindIsAcknowledgedAndIgnored() {
        val wired = Wired()
        // What a newer version might send: a kind this one has never heard of.
        val future = byteArrayOf(40, 0, 0, 0, 5, 1, 2, 3)

        wired.b.receive(future)
        wired.b.receive(future)

        assertTrue(wired.received.isEmpty())
        assertEquals("the sender is told twice, since the first ack may be lost", 2, wired.sentByB.size)
        assertEquals(listOf<Byte>(0, 0, 0, 0, 5), wired.sentByB.first().toList())
    }

    @Test
    fun garbageIsIgnored() {
        val wired = Wired()

        wired.b.receive(ByteArray(0))
        wired.b.receive(byteArrayOf(1, 2, 3))
        wired.b.receive(ByteArray(50) { 99 })

        assertTrue(wired.received.isEmpty())
    }
}
