package dev.mkzk.manifold.hub.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlTest {

    private fun roundTrip(control: Control): Control? =
        (ControlCodec.decode(ControlCodec.encode(7, control)) as? Decoded.Message)?.control

    @Test
    fun everyMessageSurvivesEncoding() {
        listOf(Control.Ping, Control.PairConfirm, Control.PairReject, Control.Bye).forEach { assertEquals(it, roundTrip(it)) }
        val feeds = Control.FeedList(listOf(FeedInfo("alpha", 720, 1080, 30, false), FeedInfo("cam é", 1920, 1080, 60, true)))
        assertEquals(feeds, roundTrip(feeds))
        assertEquals(Control.FeedList(emptyList()), roundTrip(Control.FeedList(emptyList())))
    }

    @Test
    fun theSoundOnlyFlagSurvivesEncodingAndANoSizeFeedWithSoundIsNotTakenForOne() {
        val sound = FeedInfo("PC sound", 0, 0, 0, true, soundOnly = true)
        val noPreference = FeedInfo("no size", 0, 0, 0, true)
        val feeds = Control.FeedList(listOf(sound, noPreference, FeedInfo("picture", 1280, 720, 30, true)))

        val decoded = roundTrip(feeds) as Control.FeedList

        assertEquals(feeds, decoded)
        assertEquals(listOf(true, false, false), decoded.feeds.map { it.soundOnly })
    }

    @Test
    fun theIdAndAckAreCarried() {
        assertEquals(Decoded.Ack(42), ControlCodec.decode(ControlCodec.encodeAck(42)))
        assertEquals(Decoded.Message(9, Control.Bye), ControlCodec.decode(ControlCodec.encode(9, Control.Bye)))
    }

    @Test
    fun aFeedListNeverOverflowsAPacket() {
        val many = List(200) { FeedInfo("feed number $it with a long name", 1, 1, 1, false) }

        val bytes = ControlCodec.encode(1, Control.FeedList(many))

        assertTrue(bytes.size <= Session.MAX_PAYLOAD)
        val decoded = (ControlCodec.decode(bytes) as Decoded.Message).control as Control.FeedList
        assertTrue(decoded.feeds.isNotEmpty() && decoded.feeds.size <= ControlCodec.MAX_FEEDS)
        assertEquals(many.first(), decoded.feeds.first())
    }

    @Test
    fun aNameTooLongToEncodeIsLeftOut() {
        val tooLong = FeedInfo("😀".repeat(64), 1, 1, 1, false)

        val decoded = (ControlCodec.decode(ControlCodec.encode(1, Control.FeedList(listOf(tooLong, FeedInfo("ok", 1, 1, 1, false))))) as Decoded.Message).control as Control.FeedList

        assertEquals(listOf("ok"), decoded.feeds.map { it.name })
    }

    @Test
    fun feedsWithUnusableNamesFromTheOtherSideAreDropped() {
        val sent = ControlCodec.encode(1, Control.FeedList(listOf(FeedInfo("bad\nname", 1, 1, 1, false), FeedInfo("   ", 1, 1, 1, false), FeedInfo("good", 1, 1, 1, false))))

        val feeds = ((ControlCodec.decode(sent) as Decoded.Message).control as Control.FeedList).feeds

        assertEquals(listOf("good"), feeds.map { it.name })
    }

    @Test
    fun feedsWithAnAbsurdSizeFromTheOtherSideAreDropped() {
        val sent = ControlCodec.encode(1, Control.FeedList(listOf(FeedInfo("huge", 60_000, 1, 1, false), FeedInfo("fine", 1920, 1080, 30, false))))

        val feeds = ((ControlCodec.decode(sent) as Decoded.Message).control as Control.FeedList).feeds

        assertEquals(listOf("fine"), feeds.map { it.name })
    }

    @Test
    fun junkIsRefusedWithoutThrowing() {
        assertNull(ControlCodec.decode(ByteArray(0)))
        assertNull(ControlCodec.decode(ByteArray(4)))
        val truncated = ControlCodec.encode(1, Control.FeedList(listOf(FeedInfo("alpha", 720, 1080, 30, false))))
        for (length in 5 until truncated.size) assertEquals("length $length", Decoded.Unrecognized(1), ControlCodec.decode(truncated.copyOf(length)))
        val liar = ControlCodec.encode(1, Control.FeedList(emptyList())).also { it[5] = 0x7F; it[6] = 0x7F }
        assertEquals(Decoded.Unrecognized(1), ControlCodec.decode(liar))
    }

    @Test
    fun aKindFromANewerVersionIsRecognizedAsUnknownAndKeepsItsId() {
        assertEquals(Decoded.Unrecognized(1), ControlCodec.decode(byteArrayOf(99, 0, 0, 0, 1)))
        assertEquals(Decoded.Unrecognized(0x01020304), ControlCodec.decode(byteArrayOf(40, 1, 2, 3, 4, 9, 9)))
    }

    @Test
    fun subscriptionMessagesSurviveEncoding() {
        val subscribe = Control.Subscribe(streamId = 65_535, feed = "alpha", width = 720, height = 1080, bitrateKbps = 2_500)

        assertEquals(subscribe, roundTrip(subscribe))
        assertEquals(subscribe.copy(audio = true), roundTrip(subscribe.copy(audio = true)))
        assertEquals(Control.Unsubscribe(3), roundTrip(Control.Unsubscribe(3)))
        assertEquals(Control.KeyframeRequest(65_000), roundTrip(Control.KeyframeRequest(65_000)))
    }

    @Test
    fun aRefusalCarriesItsReasonAndAnUnknownReasonReadsAsFailed() {
        for (reason in Control.Refusal.entries) {
            assertEquals(Control.SubscribeRefused(9, reason), roundTrip(Control.SubscribeRefused(9, reason)))
        }
        assertEquals(
            Decoded.Message(5, Control.SubscribeRefused(9, Control.Refusal.FAILED)),
            ControlCodec.decode(byteArrayOf(14, 0, 0, 0, 5, 0, 9, 99)),
        )
        assertTrue(ControlCodec.needsAck(Control.SubscribeRefused(9, Control.Refusal.NOT_FOUND)))
    }

    @Test
    fun measurementMessagesSurviveEncodingAndAreNeverResent() {
        val request = Control.TimeRequest(1_234_567_890_123L)
        val reply = Control.TimeReply(Long.MAX_VALUE)
        val stats = Control.SenderStats(streamId = 9, bitrateKbps = 4_300, fps10 = 598, encodeMs10 = 87, sendMs10 = 12)

        assertEquals(request, roundTrip(request))
        assertEquals(reply, roundTrip(reply))
        assertEquals(stats, roundTrip(stats))
        listOf(request, reply, stats, Control.Ping).forEach { assertFalse(it.toString(), ControlCodec.needsAck(it)) }
        assertTrue(ControlCodec.needsAck(Control.Subscribe(1, "alpha", 1, 1, 1)))
    }

    @Test
    fun theBytesOfTheMeasurementMessagesAreTheOnesTheWindowsHubWrites() {
        assertArrayEquals(
            byteArrayOf(9, 0, 0, 0, 1, 1, 2, 3, 4, 5, 6, 7, 8),
            ControlCodec.encode(1, Control.TimeRequest(0x0102030405060708)),
        )
        assertArrayEquals(
            byteArrayOf(11, 0, 0, 0, 2, 0, 3, 0x07, 0xD0.toByte(), 0x01, 0x2C, 0, 80, 0, 5),
            ControlCodec.encode(2, Control.SenderStats(streamId = 3, bitrateKbps = 2_000, fps10 = 300, encodeMs10 = 80, sendMs10 = 5)),
        )
    }

    @Test
    fun aRequestToSendFragmentsAgainSurvivesEncodingAndIsNeverResent() {
        val some = Control.Nack(streamId = 7, frameId = -5, indexes = listOf(0, 3, 4_095))
        val whole = Control.Nack(streamId = 7, frameId = 1_000_000, indexes = emptyList())

        assertEquals(some, roundTrip(some))
        assertEquals(whole, roundTrip(whole))
        assertFalse(ControlCodec.needsAck(some))
    }

    @Test
    fun aRequestForMorePiecesThanFitBecomesARequestForTheWholeFrame() {
        val huge = Control.Nack(streamId = 1, frameId = 2, indexes = List(ControlCodec.MAX_NACK_INDEXES + 1) { it })

        assertEquals(Control.Nack(1, 2, emptyList()), roundTrip(huge))
        val bytes = ControlCodec.encode(1, Control.Nack(1, 2, List(ControlCodec.MAX_NACK_INDEXES) { it }))
        assertTrue(bytes.size <= Session.MAX_PAYLOAD)
    }

    @Test
    fun aRequestThatClaimsTooManyPiecesIsNotDecoded() {
        val bytes = ControlCodec.encode(1, Control.Nack(1, 2, listOf(5))).also { it[11] = 0x7F; it[12] = 0x7F }

        assertEquals(Decoded.Unrecognized(1), ControlCodec.decode(bytes))
    }

    @Test
    fun theBytesOfARequestToSendAgainAreTheOnesTheWindowsHubWrites() {
        assertArrayEquals(
            byteArrayOf(12, 0, 0, 0, 3, 0, 7, 0, 0, 0x01, 0x00, 0, 2, 0, 1, 0, 9),
            ControlCodec.encode(3, Control.Nack(streamId = 7, frameId = 256, indexes = listOf(1, 9))),
        )
    }

    @Test
    fun aReportOnAStreamSurvivesEncodingAndIsNeverResent() {
        val report = Control.StreamReport(streamId = 5, windowMs = 500, fragments = 420, resendRequests = 6, lostFrames = 1, jitterMs10 = 38)

        assertEquals(report, roundTrip(report))
        assertFalse(ControlCodec.needsAck(report))
        assertEquals(
            Control.StreamReport(5, 65_535, 65_535, 65_535, 65_535, 65_535),
            roundTrip(Control.StreamReport(5, 900_000, 900_000, 900_000, 900_000, 900_000)),
        )
    }

    @Test
    fun theBytesOfAReportAreTheOnesTheWindowsHubWrites() {
        assertArrayEquals(
            byteArrayOf(13, 0, 0, 0, 4, 0, 5, 0x01, 0xF4.toByte(), 0x01, 0xA4.toByte(), 0, 6, 0, 1, 0, 38),
            ControlCodec.encode(4, Control.StreamReport(streamId = 5, windowMs = 500, fragments = 420, resendRequests = 6, lostFrames = 1, jitterMs10 = 38)),
        )
    }

    @Test
    fun senderStatsThatDoNotFitTheFieldsAreCapped() {
        val capped = roundTrip(Control.SenderStats(1, bitrateKbps = 900_000, fps10 = -5, encodeMs10 = 70_000, sendMs10 = 0))

        assertEquals(Control.SenderStats(1, 65_535, 0, 65_535, 0), capped)
    }

    @Test
    fun aSubscribeRequestNobodyShouldMakeIsNotDecoded() {
        listOf(
            Control.Subscribe(1, "bad\nname", 720, 1080, 2_000),
            Control.Subscribe(1, "   ", 720, 1080, 2_000),
            Control.Subscribe(1, "alpha", 0, 1080, 2_000),
            Control.Subscribe(1, "alpha", 720, 0, 2_000),
            Control.Subscribe(1, "alpha", 60_000, 1080, 2_000),
            Control.Subscribe(1, "alpha", 720, 1080, 0),
        ).forEach { assertEquals(it.toString(), Decoded.Unrecognized(1), ControlCodec.decode(ControlCodec.encode(1, it))) }
    }

    @Test
    fun theFrameRateTravelsWithASubscribeAndIsLeftOutWhenItIsTheDefault() {
        val sixty = Control.Subscribe(1, "alpha", 720, 1080, 2_000, audio = true, fps = 60)
        val plain = Control.Subscribe(1, "alpha", 720, 1080, 2_000, audio = true)

        assertEquals(sixty, roundTrip(sixty))
        assertEquals(30, (roundTrip(plain) as Control.Subscribe).fps)
        assertEquals("the default adds no byte, so older hubs read the same message", ControlCodec.encode(1, plain).size + 1, ControlCodec.encode(1, sixty).size)
    }

    @Test
    fun aFrameRateOutOfRangeIsBroughtBackIntoIt() {
        val bytes = ControlCodec.encode(1, Control.Subscribe(1, "alpha", 720, 1080, 2_000, fps = 60))

        assertEquals(60, ((ControlCodec.decode(bytes.also { it[it.size - 1] = 0xC8.toByte() }) as Decoded.Message).control as Control.Subscribe).fps)
        assertEquals(1, ((ControlCodec.decode(bytes.also { it[it.size - 1] = 0 }) as Decoded.Message).control as Control.Subscribe).fps)
    }

    @Test
    fun aSubscribeWithoutTheAudioByteMeansNoAudio() {
        val withFlag = ControlCodec.encode(1, Control.Subscribe(1, "alpha", 720, 1080, 2_000, audio = true))

        val decoded = ControlCodec.decode(withFlag.copyOf(withFlag.size - 1)) as Decoded.Message

        assertEquals(Control.Subscribe(1, "alpha", 720, 1080, 2_000, audio = false), decoded.control)
    }

    @Test
    fun aTruncatedSubscribeIsRefusedWithoutThrowing() {
        val full = ControlCodec.encode(1, Control.Subscribe(1, "alpha", 720, 1080, 2_000))
        // The audio byte is optional, so a request cut right after the name is still a request.
        for (length in 5 until full.size - 1) assertEquals("length $length", Decoded.Unrecognized(1), ControlCodec.decode(full.copyOf(length)))
    }
}
