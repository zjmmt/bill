package dev.bill.app.notification

import android.content.Context
import dev.bill.source.genericnotification.NotificationRouteCatalog

/**
 * Only opaque ids from the static catalog are persisted. Package names, channels, categories and
 * notification text are never copied into preferences.
 */
internal interface NotificationRouteEnablement {
    fun isEnabled(routeId: String): Boolean

    /** Returns false when the route is unknown, removed, or its new state was not durably saved. */
    fun setEnabled(routeId: String, enabled: Boolean): Boolean

    fun enabledRouteIds(): Set<String>
}

internal class NotificationRouteEnablementState(
    availableRouteIds: Set<String>,
    enabledRouteIds: Set<String>,
) {
    private val availableRouteIds = availableRouteIds.toSet()

    @Volatile
    private var enabledRouteIds = enabledRouteIds.intersect(this.availableRouteIds).toSet()

    fun isEnabled(routeId: String): Boolean = routeId in enabledRouteIds

    @Synchronized
    fun setEnabled(routeId: String, enabled: Boolean): Boolean {
        if (routeId !in availableRouteIds) return false
        enabledRouteIds = if (enabled) {
            enabledRouteIds + routeId
        } else {
            enabledRouteIds - routeId
        }
        return true
    }

    fun enabledRouteIds(): Set<String> = enabledRouteIds
}

internal class SharedPreferencesNotificationRouteEnablement(
    context: Context,
    catalog: NotificationRouteCatalog,
) : NotificationRouteEnablement {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val state = NotificationRouteEnablementState(
        availableRouteIds = catalog.presentations().map { it.routeId }.toSet(),
        enabledRouteIds = preferences.getStringSet(ENABLED_ROUTE_IDS_KEY, emptySet()).orEmpty().toSet(),
    )

    override fun isEnabled(routeId: String): Boolean = state.isEnabled(routeId)

    @Synchronized
    override fun setEnabled(routeId: String, enabled: Boolean): Boolean {
        if (!state.setEnabled(routeId, enabled)) return false
        return persist(state.enabledRouteIds())
    }

    override fun enabledRouteIds(): Set<String> = state.enabledRouteIds()

    /**
     * Route changes are privacy-sensitive revocations, so they must complete their disk write
     * before callers can treat the action as successful. Future UI callers must invoke this off
     * the main thread and surface a false result for retry; do not replace it with `apply()`.
     */
    private fun persist(enabledRouteIds: Set<String>): Boolean =
        preferences.edit().putStringSet(ENABLED_ROUTE_IDS_KEY, enabledRouteIds).commit()

    private companion object {
        const val PREFERENCES_NAME = "bill.notification-route-enablement"
        const val ENABLED_ROUTE_IDS_KEY = "enabled-route-ids"
    }
}
