package dev.bill.app.quickcapture

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import dev.bill.app.R
import kotlin.math.roundToInt

/** Schedules one main-thread removal without introducing a timer, service or wake lock. */
internal fun interface QuickCaptureFlashScheduler {
    fun postDelayed(delayMillis: Long, action: () -> Unit)
}

/** A content-free surface. [show] replaces any older flash owned by this service. */
internal interface QuickCaptureFlashSurface {
    fun show(): Boolean

    fun hide()
}

/**
 * Owns the short visual receipt for a successfully acquired screenshot.
 *
 * Generation matching prevents an older delayed removal from hiding a newer flash.
 */
internal class QuickCaptureFlashFeedback(
    private val surface: QuickCaptureFlashSurface,
    private val scheduler: QuickCaptureFlashScheduler,
) {
    private var generation = 0L

    @MainThread
    fun show() {
        val shownGeneration = ++generation
        if (!surface.show()) return
        scheduler.postDelayed(QUICK_CAPTURE_FLASH_VISIBLE_MILLIS) {
            if (generation == shownGeneration) {
                generation += 1
                surface.hide()
            }
        }
    }

    @MainThread
    fun clear() {
        generation += 1
        surface.hide()
    }
}

/** Creates feedback only while the user-enabled accessibility service is connected. */
internal fun createQuickCaptureFlashFeedback(
    service: AccessibilityService,
): QuickCaptureFlashFeedback {
    val handler = Handler(Looper.getMainLooper())
    return QuickCaptureFlashFeedback(
        surface = AccessibilityQuickCaptureFlashSurface(service),
        scheduler = QuickCaptureFlashScheduler { delayMillis, action ->
            handler.postDelayed(action, delayMillis)
        },
    )
}

private class AccessibilityQuickCaptureFlashSurface(
    private val service: AccessibilityService,
) : QuickCaptureFlashSurface {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var currentView: View? = null

    override fun show(): Boolean {
        hide()
        val flashView = View(service).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(ContextCompat.getColor(service, R.color.quick_capture_flash_fill))
                setStroke(
                    (QUICK_CAPTURE_FLASH_STROKE_DP * resources.displayMetrics.density)
                        .roundToInt()
                        .coerceAtLeast(1),
                    ContextCompat.getColor(service, R.color.quick_capture_flash_stroke),
                )
            }
        }
        return try {
            windowManager.addView(flashView, quickCaptureFlashLayoutParams())
            currentView = flashView
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun hide() {
        val flashView = currentView ?: return
        currentView = null
        try {
            windowManager.removeViewImmediate(flashView)
        } catch (_: RuntimeException) {
            // The system may already have detached the overlay while the service is stopping.
        }
    }
}

internal fun quickCaptureFlashLayoutParams(): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        title = "Bill screenshot feedback"
    }

internal const val QUICK_CAPTURE_FLASH_VISIBLE_MILLIS = 180L
private const val QUICK_CAPTURE_FLASH_STROKE_DP = 6f
