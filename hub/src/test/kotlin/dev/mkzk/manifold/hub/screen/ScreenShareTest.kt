package dev.mkzk.manifold.hub.screen

import dev.mkzk.manifold.hub.broker.Registry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenShareTest {

    @Test
    fun aScreenWithinTheLimitKeepsItsSize() {
        val size = scaledCapture(1080, 1920, 420)

        assertEquals(1080, size.width)
        assertEquals(1920, size.height)
        assertEquals(420, size.dpi)
    }

    @Test
    fun aLongerScreenIsScaledToTheLimitKeepingItsShape() {
        val size = scaledCapture(1080, 2400, 420)

        assertEquals(864, size.width)
        assertEquals(1920, size.height)
    }

    @Test
    fun aLandscapeScreenIsScaledOnItsLongEdge() {
        val size = scaledCapture(3840, 2160, 320)

        assertEquals(1920, size.width)
        assertEquals(1080, size.height)
    }

    @Test
    fun sizesAreAlwaysEven() {
        for ((width, height) in listOf(1081 to 2401, 721 to 1281, 1439 to 3119)) {
            val size = scaledCapture(width, height, 320)

            assertTrue("${size.width}x${size.height} from ${width}x$height", size.width % 2 == 0 && size.height % 2 == 0)
        }
    }

    @Test
    fun mutedAppsAreSavedAndKeptWithoutAShare() {
        var saved: Set<String>? = null
        val share = ScreenShare(Registry(), setOf("com.example.music")) { saved = it }

        share.setMuted(setOf("com.example.music", "dev.mkzk.parallax"))

        assertEquals(setOf("com.example.music", "dev.mkzk.parallax"), share.muted.value)
        assertEquals(share.muted.value, saved)
    }
}
