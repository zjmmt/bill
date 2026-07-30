package dev.bill.app.notification

import dev.bill.source.genericnotification.NotificationRouteCatalog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class NotificationRouteSetting(
    val routeId: String,
    val safeLabel: String,
    val enabled: Boolean,
)

internal data class NotificationRouteUpdateRetry(
    val routeId: String,
    val desiredEnabled: Boolean,
)

internal data class NotificationRouteSettingsSnapshot(
    val routes: List<NotificationRouteSetting>,
    val updatingRouteId: String? = null,
    val retryUpdate: NotificationRouteUpdateRetry? = null,
) {
    val hasVerifiedRoutes: Boolean
        get() = routes.isNotEmpty()

    val hasEnabledRoutes: Boolean
        get() = routes.any(NotificationRouteSetting::enabled)

    val lastUpdateFailed: Boolean
        get() = retryUpdate != null
}

/**
 * User-facing control plane for the static, verified notification route catalog.
 *
 * Only opaque route ids and reviewed safe labels cross this boundary. Preference commits run on a
 * serial background path; once a command owns the mutex, cancellation cannot strand the UI between
 * a completed privacy-sensitive write and its in-memory projection.
 */
internal class NotificationRouteSettings(
    catalog: NotificationRouteCatalog,
    private val enablement: NotificationRouteEnablement,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onEnabledRoutesChanged: (Set<String>) -> Unit = {},
) {
    private val presentations = catalog.presentations()
    private val presentationIds = presentations.map { it.routeId }.toSet()
    private val updateMutex = Mutex()
    private val mutableState = MutableStateFlow(snapshot())

    val state: StateFlow<NotificationRouteSettingsSnapshot> = mutableState.asStateFlow()

    suspend fun setEnabled(routeId: String, enabled: Boolean): Boolean = updateMutex.withLock {
        if (state.value.retryUpdate != null) return@withLock false
        val current = state.value.routes.firstOrNull { route -> route.routeId == routeId }
            ?: return@withLock false
        if (current.enabled == enabled) return@withLock true
        applyUpdateLocked(routeId, enabled)
    }

    suspend fun retryLastUpdate(): Boolean = updateMutex.withLock {
        val retry = state.value.retryUpdate ?: return@withLock false
        applyUpdateLocked(retry.routeId, retry.desiredEnabled)
    }

    private suspend fun applyUpdateLocked(routeId: String, enabled: Boolean): Boolean =
        withContext(NonCancellable) {
            if (routeId !in presentationIds) return@withContext false

            mutableState.value = snapshot(
                updatingRouteId = routeId,
            )
            val persisted = withContext(ioDispatcher) {
                enablement.setEnabled(routeId, enabled)
            }
            val enabledRouteIds = enablement.enabledRouteIds()
            mutableState.value = snapshot(
                enabledRouteIds = enabledRouteIds,
                retryUpdate = if (persisted) {
                    null
                } else {
                    NotificationRouteUpdateRetry(routeId, enabled)
                },
            )
            onEnabledRoutesChanged(enabledRouteIds)
            persisted
        }

    private fun snapshot(
        enabledRouteIds: Set<String> = enablement.enabledRouteIds(),
        updatingRouteId: String? = null,
        retryUpdate: NotificationRouteUpdateRetry? = null,
    ) = NotificationRouteSettingsSnapshot(
        routes = presentations.map { presentation ->
            NotificationRouteSetting(
                routeId = presentation.routeId,
                safeLabel = presentation.safeLabel,
                enabled = presentation.routeId in enabledRouteIds,
            )
        },
        updatingRouteId = updatingRouteId,
        retryUpdate = retryUpdate,
    )
}
