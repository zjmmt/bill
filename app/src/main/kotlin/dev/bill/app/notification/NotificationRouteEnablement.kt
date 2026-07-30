package dev.bill.app.notification

import android.content.Context
import dev.bill.source.genericnotification.NotificationRouteCatalog
import kotlinx.coroutines.CancellationException

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

    /**
     * Applies a privacy-sensitive route change with fail-closed runtime semantics.
     *
     * Enabling is published only after durable persistence succeeds. Disabling closes the runtime
     * gate first and keeps it closed even when persistence fails, so a failed revocation never
     * leaves this process reading notification content.
     */
    @Synchronized
    fun setEnabled(
        routeId: String,
        enabled: Boolean,
        persist: (Set<String>) -> Boolean = { true },
    ): Boolean {
        if (routeId !in availableRouteIds) return false
        val updatedRouteIds = if (enabled) {
            enabledRouteIds + routeId
        } else {
            enabledRouteIds - routeId
        }
        if (enabled) {
            if (!persistSafely(persist, updatedRouteIds)) return false
            enabledRouteIds = updatedRouteIds
            return true
        }

        enabledRouteIds = updatedRouteIds
        return persistSafely(persist, updatedRouteIds)
    }

    fun enabledRouteIds(): Set<String> = enabledRouteIds.toSet()

    private fun persistSafely(
        persist: (Set<String>) -> Boolean,
        enabledRouteIds: Set<String>,
    ): Boolean = try {
        persist(enabledRouteIds)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        false
    }
}

internal class SharedPreferencesNotificationRouteEnablement(
    context: Context,
    catalog: NotificationRouteCatalog,
    preferencesName: String = PREFERENCES_NAME,
) : NotificationRouteEnablement {
    private val preferences = (context.applicationContext ?: context).getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )
    private val state = NotificationRouteEnablementState(
        availableRouteIds = catalog.presentations().map { it.routeId }.toSet(),
        enabledRouteIds = readEnabledRouteIdsSafely(),
    )

    override fun isEnabled(routeId: String): Boolean = state.isEnabled(routeId)

    @Synchronized
    override fun setEnabled(routeId: String, enabled: Boolean): Boolean {
        return state.setEnabled(
            routeId = routeId,
            enabled = enabled,
            persist = ::persist,
        )
    }

    override fun enabledRouteIds(): Set<String> = state.enabledRouteIds()

    /**
     * Route changes are privacy-sensitive revocations, so they must complete their disk write
     * before callers can treat the action as successful. Future UI callers must invoke this off
     * the main thread and surface a false result for retry; do not replace it with `apply()`.
     */
    private fun persist(enabledRouteIds: Set<String>): Boolean =
        preferences.edit().putStringSet(ENABLED_ROUTE_IDS_KEY, enabledRouteIds).commit()

    /** Malformed or incompatible legacy preferences must never open a notification route. */
    private fun readEnabledRouteIdsSafely(): Set<String> = try {
        preferences.getStringSet(ENABLED_ROUTE_IDS_KEY, emptySet()).orEmpty().toSet()
    } catch (_: RuntimeException) {
        emptySet()
    }

    private companion object {
        const val PREFERENCES_NAME = "bill.notification-route-enablement"
        const val ENABLED_ROUTE_IDS_KEY = "enabled-route-ids"
    }
}
