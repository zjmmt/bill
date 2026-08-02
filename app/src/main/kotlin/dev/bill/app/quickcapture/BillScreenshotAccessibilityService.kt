package dev.bill.app.quickcapture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import dev.bill.application.PhotoOcrCommitLease
import dev.bill.application.PhotoOcrTranscriptEvidence
import dev.bill.application.SourceCaptureError
import dev.bill.application.SourceCaptureResult
import dev.bill.app.BillApplication
import dev.bill.source.genericphotoocr.OcrTranscript
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Dedicated, user-enabled screenshot boundary.
 *
 * No accessibility event content, node tree, package name, gesture, or global action is read.
 * A screenshot exists only after the process-local Quick Settings command gate calls [capture].
 */
class BillScreenshotAccessibilityService : AccessibilityService(), QuickScreenshotGateway {
    override fun onServiceConnected() {
        BillQuickCaptureRuntime.controller.attach(this)
    }

    override fun capture(
        commandId: String,
        completion: (QuickCaptureOutcome) -> Unit,
    ): QuickCaptureCancellation {
        val cancellation = QuickCaptureCancellationSignal()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            completeQuickCaptureIfActive(
                cancellation = cancellation,
                completion = completion,
                outcome = QuickCaptureOutcome.Failed(QuickCaptureFailure.UNSUPPORTED_DEVICE),
            )
            return cancellation
        }
        captureOnAndroidR(commandId, cancellation, completion)
        return cancellation
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun captureOnAndroidR(
        commandId: String,
        cancellation: QuickCaptureCancellationSignal,
        completion: (QuickCaptureOutcome) -> Unit,
    ) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val bufferClosed = AtomicBoolean(false)
                        val closeBuffer = {
                            if (bufferClosed.compareAndSet(false, true)) {
                                hardwareBuffer.close()
                            }
                        }
                        if (!cancellation.isActive()) {
                            closeBuffer()
                            return
                        }
                        val processor = try {
                            screenshotProcessor()
                        } catch (_: RuntimeException) {
                            closeBuffer()
                            completeQuickCaptureIfActive(
                                cancellation = cancellation,
                                completion = completion,
                                outcome = QuickCaptureOutcome.Failed(
                                    QuickCaptureFailure.EVIDENCE_REJECTED,
                                ),
                            )
                            return
                        }
                        val processing = BillQuickCaptureRuntime.processingScope.launch {
                            try {
                                cancellation.throwIfCancelled()
                                val outcome = try {
                                    processor.process(
                                        commandId,
                                        hardwareBuffer,
                                        colorSpace,
                                        cancellation,
                                        closeBuffer,
                                    )
                                } catch (cancellationException: CancellationException) {
                                    throw cancellationException
                                } catch (_: RuntimeException) {
                                    QuickCaptureOutcome.Failed(QuickCaptureFailure.OCR_FAILED)
                                }
                                completeQuickCaptureIfActive(cancellation, completion, outcome)
                            } catch (_: CancellationException) {
                                // A timed-out or detached request owns no UI callback or evidence.
                            } finally {
                                closeBuffer()
                            }
                        }
                        cancellation.attach(processing)
                        processing.invokeOnCompletion {
                            cancellation.detach(processing)
                            closeBuffer()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        completeQuickCaptureIfActive(
                            cancellation = cancellation,
                            completion = completion,
                            outcome = QuickCaptureOutcome.Failed(
                                mapScreenshotFailure(errorCode),
                            ),
                        )
                    }
                },
            )
        } catch (_: SecurityException) {
            completeQuickCaptureIfActive(
                cancellation = cancellation,
                completion = completion,
                outcome = QuickCaptureOutcome.Failed(
                    QuickCaptureFailure.PERMISSION_REVOKED,
                ),
            )
        } catch (_: IllegalStateException) {
            completeQuickCaptureIfActive(
                cancellation = cancellation,
                completion = completion,
                outcome = QuickCaptureOutcome.Failed(
                    QuickCaptureFailure.SERVICE_DISCONNECTED,
                ),
            )
        } catch (_: RuntimeException) {
            completeQuickCaptureIfActive(
                cancellation = cancellation,
                completion = completion,
                outcome = QuickCaptureOutcome.Failed(
                    QuickCaptureFailure.SCREENSHOT_FAILED,
                ),
            )
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun screenshotProcessor(): OnDeviceScreenshotOcrProcessor =
        OnDeviceScreenshotOcrProcessor(
            applicationContext = applicationContext,
            capture = (application as BillApplication)
                .container
                .photoOcrTranscriptIngestionService,
        )

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun mapScreenshotFailure(errorCode: Int): QuickCaptureFailure = when (errorCode) {
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            QuickCaptureFailure.PERMISSION_REVOKED

        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            QuickCaptureFailure.RATE_LIMITED

        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            QuickCaptureFailure.SECURE_WINDOW

        else -> QuickCaptureFailure.SCREENSHOT_FAILED
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally no-op: this service is command-driven, not event-driven.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        BillQuickCaptureRuntime.controller.detach(this)
        super.onDestroy()
    }
}

private fun completeQuickCaptureIfActive(
    cancellation: QuickCaptureCancellationSignal,
    completion: (QuickCaptureOutcome) -> Unit,
    outcome: QuickCaptureOutcome,
) {
    if (cancellation.complete()) completion(outcome)
}

internal class QuickCaptureCancellationSignal : QuickCaptureCancellation {
    private val state = AtomicReference(State.ACTIVE)
    private val processingJob = AtomicReference<Job?>(null)

    override fun cancel(): Boolean {
        while (true) {
            when (state.get()) {
                State.COMMITTING,
                State.COMPLETED,
                -> return false

                State.CANCELLED -> return true
                State.ACTIVE -> {
                    if (state.compareAndSet(State.ACTIVE, State.CANCELLED)) {
                        processingJob.getAndSet(null)?.cancel()
                        return true
                    }
                }
            }
        }
    }

    fun isActive(): Boolean = state.get() == State.ACTIVE

    fun tryBeginLocalCommit(): Boolean =
        state.compareAndSet(State.ACTIVE, State.COMMITTING)

    fun throwIfCancelled() {
        if (!isActive()) throw CancellationException("Quick capture lease is no longer active")
    }

    fun attach(job: Job) {
        if (!processingJob.compareAndSet(null, job)) {
            job.cancel()
            return
        }
        if (state.get() == State.CANCELLED && processingJob.compareAndSet(job, null)) {
            job.cancel()
        }
    }

    fun detach(job: Job) {
        processingJob.compareAndSet(job, null)
    }

    fun complete(): Boolean {
        while (true) {
            when (val current = state.get()) {
                State.CANCELLED,
                State.COMPLETED,
                -> return false

                State.ACTIVE,
                State.COMMITTING,
                -> if (state.compareAndSet(current, State.COMPLETED)) {
                    processingJob.set(null)
                    return true
                }
            }
        }
    }

    private enum class State {
        ACTIVE,
        COMMITTING,
        CANCELLED,
        COMPLETED,
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
private class OnDeviceScreenshotOcrProcessor(
    private val applicationContext: android.content.Context,
    private val capture: dev.bill.application.PhotoOcrTranscriptCapture,
) {

    suspend fun process(
        commandId: String,
        hardwareBuffer: HardwareBuffer,
        colorSpace: ColorSpace,
        cancellation: QuickCaptureCancellationSignal,
        releasePixels: () -> Unit,
    ): QuickCaptureOutcome {
        var hardwareBitmap: Bitmap? = null
        var ocrBitmap: Bitmap? = null
        val transcriptBytes: ByteArray
        try {
            cancellation.throwIfCancelled()
            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                ?: return failed(QuickCaptureFailure.SCREENSHOT_FAILED)
            ocrBitmap = boundedSoftwareCopy(hardwareBitmap)
                ?: return failed(QuickCaptureFailure.IMAGE_TOO_LARGE)

            cancellation.throwIfCancelled()
            transcriptBytes = when (
                val recognized = BundledLocalOcrEngine.recognize(applicationContext, ocrBitmap)
            ) {
                is LocalOcrResult.Lines -> OcrTranscript.encodeSpatial(recognized.values)
                    ?: return failed(QuickCaptureFailure.OCR_OUTPUT_TOO_LARGE)

                LocalOcrResult.Empty -> OcrTranscript.encodeEmpty()
                LocalOcrResult.Failed -> return failed(QuickCaptureFailure.OCR_FAILED)
            }
            cancellation.throwIfCancelled()
        } finally {
            ocrBitmap?.recycle()
            hardwareBitmap?.recycle()
            releasePixels()
        }
        return ingestQuickCaptureTranscript(
            commandId = commandId,
            transcriptBytes = transcriptBytes,
            cancellation = cancellation,
            capture = capture,
        )
    }

    private fun boundedSoftwareCopy(source: Bitmap): Bitmap? {
        val sourceWidth = source.width
        val sourceHeight = source.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
        if (sourcePixels > MAX_SOURCE_PIXELS) return null

        val scale = minOf(
            1.0,
            MAX_OCR_WIDTH.toDouble() / sourceWidth.toDouble(),
            MAX_OCR_HEIGHT.toDouble() / sourceHeight.toDouble(),
            kotlin.math.sqrt(MAX_OCR_PIXELS.toDouble() / sourcePixels.toDouble()),
        )
        val targetWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val target = Bitmap.createBitmap(
            targetWidth,
            targetHeight,
            Bitmap.Config.ARGB_8888,
        )
        Canvas(target).drawBitmap(
            source,
            null,
            Rect(0, 0, targetWidth, targetHeight),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
        )
        return target
    }

    private fun failed(reason: QuickCaptureFailure) = QuickCaptureOutcome.Failed(reason)

    private companion object {
        const val MAX_SOURCE_PIXELS = 32_000_000L
        const val MAX_OCR_PIXELS = 2_560_000L
        const val MAX_OCR_WIDTH = 1_600
        const val MAX_OCR_HEIGHT = 1_600
    }
}

internal suspend fun ingestQuickCaptureTranscript(
    commandId: String,
    transcriptBytes: ByteArray,
    cancellation: QuickCaptureCancellationSignal,
    capture: dev.bill.application.PhotoOcrTranscriptCapture,
): QuickCaptureOutcome = try {
    cancellation.throwIfCancelled()
    when (
        val result = capture.ingestWithCommitLease(
            commandId = commandId,
            evidence = PhotoOcrTranscriptEvidence(transcriptBytes),
            commitLease = PhotoOcrCommitLease(cancellation::tryBeginLocalCommit),
        )
    ) {
        is SourceCaptureResult.ReadyForReview ->
            QuickCaptureOutcome.Saved(result.alreadyPresent)

        is SourceCaptureResult.Failure -> QuickCaptureOutcome.Failed(
            when (result.error) {
                SourceCaptureError.STORAGE_LIMIT_REACHED ->
                    QuickCaptureFailure.STORAGE_LIMIT_REACHED

                SourceCaptureError.EMPTY_CONTENT ->
                    QuickCaptureFailure.EMPTY_OCR

                SourceCaptureError.COMMIT_STATUS_UNKNOWN ->
                    QuickCaptureFailure.RESULT_UNCONFIRMED

                else -> QuickCaptureFailure.EVIDENCE_REJECTED
            },
        )
    }
} finally {
    transcriptBytes.fill(0)
}
