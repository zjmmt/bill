package dev.bill.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent

/**
 * A deliberately dormant boundary for future verified payment-result readers.
 *
 * The service has no active source package, no event subscription, no node lookup, no screenshot
 * capability and no UI automation. It exists so the permission tutorial can identify the separate
 * Android setting without quietly turning a future reader into a broad screen collector.
 */
class BillPaymentResultObserverService : AccessibilityService() {
    override fun onServiceConnected() {
        val disabledInfo = serviceInfo ?: return
        disabledInfo.eventTypes = 0
        disabledInfo.packageNames = arrayOf(DISABLED_PACKAGE)
        disabledInfo.notificationTimeout = DISABLED_NOTIFICATION_TIMEOUT_MILLIS
        disabledInfo.flags = disabledInfo.flags and
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS.inv()
        serviceInfo = disabledInfo
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally no-op. No provider template is allowed to activate this service yet.
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val DISABLED_PACKAGE = "dev.bill.disabled"
        const val DISABLED_NOTIFICATION_TIMEOUT_MILLIS = 500L
    }
}
