package dev.bill.app.notification

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

internal fun interface NotificationListenerAccess {
    fun isGranted(): Boolean
}

internal class AndroidNotificationListenerAccess(
    context: Context,
) : NotificationListenerAccess {
    private val applicationContext = context.applicationContext ?: context

    override fun isGranted(): Boolean = try {
        applicationContext.packageName in
            NotificationManagerCompat.getEnabledListenerPackages(applicationContext)
    } catch (_: RuntimeException) {
        false
    }
}

internal fun openNotificationListenerSettings(context: Context): Boolean {
    val intents = listOf(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    ).map { intent ->
        intent.apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next system-owned settings surface.
        } catch (_: SecurityException) {
            // An OEM may block a specific settings surface; fall back without crashing Bill.
        }
    }
    return false
}

/** Requests one system-managed bind attempt without starting a background service ourselves. */
internal fun requestBillNotificationListenerRebind(context: Context): Boolean = try {
    NotificationListenerService.requestRebind(
        ComponentName(context, BillNotificationListenerService::class.java),
    )
    true
} catch (_: RuntimeException) {
    false
}
