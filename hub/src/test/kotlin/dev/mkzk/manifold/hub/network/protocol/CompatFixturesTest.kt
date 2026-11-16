package dev.mkzk.manifold.hub.network.protocol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Windows hub is written in Rust and has to speak the same bytes, so both suites read fixtures/compat.txt. */
class CompatFixturesTest {

    private val expected: Map<String, String> = File("../fixtures/compat.txt").readLines()
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
        check("control_subscribe_alpha", ControlCodec.encode(3, Control.Subscribe(7, "alpha", 720, 1080, 2500, alpha = true)))
        check(
            "control_feedlist",
            ControlCodec.encode(4, Control.FeedList(listOf(FeedInfo("alpha", 720, 1080, 30, false), FeedInfo("camera", 1280, 720, 30, true)))),
        )
        check("control_feedlist_alpha", ControlCodec.encode(8, Control.FeedList(listOf(FeedInfo("avatar", 720, 1080, 30, false, hasAlpha = true)))))
        check("control_feedlist_sound", ControlCodec.encode(5, Control.FeedList(listOf(FeedInfo("PC sound", 0, 0, 0, true, soundOnly = true)))))
        check(
            "control_feedlist_titles",
            ControlCodec.encode(6, Control.FeedList(listOf(FeedInfo("alpha", 720, 1080, 30, false, title = "Alpha: new tab"), FeedInfo("camera", 1280, 720, 30, true)))),
        )
        check("control_keyframe", ControlCodec.encode(5, Control.KeyframeRequest(9)))
        check("control_refused", ControlCodec.encode(7, Control.SubscribeRefused(9, Control.Refusal.NOT_SHARED)))
        check("control_ack", ControlCodec.encodeAck(77))
        check("control_confirm", ControlCodec.encode(6, Control.PairConfirm))
        check("control_addresses", ControlCodec.encode(9, Control.Addresses(listOf(Address("100.77.195.122", 47200), Address("192.168.0.100", 8000)))))
        check("control_pointer", ControlCodec.encode(10, Control.Pointer(9, 3, 32768, 65535)))
        check("control_motion", ControlCodec.encode(11, Control.Motion(9, -5, 300)))
        check("control_button", ControlCodec.encode(12, Control.Button(9, 4, MouseButton.RIGHT, down = true, x = 100, y = 200)))
        check("control_button_free", ControlCodec.encode(13, Control.Button(9, 5, MouseButton.LEFT, down = false)))
        check("control_scroll", ControlCodec.encode(14, Control.Scroll(9, 0, -120)))
        check("control_release", ControlCodec.encode(15, Control.Release(9, 6)))
        check("control_key", ControlCodec.encode(18, Control.Key(9, 7, 4, down = true)))
        check("control_text", ControlCodec.encode(19, Control.Text(9, 8, "h\u00e9")))
        check("control_captured", ControlCodec.encode(17, Control.PointerCaptured(9, true)))
        check("control_rumble", ControlCodec.encode(21, Control.Rumble(9, 1, 255, 64)))
        check("control_clipboard", ControlCodec.encode(3, Control.Clipboard(7, 5, 0, 1, "hi".toByteArray())))
        check(
            "control_feedlist_clipboard",
            ControlCodec.encode(9, Control.FeedList(listOf(FeedInfo("alpha", 720, 1080, 30, false, controllable = true, clipboard = true)))),
        )
        check("control_gamepad", ControlCodec.encode(20, Control.Gamepad(9, 9, 1, PadState(0x1001, 128, 255, 32767, -32768, 256, -1))))
        check("control_feedlist_control", ControlCodec.encode(16, Control.FeedList(listOf(FeedInfo("desk", 1920, 1080, 30, true, controllable = true)))))
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
