package dev.mkzk.manifold.hub.network.protocol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Windows hub is written in Dart and has to speak the same bytes, so both suites read desktop/test/fixtures/compat.txt. */
class CompatFixturesTest {

    private val expected: Map<String, String> = File("../desktop/test/fixtures/compat.txt").readLines()
        .filter { it.isNotBlank() }
        .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

    private fun check(name: String, bytes: ByteArray) = assertEquals(name, expected.getValue(name), bytes.toHex())

    @Test
    fun sessionPacketsMatchTheFixture() {
        val sender = Session(1, 2, TransportKeys(send = ByteArray(32) { 1 }, receive = ByteArray(32) { 2 }))

        check("session_control", sender.seal(Stream.Control, byteArrayOf(0xAA.toByte(), 0xBB.toByte())))
        check("session_video", sender.seal(Stream.Video, byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun controlMessagesMatchTheFixture() {
        check("control_subscribe", ControlCodec.encode(3, Control.Subscribe(7, "alpha", 720, 1080, 2500)))
        check("control_subscribe_audio", ControlCodec.encode(3, Control.Subscribe(7, "alpha", 720, 1080, 2500, audio = true)))
        check(
            "control_feedlist",
            ControlCodec.encode(4, Control.FeedList(listOf(FeedInfo("alpha", 720, 1080, 30, false), FeedInfo("camera", 1280, 720, 30, true)))),
        )
        check("control_feedlist_sound", ControlCodec.encode(5, Control.FeedList(listOf(FeedInfo("PC sound", 0, 0, 0, true, soundOnly = true)))))
        check(
            "control_feedlist_titles",
            ControlCodec.encode(6, Control.FeedList(listOf(FeedInfo("alpha", 720, 1080, 30, false, title = "Alpha: new tab"), FeedInfo("camera", 1280, 720, 30, true)))),
        )
        check("control_keyframe", ControlCodec.encode(5, Control.KeyframeRequest(9)))
        check("control_refused", ControlCodec.encode(7, Control.SubscribeRefused(9, Control.Refusal.NOT_SHARED)))
        check("control_ack", ControlCodec.encodeAck(77))
        check("control_confirm", ControlCodec.encode(6, Control.PairConfirm))
    }

    @Test
    fun videoFragmentsMatchTheFixture() {
        val small = Fragmenter.split(Frame(9, 90_000, true, ByteArray(100) { (it * 7).toByte() })).single()
        check("fragment_small", small)
        check("videopacket_small", VideoPacket.encode(12, small))

        val large = Fragmenter.split(Frame(-2, -90_000, false, ByteArray(3000) { (it * 31).toByte() }))
        assertEquals(expected.getValue("fragment_large_count").toInt(), large.size)
        check("fragment_large_hash", Crypto.sha256(*large.toTypedArray()))
    }

    @Test
    fun audioPacketsMatchTheFixture() {
        check("audiopacket", AudioPacket.encode(12, 90_000, byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun beaconsMatchTheFixture() {
        check("beacon", BeaconCodec.encode(Beacon("0123456789abcdef", 47200, "Pixel")))
    }

    @Test
    fun handshakePacketsMatchTheFixture() {
        check("wire_hello", Wire.encode(Wire.Handshake(Wire.TYPE_HELLO, 1, -5, 77, byteArrayOf(1, 2, 3))))
        check("wire_pair", Wire.encode(Wire.Handshake(Wire.TYPE_PAIR, 0, 123_456, 0, ByteArray(0))))
    }

    @Test
    fun theFixtureHasEveryEntryTheTestsUse() {
        assertTrue(expected.keys.containsAll(listOf("session_control", "control_subscribe", "fragment_small", "wire_hello")))
    }
}
