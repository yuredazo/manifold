package dev.mkzk.manifold.hub.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraShareTest {

    private fun sizes(vararg pairs: Pair<Int, Int>) = pairs.map { (w, h) -> FrameSize(w, h) }

    private fun FrameSize?.text() = this?.let { "${it.width}x${it.height}" }

    @Test
    fun theLargestWideSizeWithinTheLimitIsChosen() {
        val chosen = outputSize(sizes(4000 to 3000, 3840 to 2160, 1920 to 1080, 1440 to 1080, 1280 to 720))

        assertEquals("1920x1080", chosen.text())
    }

    @Test
    fun aCameraWithoutWideSizesGetsItsLargestFittingOne() {
        val chosen = outputSize(sizes(2592 to 1944, 1600 to 1200, 1280 to 960, 640 to 480))

        assertEquals("1600x1200", chosen.text())
    }

    @Test
    fun aCameraWithNothingSmallEnoughHasNoSize() {
        assertNull(outputSize(sizes(4000 to 3000, 3840 to 2160)))
    }

    @Test
    fun aPhoneHeldUprightStaysUnturned() {
        assertEquals(0, quarterTurn(10, current = 0))
        assertEquals(0, quarterTurn(350, current = 0))
    }

    @Test
    fun theTurnChangesOnlyWellPastTheHalfwayPoint() {
        assertEquals(0, quarterTurn(55, current = 0))
        assertEquals(90, quarterTurn(65, current = 0))
        assertEquals(90, quarterTurn(35, current = 90))
        assertEquals(0, quarterTurn(25, current = 90))
        assertEquals(270, quarterTurn(295, current = 0))
    }

    @Test
    fun aPhoneLyingFlatKeepsItsLastTurn() {
        assertEquals(180, quarterTurn(-1, current = 180))
    }

    @Test
    fun aSidewaysSensorGivesAPortraitPictureOnAnUprightPhone() {
        assertEquals("1080x1920", pictureSize(FrameSize(1920, 1080), sensorTurn = 90, phoneTurn = 0).text())
        assertEquals("1080x1920", pictureSize(FrameSize(1920, 1080), sensorTurn = 270, phoneTurn = 180).text())
    }

    @Test
    fun turningThePhoneSidewaysGivesALandscapePicture() {
        assertEquals("1920x1080", pictureSize(FrameSize(1920, 1080), sensorTurn = 90, phoneTurn = 90).text())
        assertEquals("1920x1080", pictureSize(FrameSize(1920, 1080), sensorTurn = 270, phoneTurn = 270).text())
    }
}
