package dev.bill.app.quickcapture

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import dev.bill.app.R

/**
 * Collapses Quick Settings before capture, then immediately leaves the target app visible again.
 *
 * This activity has no layout, input, screenshot, OCR, or persistence capability.
 */
class QuickCaptureRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val commandId = intent
            ?.takeIf { it.action == ACTION_CAPTURE }
            ?.getStringExtra(EXTRA_COMMAND_ID)
            ?.takeIf(::isValidCommandId)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        if (commandId != null) {
            val appContext = applicationContext
            Handler(Looper.getMainLooper()).postDelayed(
                { requestCapture(appContext, commandId) },
                CAPTURE_SETTLE_MILLIS,
            )
        }
    }

    companion object {
        private fun requestCapture(
            context: Context,
            commandId: String,
        ) {
            val disposition = BillQuickCaptureRuntime.controller.request(commandId) { outcome ->
                Handler(Looper.getMainLooper()).post {
                    showOutcome(context, outcome)
                    BillQuickCaptureTileService.requestTileRefresh(context)
                }
            }
            when (disposition) {
                QuickCaptureRequestDisposition.STARTED -> Unit
                QuickCaptureRequestDisposition.BUSY ->
                    showToast(context, R.string.quick_capture_busy)

                QuickCaptureRequestDisposition.SERVICE_UNAVAILABLE ->
                    showToast(context, R.string.quick_capture_enable_accessibility)
            }
        }

        private fun showOutcome(
            context: Context,
            outcome: QuickCaptureOutcome,
        ) {
            val messageRes = when (outcome) {
                is QuickCaptureOutcome.Saved -> if (outcome.alreadyPresent) {
                    R.string.quick_capture_already_saved
                } else {
                    R.string.quick_capture_saved
                }

                is QuickCaptureOutcome.Failed -> when (outcome.reason) {
                    QuickCaptureFailure.UNSUPPORTED_DEVICE ->
                        R.string.quick_capture_unsupported

                    QuickCaptureFailure.SERVICE_DISCONNECTED,
                    QuickCaptureFailure.PERMISSION_REVOKED,
                    -> R.string.quick_capture_enable_accessibility

                    QuickCaptureFailure.SECURE_WINDOW ->
                        R.string.quick_capture_secure_window

                    QuickCaptureFailure.RATE_LIMITED ->
                        R.string.quick_capture_rate_limited

                    QuickCaptureFailure.EMPTY_OCR ->
                        R.string.quick_capture_empty_ocr

                    QuickCaptureFailure.IMAGE_TOO_LARGE,
                    QuickCaptureFailure.OCR_OUTPUT_TOO_LARGE,
                    -> R.string.quick_capture_too_large

                    QuickCaptureFailure.STORAGE_LIMIT_REACHED ->
                        R.string.quick_capture_storage_full

                    QuickCaptureFailure.TIMED_OUT ->
                        R.string.quick_capture_timed_out

                    QuickCaptureFailure.RESULT_UNCONFIRMED ->
                        R.string.quick_capture_result_unconfirmed

                    QuickCaptureFailure.SCREENSHOT_FAILED,
                    QuickCaptureFailure.OCR_FAILED,
                    QuickCaptureFailure.EVIDENCE_REJECTED,
                    -> R.string.quick_capture_failed
                }
            }
            showToast(context, messageRes)
        }

        private fun showToast(
            context: Context,
            messageRes: Int,
        ) {
            Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show()
        }

        private fun isValidCommandId(value: String): Boolean =
            value.length in 1..64 && value.all { it.isLetterOrDigit() || it == '-' }

        const val ACTION_CAPTURE = "dev.bill.app.action.QUICK_CAPTURE"
        const val EXTRA_COMMAND_ID = "quick_capture_command_id"
        private const val CAPTURE_SETTLE_MILLIS = 450L
    }
}
