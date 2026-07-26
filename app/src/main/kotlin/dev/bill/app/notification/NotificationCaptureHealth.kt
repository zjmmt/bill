package dev.bill.app.notification

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A user-visible, process-local health summary. It intentionally contains no provider identity,
 * notification text, timestamps or transaction data. It is diagnostic state, not accounting data.
 */
internal data class NotificationCaptureHealthSnapshot(
    val state: NotificationCaptureHealthState,
    val droppedInThisProcess: Long,
    val failuresInThisProcess: Long,
) {
    init {
        require(droppedInThisProcess >= 0L && failuresInThisProcess >= 0L)
    }
}

internal enum class NotificationCaptureHealthState {
    NO_VERIFIED_TEMPLATES,
    READY,
    BACKPRESSURE,
    RECENT_FAILURE,
}

internal class NotificationCaptureHealth(
    hasVerifiedTemplates: Boolean,
) {
    private val dropped = AtomicLong(0L)
    private val failures = AtomicLong(0L)
    private val mutableState = MutableStateFlow(
        snapshot(
            if (hasVerifiedTemplates) {
                NotificationCaptureHealthState.READY
            } else {
                NotificationCaptureHealthState.NO_VERIFIED_TEMPLATES
            },
        ),
    )

    val state: StateFlow<NotificationCaptureHealthSnapshot> = mutableState.asStateFlow()

    fun onQueueDropped() {
        dropped.incrementAndGet()
        mutableState.value = snapshot(NotificationCaptureHealthState.BACKPRESSURE)
    }

    fun onCaptureFailure() {
        failures.incrementAndGet()
        mutableState.value = snapshot(NotificationCaptureHealthState.RECENT_FAILURE)
    }

    fun onCaptureRecorded() {
        mutableState.value = snapshot(
            when {
                failures.get() > 0L -> NotificationCaptureHealthState.RECENT_FAILURE
                dropped.get() > 0L -> NotificationCaptureHealthState.BACKPRESSURE
                else -> NotificationCaptureHealthState.READY
            },
        )
    }

    private fun snapshot(state: NotificationCaptureHealthState) = NotificationCaptureHealthSnapshot(
        state = state,
        droppedInThisProcess = dropped.get(),
        failuresInThisProcess = failures.get(),
    )
}
