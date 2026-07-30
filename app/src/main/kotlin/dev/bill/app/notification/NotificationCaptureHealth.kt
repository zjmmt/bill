package dev.bill.app.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A user-visible, process-local health summary. It intentionally contains no provider identity,
 * notification text, timestamps or transaction data. It is diagnostic state, not accounting data.
 */
internal data class NotificationCaptureHealthSnapshot(
    val state: NotificationCaptureHealthState,
    val hasSystemAccess: Boolean,
    val droppedInThisProcess: Long,
    val failuresInThisProcess: Long,
) {
    init {
        require(droppedInThisProcess >= 0L && failuresInThisProcess >= 0L)
    }
}

internal enum class NotificationCaptureHealthState {
    NO_VERIFIED_TEMPLATES,
    NO_ENABLED_ROUTES,
    SYSTEM_ACCESS_REQUIRED,
    LISTENER_CONNECTION_PENDING,
    READY,
    BACKPRESSURE,
    RECENT_FAILURE,
}

internal class NotificationCaptureHealth(
    private val hasVerifiedTemplates: Boolean,
    hasEnabledRoutes: Boolean = hasVerifiedTemplates,
    hasSystemAccess: Boolean = true,
    hasListenerConnection: Boolean = false,
) {
    private var hasEnabledRoutes = hasEnabledRoutes
    private var hasSystemAccess = hasSystemAccess
    private var hasListenerConnection = hasListenerConnection
    private var dropped = 0L
    private var failures = 0L
    private val mutableState = MutableStateFlow(snapshot())

    val state: StateFlow<NotificationCaptureHealthSnapshot> = mutableState.asStateFlow()

    @Synchronized
    fun onConfigurationChanged(
        hasEnabledRoutes: Boolean,
        hasSystemAccess: Boolean,
    ) {
        this.hasEnabledRoutes = hasEnabledRoutes
        this.hasSystemAccess = hasSystemAccess
        if (!hasSystemAccess) {
            hasListenerConnection = false
        }
        publish()
    }

    @Synchronized
    fun onListenerConnectionChanged(connected: Boolean) {
        hasListenerConnection = connected
        publish()
    }

    @Synchronized
    fun onQueueDropped() {
        dropped = incrementSafely(dropped)
        publish()
    }

    @Synchronized
    fun onCaptureFailure() {
        failures = incrementSafely(failures)
        publish()
    }

    @Synchronized
    fun onCaptureRecorded() {
        publish()
    }

    private fun publish() {
        mutableState.value = snapshot()
    }

    private fun snapshot() = NotificationCaptureHealthSnapshot(
        state = when {
            !hasVerifiedTemplates -> NotificationCaptureHealthState.NO_VERIFIED_TEMPLATES
            !hasEnabledRoutes -> NotificationCaptureHealthState.NO_ENABLED_ROUTES
            !hasSystemAccess -> NotificationCaptureHealthState.SYSTEM_ACCESS_REQUIRED
            !hasListenerConnection ->
                NotificationCaptureHealthState.LISTENER_CONNECTION_PENDING
            failures > 0L -> NotificationCaptureHealthState.RECENT_FAILURE
            dropped > 0L -> NotificationCaptureHealthState.BACKPRESSURE
            else -> NotificationCaptureHealthState.READY
        },
        hasSystemAccess = hasSystemAccess,
        droppedInThisProcess = dropped,
        failuresInThisProcess = failures,
    )

    private fun incrementSafely(value: Long): Long =
        if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L
}
