package dev.bill.app.quickcapture

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    TIMED_OUT,
    RESULT_UNCONFIRMED,
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
    /**
     * Starts an asynchronous capture and returns its cancellation handle promptly.
     *
     * Implementations must return the handle before asynchronous work can cross its irreversible
     * local-commit boundary. This lets a controller timeout that races handle attachment cancel
     * the subsequently returned operation without ever misclassifying a committed write.
     */
    fun capture(
        commandId: String,
        completion: (QuickCaptureOutcome) -> Unit,
    ): QuickCaptureCancellation
}

/**
 * Invalidates one in-flight capture.
 *
 * Returns false after the gateway has crossed its local commit boundary or has already completed;
 * in either case cancellation must not be reported as successful.
 */
fun interface QuickCaptureCancellation {
    fun cancel(): Boolean
}

internal fun interface QuickCaptureTimeoutHandle {
    fun cancel()
}

internal fun interface QuickCaptureTimeoutScheduler {
    fun schedule(
        delayMillis: Long,
        task: () -> Unit,
    ): QuickCaptureTimeoutHandle
}

/**
 * Starts a daemon thread only while a capture timeout is pending and lets it retire when idle.
 *
 * This is a failure boundary, not a background capture loop. Cancelling a completed request also
 * removes its task from the executor queue.
 */
private object ExecutorQuickCaptureTimeoutScheduler : QuickCaptureTimeoutScheduler {
    private val executor = ScheduledThreadPoolExecutor(
        1,
        ThreadFactory { runnable ->
            Thread(runnable, "bill-quick-capture-timeout").apply {
                isDaemon = true
            }
        },
    ).apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(10L, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }

    override fun schedule(
        delayMillis: Long,
        task: () -> Unit,
    ): QuickCaptureTimeoutHandle {
        val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return QuickCaptureTimeoutHandle { future.cancel(false) }
    }
}

/**
 * Process-local, zero-queue command gate between a Quick Settings tile and AccessibilityService.
 *
 * It never stores screenshots or OCR text. A detached gateway completes its active request once
 * with a safe failure. Every request also has a bounded lease so a missing platform/native callback
 * cannot retain the finished relay activity or leave the tile permanently busy. Request tokens
 * ensure a timed-out callback can never complete a later request through the same service.
 */
class SingleFlightQuickCaptureController internal constructor(
    private val timeoutMillis: Long = DEFAULT_CAPTURE_TIMEOUT_MILLIS,
    private val timeoutScheduler: QuickCaptureTimeoutScheduler =
        ExecutorQuickCaptureTimeoutScheduler,
) {
    init {
        require(timeoutMillis > 0L) { "Quick capture timeout must be positive" }
    }

    private val lock = Any()
    private var gateway: QuickScreenshotGateway? = null
    private var activeRequest: ActiveRequest? = null
    private val mutableConnectionState = MutableStateFlow(QuickCaptureConnectionState.DISCONNECTED)

    val connectionState: StateFlow<QuickCaptureConnectionState> =
        mutableConnectionState.asStateFlow()

    fun attach(attachedGateway: QuickScreenshotGateway) {
        val interrupted = synchronized(lock) {
            val previousGateway = gateway
            gateway = attachedGateway
            mutableConnectionState.value = QuickCaptureConnectionState.CONNECTED
            activeRequest?.takeIf {
                previousGateway != null &&
                    previousGateway !== attachedGateway &&
                    it.gateway === previousGateway
            }
        }
        interruptIfCancellable(interrupted)
    }

    fun detach(detachedGateway: QuickScreenshotGateway) {
        val interrupted = synchronized(lock) {
            if (gateway !== detachedGateway) return
            gateway = null
            mutableConnectionState.value = QuickCaptureConnectionState.DISCONNECTED
            activeRequest
                ?.takeIf { it.gateway === detachedGateway }
        }
        interruptIfCancellable(interrupted)
    }

    fun request(
        commandId: String,
        completion: (QuickCaptureOutcome) -> Unit,
    ): QuickCaptureRequestDisposition {
        val request = synchronized(lock) {
            if (activeRequest != null) return QuickCaptureRequestDisposition.BUSY
            val connected = gateway ?: return QuickCaptureRequestDisposition.SERVICE_UNAVAILABLE
            ActiveRequest(
                identity = RequestIdentity(),
                gateway = connected,
                completion = completion,
            ).also { activeRequest = it }
        }
        val requestIdentity = request.identity
        val timeoutHandle = try {
            timeoutScheduler.schedule(timeoutMillis) {
                timeout(requestIdentity)
            }
        } catch (_: RuntimeException) {
            complete(
                identity = requestIdentity,
                outcome = QuickCaptureOutcome.Failed(QuickCaptureFailure.SCREENSHOT_FAILED),
            )
            return QuickCaptureRequestDisposition.STARTED
        }
        val timeoutStillOwned = synchronized(lock) {
            activeRequest
                ?.takeIf { it.identity === requestIdentity }
                ?.attachTimeout(timeoutHandle) == true
        }
        if (!timeoutStillOwned) {
            timeoutHandle.cancel()
            return QuickCaptureRequestDisposition.STARTED
        }
        val captureRegistrationStillOwned = synchronized(lock) {
            activeRequest
                ?.takeIf { it.identity === requestIdentity }
                ?.beginCaptureRegistration() == true
        }
        if (!captureRegistrationStillOwned) {
            return QuickCaptureRequestDisposition.STARTED
        }
        val requestGateway = request.gateway
        return try {
            val cancellation = requestGateway.capture(commandId) { outcome ->
                complete(requestIdentity, outcome)
            }
            val attachment = synchronized(lock) {
                activeRequest
                    ?.takeIf { it.identity === requestIdentity }
                    ?.attachCaptureCancellation(cancellation)
                    ?: CaptureAttachment.RequestGone
            }
            when (attachment) {
                CaptureAttachment.RequestGone -> cancelBestEffort(cancellation)
                is CaptureAttachment.Attached -> attachment.pendingFailure?.let { failure ->
                    resolveInterruption(request, failure, cancellation)
                }
            }
            QuickCaptureRequestDisposition.STARTED
        } catch (_: RuntimeException) {
            complete(
                identity = requestIdentity,
                outcome = QuickCaptureOutcome.Failed(QuickCaptureFailure.SCREENSHOT_FAILED),
            )
            QuickCaptureRequestDisposition.STARTED
        }
    }

    private fun timeout(identity: RequestIdentity) {
        val timedOutRequest = synchronized(lock) {
            activeRequest?.takeIf { it.identity === identity }
        } ?: return
        interrupt(
            request = timedOutRequest,
            failure = QuickCaptureFailure.TIMED_OUT,
        )
    }

    private fun interruptIfCancellable(request: ActiveRequest?) {
        request ?: return
        interrupt(
            request = request,
            failure = QuickCaptureFailure.SERVICE_DISCONNECTED,
        )
    }

    private fun interrupt(
        request: ActiveRequest,
        failure: QuickCaptureFailure,
    ) {
        when (val cancellation = request.requestCancellation(failure)) {
            CaptureCancellationRequest.AlreadyFinished -> Unit
            CaptureCancellationRequest.BeforeRegistration -> complete(
                identity = request.identity,
                outcome = QuickCaptureOutcome.Failed(failure),
                cancelCapture = false,
            )

            CaptureCancellationRequest.RegistrationPending -> awaitFinalization(request)
            is CaptureCancellationRequest.WithHandle ->
                resolveInterruption(request, failure, cancellation.handle)
        }
    }

    private fun resolveInterruption(
        request: ActiveRequest,
        failure: QuickCaptureFailure,
        cancellation: QuickCaptureCancellation,
    ) {
        if (cancelBestEffort(cancellation)) {
            complete(
                identity = request.identity,
                outcome = QuickCaptureOutcome.Failed(failure),
                cancelCapture = false,
            )
        } else {
            awaitFinalization(request)
        }
    }

    private fun cancelBestEffort(cancellation: QuickCaptureCancellation): Boolean =
        try {
            cancellation.cancel()
        } catch (_: RuntimeException) {
            false
        }

    private fun awaitFinalization(request: ActiveRequest) {
        val requestIdentity = request.identity
        val finalizationHandle = try {
            timeoutScheduler.schedule(FINALIZATION_GRACE_MILLIS) {
                complete(
                    identity = requestIdentity,
                    outcome = QuickCaptureOutcome.Failed(
                        QuickCaptureFailure.RESULT_UNCONFIRMED,
                    ),
                    cancelCapture = false,
                )
            }
        } catch (_: RuntimeException) {
            complete(
                identity = requestIdentity,
                outcome = QuickCaptureOutcome.Failed(
                    QuickCaptureFailure.RESULT_UNCONFIRMED,
                ),
                cancelCapture = false,
            )
            return
        }
        val stillOwned = synchronized(lock) {
            activeRequest
                ?.takeIf { it.identity === requestIdentity }
                ?.attachFinalizationTimeout(finalizationHandle) == true
        }
        if (!stillOwned) finalizationHandle.cancel()
    }

    private fun complete(
        identity: RequestIdentity,
        outcome: QuickCaptureOutcome,
        cancelCapture: Boolean = true,
    ) {
        val completed = synchronized(lock) {
            activeRequest
                ?.takeIf { it.identity === identity }
                ?.also { activeRequest = null }
        }
        completed?.finish(outcome, cancelCapture)
    }

    private class ActiveRequest(
        val identity: RequestIdentity,
        val gateway: QuickScreenshotGateway,
        completion: (QuickCaptureOutcome) -> Unit,
    ) {
        private var timeoutHandle: QuickCaptureTimeoutHandle? = null
        private var captureCancellation: QuickCaptureCancellation? = null
        private var completion: ((QuickCaptureOutcome) -> Unit)? = completion
        private var finalizationPending = false
        private var captureRegistrationStarted = false
        private var pendingInterruption: QuickCaptureFailure? = null

        fun attachTimeout(handle: QuickCaptureTimeoutHandle): Boolean = synchronized(this) {
            if (completion == null) return false
            timeoutHandle = handle
            true
        }

        fun attachFinalizationTimeout(handle: QuickCaptureTimeoutHandle): Boolean =
            synchronized(this) {
                if (completion == null || finalizationPending) return false
                finalizationPending = true
                timeoutHandle?.cancel()
                timeoutHandle = handle
                true
            }

        fun beginCaptureRegistration(): Boolean = synchronized(this) {
            if (completion == null || captureRegistrationStarted) return false
            captureRegistrationStarted = true
            true
        }

        fun attachCaptureCancellation(
            cancellation: QuickCaptureCancellation,
        ): CaptureAttachment =
            synchronized(this) {
                if (completion == null) return CaptureAttachment.RequestGone
                captureCancellation = cancellation
                CaptureAttachment.Attached(pendingInterruption)
            }

        fun requestCancellation(
            failure: QuickCaptureFailure,
        ): CaptureCancellationRequest = synchronized(this) {
            if (completion == null) return CaptureCancellationRequest.AlreadyFinished
            captureCancellation?.let { capture ->
                return CaptureCancellationRequest.WithHandle(capture)
            }
            if (!captureRegistrationStarted) {
                return CaptureCancellationRequest.BeforeRegistration
            }
            if (pendingInterruption == null) pendingInterruption = failure
            CaptureCancellationRequest.RegistrationPending
        }

        fun finish(
            outcome: QuickCaptureOutcome,
            cancelCapture: Boolean = true,
        ) {
            val resources = synchronized(this) {
                val callback = completion ?: return
                completion = null
                val timeout = timeoutHandle
                timeoutHandle = null
                val capture = captureCancellation
                captureCancellation = null
                FinishResources(timeout, capture, callback)
            }
            try {
                resources.timeout?.cancel()
            } catch (_: RuntimeException) {
                // The request is already detached from the gate; callback delivery still wins.
            }
            if (cancelCapture) {
                try {
                    resources.capture?.cancel()
                } catch (_: RuntimeException) {
                    // A broken cancellation boundary must not suppress the known result.
                }
            }
            resources.callback(outcome)
        }

        private data class FinishResources(
            val timeout: QuickCaptureTimeoutHandle?,
            val capture: QuickCaptureCancellation?,
            val callback: (QuickCaptureOutcome) -> Unit,
        )
    }

    private sealed interface CaptureAttachment {
        data object RequestGone : CaptureAttachment

        data class Attached(
            val pendingFailure: QuickCaptureFailure?,
        ) : CaptureAttachment
    }

    private sealed interface CaptureCancellationRequest {
        data object AlreadyFinished : CaptureCancellationRequest

        data object BeforeRegistration : CaptureCancellationRequest

        data object RegistrationPending : CaptureCancellationRequest

        data class WithHandle(
            val handle: QuickCaptureCancellation,
        ) : CaptureCancellationRequest
    }

    private class RequestIdentity

    private companion object {
        const val DEFAULT_CAPTURE_TIMEOUT_MILLIS = 90_000L
        const val FINALIZATION_GRACE_MILLIS = 15_000L
    }
}

object BillQuickCaptureRuntime {
    val controller = SingleFlightQuickCaptureController()
    internal val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
