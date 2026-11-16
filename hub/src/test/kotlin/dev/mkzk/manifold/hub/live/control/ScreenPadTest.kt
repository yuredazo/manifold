package dev.mkzk.manifold.hub.live.control

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenPadTest {

    @Test
    fun aDpadNearItsMiddleHoldsNothing() {
        assertEquals(0, dpadBits(Offset(0.2f, -0.2f)))
    }

    @Test
    fun aDpadPushedStraightHoldsOneDirection() {
        assertEquals(PAD_UP, dpadBits(Offset(0f, -1f)))
        assertEquals(PAD_DOWN, dpadBits(Offset(0f, 1f)))
        assertEquals(PAD_LEFT, dpadBits(Offset(-1f, 0f)))
        assertEquals(PAD_RIGHT, dpadBits(Offset(1f, 0f)))
    }

    @Test
    fun aDpadPushedDiagonallyHoldsTwo() {
        assertEquals(PAD_UP or PAD_RIGHT, dpadBits(Offset(0.7f, -0.7f)))
        assertEquals(PAD_DOWN or PAD_LEFT, dpadBits(Offset(-0.7f, 0.7f)))
    }

    @Test
    fun controllerBitsAreNamedInTheEditorsOrder() {
        assertEquals(listOf("A", "LT", "Start"), padNames(PAD_START or PAD_A or PAD_LEFT_TRIGGER_KEY))
    }
}
