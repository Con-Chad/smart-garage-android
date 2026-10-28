package io.github.conchad.smartgarage.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickInfoAfterSettleTest {
    @Test
    fun openDrawerClosesWhenAnotherVehicleSettles() {
        assertNull(quickInfoAfterSettle(openId = "a", settledId = "b"))
    }

    @Test
    fun openDrawerStaysOpenOnItsOwnVehicle() {
        assertEquals("a", quickInfoAfterSettle(openId = "a", settledId = "a"))
    }

    @Test
    fun nothingOpenStaysClosed() {
        assertNull(quickInfoAfterSettle(openId = null, settledId = "a"))
    }

    @Test
    fun noSettledVehicleClosesTheDrawer() {
        assertNull(quickInfoAfterSettle(openId = "a", settledId = null))
    }
}
