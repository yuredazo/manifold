package dev.mkzk.manifold.hub.network.stream

import dev.mkzk.manifold.hub.network.protocol.Fragmenter
import dev.mkzk.manifold.hub.network.protocol.Frame
import dev.mkzk.manifold.hub.network.protocol.Session
import dev.mkzk.manifold.hub.network.protocol.VideoPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTest {

    private val ms = 1_000_000L

    private fun frame(id: Int, size: Int, keyframe: Boolean = false) =
        Frame(id, timestamp = id * 3000, keyframe = keyframe, encoded = ByteArray(size) { (it * 31 + id).toByte() })

    private fun FrameBuffer.feed(frame: Frame, now: Long = 0, drop: Set<Int> = emptySet(), order: List<Int>? = null): List<Frame> {
        val fragments = Fragmenter.split(frame)
        return (order ?: fragments.indices.toList()).filter { it !in drop }.flatMap { add(fragments[it], now) }.map { it.frame }
    }

    @Test
    fun framesOfEverySizeComeBackIdentical() {
        val sizes = listOf(0, 1, 100, Fragmenter.MAX_CHUNK - 1, Fragmenter.MAX_CHUNK, Fragmenter.MAX_CHUNK + 1, 50_000, 300_000)
        sizes.forEachIndexed { index, size ->
            val buffer = FrameBuffer()
            val original = frame(index, size, keyframe = true)

            val rebuilt = buffer.feed(original).single()

            assertEquals("size $size", original.id, rebuilt.id)
            assertEquals(original.timestamp, rebuilt.timestamp)
            assertTrue(rebuilt.keyframe)
            assertArrayEquals("size $size", original.encoded, rebuilt.encoded)
        }
    }

    @Test
    fun everyFragmentFitsInOneEncryptedPacket() {
        Fragmenter.split(frame(1, 200_000)).forEach { assertTrue(it.size <= Session.MAX_PAYLOAD) }
    }

    @Test
    fun fragmentsMayArriveInAnyOrderAndTwice() {
        val buffer = FrameBuffer()
        val original = frame(1, 10_000, keyframe = true)
        val count = Fragmenter.split(original).size

        val rebuilt = buffer.feed(original, order = (0 until count).reversed() + listOf(0, 1))

        assertArrayEquals(original.encoded, rebuilt.single().encoded)
    }

    @Test
    fun theTimeAFrameTookToArriveIsReported() {
        val buffer = FrameBuffer()
        val fragments = Fragmenter.split(frame(1, 5_000, keyframe = true))

        val before = fragments.dropLast(1).flatMap { buffer.add(it, 10 * ms) }
        val done = buffer.add(fragments.last(), 16 * ms)

        assertTrue(before.isEmpty())
        assertEquals(6 * ms, done.single().assemblyTime)
    }

    @Test
    fun nothingIsHandedOutBeforeTheFirstKeyframe() {
        val buffer = FrameBuffer()

        assertTrue(buffer.needsKeyframe)
        assertTrue(buffer.feed(frame(1, 500)).isEmpty())
        assertEquals(1, buffer.feed(frame(2, 500, keyframe = true)).size)
        assertFalse(buffer.needsKeyframe)
    }

    @Test
    fun aMissingFragmentIsAskedForAndTheFrameGoesOnWhenItArrives() {
        val buffer = FrameBuffer()
        buffer.feed(frame(1, 5_000, keyframe = true))
        val fragments = Fragmenter.split(frame(2, 5_000))
        fragments.indices.filter { it != 2 }.forEach { buffer.add(fragments[it], 100 * ms) }

        assertTrue("too early to tell loss from reordering", buffer.poll(101 * ms).missing.isEmpty())
        val asked = buffer.poll(100 * ms + buffer.reorderWait).missing.single()
        assertEquals(2, asked.frameId)
        assertEquals(listOf(2), asked.indexes)

        val delivered = buffer.add(fragments[2], 110 * ms)
        assertEquals(listOf(2), delivered.map { it.frame.id })
        assertEquals(0, buffer.lostFrames)
    }

    @Test
    fun theSamePieceIsAskedForAtMostSixTimes() {
        val buffer = FrameBuffer().apply { maxWait = Long.MAX_VALUE / 4 }
        val fragments = Fragmenter.split(frame(1, 5_000, keyframe = true))
        buffer.add(fragments.first(), 0)

        val asked = (1..40).count { buffer.poll(it * 20 * ms).missing.isNotEmpty() }

        assertEquals(6, asked)
    }

    @Test
    fun eachAskWaitsLongerThanTheOneBefore() {
        val buffer = FrameBuffer().apply { maxWait = Long.MAX_VALUE / 4 }
        buffer.add(Fragmenter.split(frame(1, 5_000, keyframe = true)).first(), 0)

        val askedAt = (1..1_000).filter { buffer.poll(it * ms).missing.isNotEmpty() }
        val gaps = askedAt.zipWithNext { a, b -> b - a }

        assertEquals(6, askedAt.size)
        assertTrue("gaps $gaps ms", gaps.zipWithNext().all { (before, after) -> after >= 2 * before - 1 })
    }

    @Test
    fun laterFramesWaitBehindAnIncompleteOneAndAreReleasedInOrder() {
        val buffer = FrameBuffer()
        buffer.feed(frame(1, 500, keyframe = true))
        val blocked = Fragmenter.split(frame(2, 5_000))
        blocked.dropLast(1).forEach { buffer.add(it, 0) }

        assertTrue("frame 3 is whole but frame 2 comes first", buffer.feed(frame(3, 500), now = 5 * ms).isEmpty())

        val released = buffer.add(blocked.last(), 8 * ms).map { it.frame.id }
        assertEquals(listOf(2, 3), released)
    }

    @Test
    fun aWholeFrameThatNeverArrivedIsAskedForAsAWhole() {
        val buffer = FrameBuffer()
        buffer.feed(frame(1, 500, keyframe = true))
        buffer.feed(frame(2, 500))
        buffer.feed(frame(4, 500), now = 100 * ms)

        val asked = buffer.poll(100 * ms + buffer.reorderWait).missing.single()

        assertEquals(3, asked.frameId)
        assertTrue(asked.indexes.isEmpty())
    }

    @Test
    fun aFrameThatNeverCompletesIsGivenUpAndTheNextKeyframeIsWaitedFor() {
        val buffer = FrameBuffer()
        buffer.feed(frame(1, 5_000, keyframe = true))
        buffer.feed(frame(2, 5_000))
        buffer.feed(frame(3, 5_000), now = 0, drop = setOf(2))
        buffer.feed(frame(4, 5_000), now = 10 * ms)

        val result = buffer.poll(buffer.maxWait + 1)

        assertTrue("frame 4 is a delta frame that cannot be decoded without frame 3", result.frames.isEmpty())
        assertEquals(1, buffer.lostFrames)
        assertTrue(buffer.needsKeyframe)

        assertEquals(listOf(5), buffer.feed(frame(5, 5_000, keyframe = true), now = buffer.maxWait + 2 * ms).map { it.id })
        assertFalse(buffer.needsKeyframe)
        assertEquals(listOf(6), buffer.feed(frame(6, 5_000), now = buffer.maxWait + 3 * ms).map { it.id })
    }

    @Test
    fun aLateFragmentOfAnOldFrameIsIgnored() {
        val buffer = FrameBuffer()
        val old = Fragmenter.split(frame(1, 5_000, keyframe = true))
        buffer.feed(frame(2, 5_000, keyframe = true))

        assertTrue(buffer.add(old.first(), 0).isEmpty())
        assertEquals(0, buffer.lostFrames)
    }

    @Test
    fun malformedFragmentsAreDropped() {
        val buffer = FrameBuffer()
        val good = Fragmenter.split(frame(1, 5_000, keyframe = true)).first()

        assertTrue(buffer.add(ByteArray(3), 0).isEmpty())
        assertTrue(buffer.add(good.copyOf().also { it[11] = 0; it[12] = 0 }, 0).isEmpty())
        assertTrue(buffer.add(good.copyOf().also { it[9] = 0x7F; it[10] = 0x7F }, 0).isEmpty())
        assertTrue(buffer.add(good.copyOf().also { it[11] = 0x7F; it[12] = 0x7F }, 0).isEmpty())
    }

    @Test
    fun frameIdsKeepWorkingWhenTheyWrapAround() {
        val buffer = FrameBuffer()

        assertEquals(1, buffer.feed(frame(Int.MAX_VALUE - 1, 100, keyframe = true)).size)
        assertEquals(1, buffer.feed(frame(Int.MAX_VALUE, 100)).size)
        assertEquals(1, buffer.feed(frame(Int.MIN_VALUE, 100)).size)
        assertEquals(0, buffer.lostFrames)
    }

    @Test
    fun aJumpFarAheadStartsOverAndCountsWhatWasWaiting() {
        val buffer = FrameBuffer()
        buffer.feed(frame(1, 500, keyframe = true))
        buffer.feed(frame(3, 500), now = 0)

        buffer.feed(frame(1_000, 500, keyframe = true), now = ms)

        assertEquals("the frame that was waiting behind the gap", 1, buffer.lostFrames)
        assertFalse(buffer.needsKeyframe)
    }

    @Test
    fun theStreamIdTravelsWithTheFragmentAndStillFitsOnePacket() {
        val fragment = Fragmenter.split(frame(1, 500_000, keyframe = true)).first()

        val packet = VideoPacket.encode(54_321, fragment)

        assertTrue(packet.size <= Session.MAX_PAYLOAD)
        assertEquals(54_321, VideoPacket.streamOf(packet))
        assertArrayEquals(fragment, VideoPacket.fragmentOf(packet))
    }

    @Test
    fun aVideoPacketWithoutAFragmentHasNoStream() {
        assertNull(VideoPacket.streamOf(ByteArray(0)))
        assertNull(VideoPacket.streamOf(ByteArray(2)))
    }

    @Test
    fun aDecoderThatFellBehindMakesTheBufferWaitForAKeyframe() {
        val buffer = FrameBuffer()
        assertEquals(1, buffer.feed(frame(1, 500, keyframe = true)).size)
        assertFalse(buffer.needsKeyframe)

        buffer.needKeyframe()

        assertTrue(buffer.needsKeyframe)
        assertTrue("a delta frame is no use now", buffer.feed(frame(2, 500)).isEmpty())
        assertEquals(1, buffer.feed(frame(3, 500, keyframe = true)).size)
        assertFalse(buffer.needsKeyframe)
    }
}
