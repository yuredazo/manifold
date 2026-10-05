package dev.mkzk.manifold.hub.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetransmitStoreTest {

    private val second = 1_000_000_000L

    private fun fragments(size: Int) = Fragmenter.split(Frame(1, 0, false, ByteArray(size)))

    @Test
    fun theRequestedFragmentsComeBackUnchanged() {
        val store = RetransmitStore()
        val kept = fragments(10_000)
        store.remember(1, kept, now = 0)

        val again = store.fragments(1, listOf(0, 2))

        assertEquals(2, again.size)
        assertArrayEquals(kept[0], again[0])
        assertArrayEquals(kept[2], again[1])
    }

    @Test
    fun noIndexesMeansTheWholeFrame() {
        val store = RetransmitStore()
        val kept = fragments(10_000)
        store.remember(1, kept, now = 0)

        assertEquals(kept.size, store.fragments(1, emptyList()).size)
    }

    @Test
    fun aFrameThatWasNeverKeptOrIsOutOfRangeGivesNothing() {
        val store = RetransmitStore()
        store.remember(1, fragments(3_000), now = 0)

        assertTrue(store.fragments(2, emptyList()).isEmpty())
        assertTrue(store.fragments(1, listOf(99)).isEmpty())
    }

    @Test
    fun aFrameIsAnsweredAtMostSixTimes() {
        val store = RetransmitStore()
        store.remember(1, fragments(3_000), now = 0)

        val answers = (1..10).count { store.fragments(1, emptyList()).isNotEmpty() }

        assertEquals(6, answers)
    }

    @Test
    fun oldFramesAreForgotten() {
        val store = RetransmitStore(keepFor = second)
        store.remember(1, fragments(3_000), now = 0)
        store.remember(2, fragments(3_000), now = 2 * second)

        assertTrue(store.fragments(1, emptyList()).isEmpty())
        assertTrue(store.fragments(2, emptyList()).isNotEmpty())
    }

    @Test
    fun theOldestFramesGoFirstWhenTheStoreIsFull() {
        val store = RetransmitStore(keepBytes = 25_000)
        (1..4).forEach { store.remember(it, fragments(10_000), now = it.toLong()) }

        assertTrue(store.fragments(1, emptyList()).isEmpty())
        assertTrue(store.fragments(2, emptyList()).isEmpty())
        assertTrue(store.fragments(4, emptyList()).isNotEmpty())
    }
}
