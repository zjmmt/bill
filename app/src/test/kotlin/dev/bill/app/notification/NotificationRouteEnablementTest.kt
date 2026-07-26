package dev.bill.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRouteEnablementTest {
    @Test
    fun `defaults stay disabled and stale persisted ids are discarded`() {
        val state = NotificationRouteEnablementState(
            availableRouteIds = setOf("fixture-payment", "fixture-refund"),
            enabledRouteIds = setOf("removed-route"),
        )

        assertFalse(state.isEnabled("fixture-payment"))
        assertEquals(emptySet<String>(), state.enabledRouteIds())
    }

    @Test
    fun `only static route ids can be enabled or persisted`() {
        val state = NotificationRouteEnablementState(
            availableRouteIds = setOf("fixture-payment"),
            enabledRouteIds = emptySet(),
        )

        assertFalse(state.setEnabled("unknown-route", true))
        assertTrue(state.setEnabled("fixture-payment", true))
        assertTrue(state.isEnabled("fixture-payment"))
        assertEquals(setOf("fixture-payment"), state.enabledRouteIds())

        assertTrue(state.setEnabled("fixture-payment", false))
        assertFalse(state.isEnabled("fixture-payment"))
    }
}
