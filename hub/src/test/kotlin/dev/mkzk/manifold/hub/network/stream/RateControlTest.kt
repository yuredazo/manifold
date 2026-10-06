package dev.mkzk.manifold.hub.network.stream

import dev.mkzk.manifold.hub.network.protocol.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateControlTest {

    private val second = 1_000_000_000L

    private fun report(lostFrames: Int = 0, fragments: Int = 400, resendRequests: Int = 0) =
        Control.StreamReport(streamId = 1, windowMs = 500, fragments = fragments, resendRequests = resendRequests, lostFrames = lostFrames, jitterMs10 = 0)

    @Test
    fun itStartsAtWhatTheReceiverAskedFor() {
        assertEquals(4_000, RateControl(minKbps = 200, maxKbps = 4_000).kbps)
    }

    @Test
    fun aFrameLostForGoodMakesItBackOff() {
        val control = RateControl(200, 4_000)

        assertEquals(3_400, control.onReport(report(lostFrames = 1), now = 0))
    }

    @Test
    fun itBacksOffAtMostOnceASecond() {
        val control = RateControl(200, 4_000)
        control.onReport(report(lostFrames = 1), now = 0)

        assertNull(control.onReport(report(lostFrames = 1), now = second / 2))
        assertEquals(2_890, control.onReport(report(lostFrames = 1), now = second))
    }

    @Test
    fun itNeverGoesBelowTheFloor() {
        val control = RateControl(minKbps = 500, maxKbps = 600)

        repeat(10) { control.onReport(report(lostFrames = 3), now = it * 2 * second) }

        assertEquals(500, control.kbps)
    }

    @Test
    fun piecesThatWereLostAndRecoveredAreNotAReasonToBackOff() {
        val control = RateControl(200, 4_000)

        repeat(20) { assertNull(control.onReport(report(fragments = 380, resendRequests = 20), now = it * second)) }
    }

    @Test
    fun itRaisesTheBitrateAfterAFewCleanReportsAndNeverPastTheAsk() {
        val control = RateControl(200, 4_000)
        control.onReport(report(lostFrames = 1), now = 0)
        val afterBackOff = control.kbps

        var now = second
        repeat(6) {
            control.onReport(report(), now)
            now += second / 2
        }
        assertTrue("${control.kbps} after the back-off to $afterBackOff", control.kbps > afterBackOff)

        repeat(400) {
            control.onReport(report(), now)
            now += second / 2
        }
        assertEquals(4_000, control.kbps)
    }

    @Test
    fun aLossInTheMiddleOfACleanStretchStartsTheCountAgain() {
        val control = RateControl(200, 4_000)
        control.onReport(report(lostFrames = 1), now = 0)
        val afterBackOff = control.kbps

        control.onReport(report(), now = 2 * second)
        control.onReport(report(), now = 3 * second)
        control.onReport(report(lostFrames = 1), now = 4 * second)
        val backedOffAgain = control.kbps
        control.onReport(report(), now = 5 * second)

        assertTrue(backedOffAgain < afterBackOff)
        assertEquals("one clean report is not enough to raise it", backedOffAgain, control.kbps)
    }
}
