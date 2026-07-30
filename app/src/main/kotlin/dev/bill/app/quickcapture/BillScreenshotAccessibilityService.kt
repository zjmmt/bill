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
import dev.bill.application.PhotoOcrTranscriptEvidence
import dev.bill.application.SourceCaptureError
import dev.bill.application.SourceCaptureResult
import dev.bill.app.BillApplication
import dev.bill.source.genericphotoocr.OcrTranscript
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Dedicated, user-enabled screenshot boundary.
 *
 * No accessibility event content, node tree, package name, gesture, or global action is read.
 * A screenshot exists only after the process-local Quick Settings command gate calls [capture].
 */
class BillScreenshotAccessibilityService : AccessibilityService(), QuickScreenshotGateway {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onServiceConnected() {
        BillQuickCaptureRuntime.controller.attach(this)
    }

    override fun capture(
        commandId: String,
        completion: (QuickCaptureOutcome) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            completion(
                QuickCaptureOutcome.Failed(QuickCaptureFailure.UNSUPPORTED_DEVICE),
            )
            return
        }
        captureOnAndroidR(commandId, completion)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun captureOnAndroidR(
        commandId: String,
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
                        val pendingOwnership = AtomicBoolean(true)
                        val processing = serviceScope.launch {
                            if (!pendingOwnership.compareAndSet(true, false)) return@launch
                            val outcome = try {
                                try {
                                    screenshotProcessor().process(
                                        commandId,
                                        hardwareBuffer,
                                        colorSpace,
                                    )
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: RuntimeException) {
                                    QuickCaptureOutcome.Failed(QuickCaptureFailure.OCR_FAILED)
                                }
                            } finally {
                                hardwareBuffer.close()
                            }
                            completion(outcome)
                        }
                        processing.invokeOnCompletion {
                            if (pendingOwnership.compareAndSet(true, false)) {
                                hardwareBuffer.close()
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        completion(
                            QuickCaptureOutcome.Failed(mapScreenshotFailure(errorCode)),
                        )
                    }
                },
            )
        } catch (_: SecurityException) {
            completion(
                QuickCaptureOutcome.Failed(QuickCaptureFailure.PERMISSION_REVOKED),
            )
        } catch (_: IllegalStateException) {
            completion(
                QuickCaptureOutcome.Failed(QuickCaptureFailure.SERVICE_DISCONNECTED),
            )
        } catch (_: RuntimeException) {
            completion(
                QuickCaptureOutcome.Failed(QuickCaptureFailure.SCREENSHOT_FAILED),
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
        serviceScope.cancel()
        super.onDestroy()
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
    ): QuickCaptureOutcome {
        var hardwareBitmap: Bitmap? = null
        var ocrBitmap: Bitmap? = null
        try {
            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                ?: return failed(QuickCaptureFailure.SCREENSHOT_FAILED)
            ocrBitmap = boundedSoftwareCopy(hardwareBitmap)
                ?: return failed(QuickCaptureFailure.IMAGE_TOO_LARGE)

            val lines = when (
                val recognized = BundledLocalOcrEngine.recognize(applicationContext, ocrBitmap)
            ) {
                is LocalOcrResult.Lines -> recognized.values
                LocalOcrResult.Empty -> return failed(QuickCaptureFailure.EMPTY_OCR)
                LocalOcrResult.Failed -> return failed(QuickCaptureFailure.OCR_FAILED)
            }
            val transcriptBytes = OcrTranscript.encode(lines)
                ?: return failed(QuickCaptureFailure.OCR_OUTPUT_TOO_LARGE)
            return when (
                val result = capture.ingest(
                    commandId = commandId,
                    evidence = PhotoOcrTranscriptEvidence(transcriptBytes),
                )
            ) {
                is SourceCaptureResult.ReadyForReview ->
                    QuickCaptureOutcome.Saved(result.alreadyPresent)

                is SourceCaptureResult.Failure -> failed(
                    when (result.error) {
                        SourceCaptureError.STORAGE_LIMIT_REACHED ->
                            QuickCaptureFailure.STORAGE_LIMIT_REACHED

                        SourceCaptureError.EMPTY_CONTENT ->
                            QuickCaptureFailure.EMPTY_OCR

                        else -> QuickCaptureFailure.EVIDENCE_REJECTED
                    },
                )
            }
        } finally {
            ocrBitmap?.recycle()
            hardwareBitmap?.recycle()
        }
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
