package dev.mkzk.manifold.hub.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayWindowTest {

    @Test
    fun aNewCounterIsAcceptedOnceOnly() {
        val window = ReplayWindow()

        assertTrue(window.accept(0))
        assertFalse(window.accept(0))
        assertTrue(window.accept(1))
        assertFalse(window.accept(1))
    }

    @Test
    fun anOlderCounterInsideTheWindowIsAcceptedOnce() {
        val window = ReplayWindow(size = 64)
        assertTrue(window.accept(10))

        assertTrue(window.accept(7))
        assertFalse(window.accept(7))
        assertTrue(window.accept(9))
    }

    @Test
    fun aCounterOlderThanTheWindowIsRefused() {
        val window = ReplayWindow(size = 64)
        assertTrue(window.accept(100))

        assertFalse(window.accept(36))
        assertTrue(window.accept(37))
    }

    @Test
    fun aBigJumpForgetsEverythingBefore() {
        val window = ReplayWindow(size = 64)
        assertTrue(window.accept(5))

        assertTrue(window.accept(5 + 1000))
        assertFalse(window.accept(5))
        assertFalse(window.accept(5 + 1000))
    }

    @Test
    fun theWindowKeepsWorkingAfterItWrapsAround() {
        val window = ReplayWindow(size = 64)

        for (counter in 0L until 500) assertTrue("counter $counter", window.accept(counter))
        for (counter in 440L until 500) assertFalse("replay of $counter", window.accept(counter))
        assertTrue(window.accept(500))
    }

    @Test
    fun aNegativeCounterIsRefused() {
        assertFalse(ReplayWindow().accept(-1))
    }
}
