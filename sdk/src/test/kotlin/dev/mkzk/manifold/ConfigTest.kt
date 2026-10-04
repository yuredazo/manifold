package dev.mkzk.manifold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigTest {

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun aMinimalConfigOnlyNeedsAName() {
        val config = ManifoldSender.Config("avatar")
        assertEquals(0, config.width)
        assertEquals(0, config.height)
        assertEquals(0, config.fps)
        assertFalse(config.hasAudio)
    }

    @Test
    fun badNamesAreRejectedBeforeAnythingIsSent() {
        rejected { ManifoldSender.Config("") }
        rejected { ManifoldSender.Config("   ") }
        rejected { ManifoldSender.Config("two\nlines") }
        rejected { ManifoldSender.Config("x".repeat(Manifold.MAX_NAME_LENGTH + 1)) }
    }

    @Test
    fun aNameAtTheLimitIsFine() {
        ManifoldSender.Config("x".repeat(Manifold.MAX_NAME_LENGTH))
    }

    @Test
    fun outOfRangeNumbersAreRejected() {
        rejected { ManifoldSender.Config("a", width = -1) }
        rejected { ManifoldSender.Config("a", height = Manifold.MAX_DIMENSION + 1) }
        rejected { ManifoldSender.Config("a", fps = Manifold.MAX_FPS + 1) }
    }

    @Test
    fun copyChangesOnlyWhatIsAsked() {
        val original = ManifoldSender.Config("avatar", width = 640, height = 480, fps = 30, hasAudio = true)

        val bigger = original.copy(width = 1280, height = 720)

        assertEquals(ManifoldSender.Config("avatar", 1280, 720, 30, true), bigger)
        assertNotEquals(original, bigger)
    }

    @Test
    fun equalConfigsHaveEqualHashCodesAndReadableText() {
        val a = ManifoldSender.Config("avatar", 1, 2, 3, true)
        val b = ManifoldSender.Config("avatar", 1, 2, 3, true)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals("Config(name=avatar, width=1, height=2, fps=3, hasAudio=true)", a.toString())
    }

    @Test
    fun nameValidationIgnoresSurroundingSpaces() {
        assertTrue(Manifold.isValidName("  avatar  "))
        assertFalse(Manifold.isValidName("\t"))
    }
}
