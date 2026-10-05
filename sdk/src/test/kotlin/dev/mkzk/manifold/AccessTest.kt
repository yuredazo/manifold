package dev.mkzk.manifold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccessTest {

    @Test
    fun theHubsValuesMapToTheMatchingState() {
        assertEquals(ManifoldReceiver.Access.PENDING, accessOf(Manifold.ACCESS_PENDING))
        assertEquals(ManifoldReceiver.Access.ALLOWED, accessOf(Manifold.ACCESS_ALLOWED))
        assertEquals(ManifoldReceiver.Access.BLOCKED, accessOf(Manifold.ACCESS_BLOCKED))
    }

    @Test
    fun aValueFromANewerHubCountsAsPending() {
        listOf(-1, 3, 4, 99, Int.MAX_VALUE, Int.MIN_VALUE).forEach {
            assertEquals("value $it", ManifoldReceiver.Access.PENDING, accessOf(it))
        }
    }

    @Test
    fun theWireValuesAreStable() {
        assertEquals(0, Manifold.ACCESS_PENDING)
        assertEquals(1, Manifold.ACCESS_ALLOWED)
        assertEquals(2, Manifold.ACCESS_BLOCKED)
        assertNotEquals(Manifold.ACCESS_ALLOWED, Manifold.ACCESS_BLOCKED)
    }
}
