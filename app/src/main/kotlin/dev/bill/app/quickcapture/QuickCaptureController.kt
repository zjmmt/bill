package dev.bill.app.quickcapture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class QuickCaptureFailure {
    UNSUPPORTED_DEVICE,
    SERVICE_DISCONNECTED,
    PERMISSION_REVOKED,
    SECURE_WINDOW,
    RATE_LIMITED,
    SCREENSHOT_FAILED,
    IMAGE_TOO_LARGE,
    EMPTY_OCR,
    OCR_FAILED,
    OCR_OUTPUT_TOO_LARGE,
    EVIDENCE_REJECTED,
    STORAGE_LIMIT_REACHED,
}

sealed interface QuickCaptureOutcome {
    data class Saved(val alreadyPresent: Boolean) : QuickCaptureOutcome

    data class Failed(val reason: QuickCaptureFailure) : QuickCaptureOutcome
}

enum class QuickCaptureRequestDisposition {
    STARTED,
    BUSY,
    SERVICE_UNAVAILABLE,
}

enum class QuickCaptureConnectionState {
    DISCONNECTED,
    CONNECTED,
}

fun interface QuickScreenshotGateway {
    fun capture(
        commandId: String,
        completion: (QuickCaptureOutcome) -> Unit,
    )
}

/**
 * Process-local, zero-queue command gate between a Quick Settings tile and AccessibilityService.
 *
 * It never stores screenshots or OCR text. A detached gateway completes its active request once
 * with a safe failure, and a late callback from the old service is ignored.
 */
class SingleFlightQuickCaptureController {
    private val lock = Any()
    private var gateway: QuickScreenshotGateway? = null
    private var activeRequest: ActiveRequest? = null
    private val mutableConnectionState = MutableStateFlow(QuickCaptureConnectionState.DISCONNECTED)

    val connectionState: StateFlow<QuickCaptureConnectionState> =
        mutableConnectionState.asStateFlow()

    fun attach(attachedGateway: QuickScreenshotGateway) {
        val interrupted = synchronized(lock) {
            val previousGateway = gateway
            val completion = activeRequest
                ?.takeIf { previousGateway != null && it.gateway === previousGateway }
                ?.also { activeRequest = null }
                ?.completion
            gateway = attachedGateway
            mutableConnectionState.value = QuickCaptureConnectionState.CONNECTED
            completion
        }
        interrupted?.invoke(
            QuickCaptureOutcome.Failed(QuickCaptureFailure.SERVICE_DISCONNECTED),
        )
    }

    fun detach(detachedGateway: QuickScreenshotGateway) {
        val completion = synchronized(lock) {
            if (gateway !== detachedGateway) return
            gateway = null
            mutableConnectionState.value = QuickCaptureConnectionState.DISCONNECTED
            activeRequest
                ?.takeIf { it.gateway === detachedGateway }
                ?.also { activeRequest = null }
                ?.completion
        }
        completion?.invoke(
            QuickCaptureOutcome.Failed(QuickCaptureFailure.SERVICE_DISCONNECTED),
        )
    }

    fun request(
        commandId: String,
        completion: (QuickCaptureOutcome) -> Unit,
    ): QuickCaptureRequestDisposition {
        val selectedGateway = synchronized(lock) {
            if (activeRequest != null) return QuickCaptureRequestDisposition.BUSY
            val connected = gateway ?: return QuickCaptureRequestDisposition.SERVICE_UNAVAILABLE
            activeRequest = ActiveRequest(
                gateway = connected,
                completion = completion,
            )
            connected
        }
        return try {
            selectedGateway.capture(commandId) { outcome ->
                complete(selectedGateway, outcome)
            }
            QuickCaptureRequestDisposition.STARTED
        } catch (_: RuntimeException) {
            complete(
                selectedGateway,
                QuickCaptureOutcome.Failed(QuickCaptureFailure.SCREENSHOT_FAILED),
            )
            QuickCaptureRequestDisposition.STARTED
        }
    }

    private fun complete(
        completedGateway: QuickScreenshotGateway,
        outcome: QuickCaptureOutcome,
    ) {
        val completion = synchronized(lock) {
            activeRequest
                ?.takeIf { it.gateway === completedGateway }
                ?.also { activeRequest = null }
                ?.completion
        }
        completion?.invoke(outcome)
    }

    private data class ActiveRequest(
        val gateway: QuickScreenshotGateway,
        val completion: (QuickCaptureOutcome) -> Unit,
    )
}

object BillQuickCaptureRuntime {
    val controller = SingleFlightQuickCaptureController()
}
