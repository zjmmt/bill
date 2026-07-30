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

    @Test
    fun `failed enable persistence never opens the runtime gate`() {
        val state = NotificationRouteEnablementState(
            availableRouteIds = setOf("fixture-payment"),
            enabledRouteIds = emptySet(),
        )
        var persistedIds: Set<String>? = null

        val updated = state.setEnabled(
            routeId = "fixture-payment",
            enabled = true,
            persist = { proposed ->
                persistedIds = proposed
                false
            },
        )

        assertFalse(updated)
        assertEquals(setOf("fixture-payment"), persistedIds)
        assertFalse(state.isEnabled("fixture-payment"))
        assertEquals(emptySet<String>(), state.enabledRouteIds())
    }

    @Test
    fun `failed disable persistence keeps the runtime gate closed and can retry`() {
        val state = NotificationRouteEnablementState(
            availableRouteIds = setOf("fixture-payment"),
            enabledRouteIds = setOf("fixture-payment"),
        )
        var persistenceAttempts = 0

        val firstUpdate = state.setEnabled(
            routeId = "fixture-payment",
            enabled = false,
            persist = {
                persistenceAttempts += 1
                false
            },
        )

        assertFalse(firstUpdate)
        assertFalse(state.isEnabled("fixture-payment"))
        assertEquals(emptySet<String>(), state.enabledRouteIds())

        val retry = state.setEnabled(
            routeId = "fixture-payment",
            enabled = false,
            persist = {
                persistenceAttempts += 1
                true
            },
        )

        assertTrue(retry)
        assertEquals(2, persistenceAttempts)
        assertFalse(state.isEnabled("fixture-payment"))
    }

    @Test
    fun `failed disable is process local until durable retry succeeds`() {
        val currentProcess = NotificationRouteEnablementState(
            availableRouteIds = setOf("fixture-payment"),
            enabledRouteIds = setOf("fixture-payment"),
        )

        assertFalse(
            currentProcess.setEnabled("fixture-payment", false) {
                false
            },
        )
        assertFalse(currentProcess.isEnabled("fixture-payment"))

        // A fresh process can only read the old durable value. The UI therefore blocks other
        // route changes and tells the user to retry or revoke Android notification access before
        // leaving; this test prevents us from accidentally claiming a cross-process guarantee.
        val restartedProcess = NotificationRouteEnablementState(
            availableRouteIds = setOf("fixture-payment"),
            enabledRouteIds = setOf("fixture-payment"),
        )
        assertTrue(restartedProcess.isEnabled("fixture-payment"))
    }

    @Test
    fun `runtime persistence exceptions use the same fail-closed semantics`() {
        val disabled = NotificationRouteEnablementState(
            availableRouteIds = setOf("fixture-payment"),
            enabledRouteIds = emptySet(),
        )
        val enabled = NotificationRouteEnablementState(
            availableRouteIds = setOf("fixture-payment"),
            enabledRouteIds = setOf("fixture-payment"),
        )

        assertFalse(
            disabled.setEnabled("fixture-payment", true) {
                throw IllegalStateException("synthetic write failure")
            },
        )
        assertFalse(disabled.isEnabled("fixture-payment"))

        assertFalse(
            enabled.setEnabled("fixture-payment", false) {
                throw IllegalStateException("synthetic write failure")
            },
        )
        assertFalse(enabled.isEnabled("fixture-payment"))
    }
}
