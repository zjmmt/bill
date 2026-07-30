package dev.bill.app.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationCaptureHealthTest {
    @Test
    fun `empty catalog is visibly closed without content details`() {
        val health = NotificationCaptureHealth(hasVerifiedTemplates = false)

        assertEquals(
            NotificationCaptureHealthState.NO_VERIFIED_TEMPLATES,
            health.state.value.state,
        )
        assertEquals(0L, health.state.value.droppedInThisProcess)
        assertEquals(0L, health.state.value.failuresInThisProcess)
    }

    @Test
    fun `queue pressure and failures expose only safe aggregate counts`() {
        val health = NotificationCaptureHealth(
            hasVerifiedTemplates = true,
            hasListenerConnection = true,
        )
        health.onQueueDropped()
        health.onQueueDropped()
        health.onCaptureFailure()

        assertEquals(NotificationCaptureHealthState.RECENT_FAILURE, health.state.value.state)
        assertEquals(2L, health.state.value.droppedInThisProcess)
        assertEquals(1L, health.state.value.failuresInThisProcess)

        health.onCaptureRecorded()
        assertEquals(NotificationCaptureHealthState.RECENT_FAILURE, health.state.value.state)
        assertEquals(2L, health.state.value.droppedInThisProcess)
        assertEquals(1L, health.state.value.failuresInThisProcess)
    }

    @Test
    fun `verified catalog stays closed until a route and system access are both enabled`() {
        val health = NotificationCaptureHealth(
            hasVerifiedTemplates = true,
            hasEnabledRoutes = false,
            hasSystemAccess = false,
        )

        assertEquals(
            NotificationCaptureHealthState.NO_ENABLED_ROUTES,
            health.state.value.state,
        )

        health.onConfigurationChanged(
            hasEnabledRoutes = true,
            hasSystemAccess = false,
        )
        assertEquals(
            NotificationCaptureHealthState.SYSTEM_ACCESS_REQUIRED,
            health.state.value.state,
        )

        health.onConfigurationChanged(
            hasEnabledRoutes = true,
            hasSystemAccess = true,
        )
        assertEquals(
            NotificationCaptureHealthState.LISTENER_CONNECTION_PENDING,
            health.state.value.state,
        )

        health.onListenerConnectionChanged(connected = true)
        assertEquals(NotificationCaptureHealthState.READY, health.state.value.state)
        assertEquals(true, health.state.value.hasSystemAccess)
        assertEquals(true, health.state.value.hasListenerConnection)

        health.onListenerConnectionChanged(connected = false)
        assertEquals(
            NotificationCaptureHealthState.LISTENER_CONNECTION_PENDING,
            health.state.value.state,
        )
        assertEquals(false, health.state.value.hasListenerConnection)

        health.onListenerConnectionChanged(connected = true)
        health.onConfigurationChanged(
            hasEnabledRoutes = true,
            hasSystemAccess = false,
        )
        health.onConfigurationChanged(
            hasEnabledRoutes = true,
            hasSystemAccess = true,
        )
        assertEquals(
            NotificationCaptureHealthState.LISTENER_CONNECTION_PENDING,
            health.state.value.state,
        )
    }

    @Test
    fun `operational failure keeps priority over later queue pressure`() {
        val health = NotificationCaptureHealth(
            hasVerifiedTemplates = true,
            hasListenerConnection = true,
        )

        health.onCaptureFailure()
        health.onQueueDropped()

        assertEquals(NotificationCaptureHealthState.RECENT_FAILURE, health.state.value.state)
        assertEquals(1L, health.state.value.droppedInThisProcess)
        assertEquals(1L, health.state.value.failuresInThisProcess)
    }

    @Test
    fun `disabling every route hides older operational errors behind actionable closure`() {
        val health = NotificationCaptureHealth(
            hasVerifiedTemplates = true,
            hasListenerConnection = true,
        )
        health.onCaptureFailure()

        health.onConfigurationChanged(
            hasEnabledRoutes = false,
            hasSystemAccess = true,
        )

        assertEquals(NotificationCaptureHealthState.NO_ENABLED_ROUTES, health.state.value.state)
        assertEquals(1L, health.state.value.failuresInThisProcess)
    }
}
