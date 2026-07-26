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
        val health = NotificationCaptureHealth(hasVerifiedTemplates = true)
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
}
