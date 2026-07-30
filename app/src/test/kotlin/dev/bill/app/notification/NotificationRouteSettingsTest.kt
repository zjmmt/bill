package dev.bill.app.notification

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.VersionId
import dev.bill.source.genericnotification.NotificationRouteCatalog
import dev.bill.source.genericnotification.NotificationTemplate
import dev.bill.source.genericnotification.VerifiedNotificationRoute
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRouteSettingsTest {
    @Test
    fun `empty production-style catalog exposes no route or enable action`() = runTest {
        val enablement = FakeEnablement()
        val settings = NotificationRouteSettings(
            catalog = NotificationRouteCatalog.empty(),
            enablement = enablement,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertFalse(settings.state.value.hasVerifiedRoutes)
        assertEquals(emptyList<NotificationRouteSetting>(), settings.state.value.routes)

        assertFalse(settings.setEnabled("fixture-payment", true))
        assertFalse(settings.state.value.lastUpdateFailed)
        assertEquals(0, enablement.writeCalls)
    }

    @Test
    fun `verified route is shown by safe label and defaults disabled`() = runTest {
        val enablement = FakeEnablement()
        var callbackIds: Set<String>? = null
        val settings = NotificationRouteSettings(
            catalog = NotificationRouteCatalog(listOf(fixtureRoute())),
            enablement = enablement,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            onEnabledRoutesChanged = { callbackIds = it },
        )

        assertEquals(
            listOf(
                NotificationRouteSetting(
                    routeId = "fixture-payment",
                    safeLabel = "Fixture payment notification",
                    enabled = false,
                ),
            ),
            settings.state.value.routes,
        )
        assertFalse(settings.state.value.hasEnabledRoutes)

        assertTrue(settings.setEnabled("fixture-payment", true))

        assertTrue(settings.state.value.hasEnabledRoutes)
        assertFalse(settings.state.value.lastUpdateFailed)
        assertEquals(setOf("fixture-payment"), callbackIds)
    }

    @Test
    fun `failed enable remains closed and exposes retry state`() = runTest {
        val enablement = FakeEnablement(writeResults = ArrayDeque(listOf(false, true)))
        val settings = NotificationRouteSettings(
            catalog = NotificationRouteCatalog(listOf(fixtureRoute())),
            enablement = enablement,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertFalse(settings.setEnabled("fixture-payment", true))
        assertFalse(settings.state.value.hasEnabledRoutes)
        assertTrue(settings.state.value.lastUpdateFailed)

        assertTrue(settings.retryLastUpdate())
        assertTrue(settings.state.value.hasEnabledRoutes)
        assertFalse(settings.state.value.lastUpdateFailed)
    }

    @Test
    fun `failed disable stays closed and can be retried durably`() = runTest {
        val enablement = FakeEnablement(
            initialEnabledIds = setOf("fixture-payment"),
            writeResults = ArrayDeque(listOf(false, true)),
        )
        val settings = NotificationRouteSettings(
            catalog = NotificationRouteCatalog(listOf(fixtureRoute())),
            enablement = enablement,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertFalse(settings.setEnabled("fixture-payment", false))
        assertFalse(settings.state.value.hasEnabledRoutes)
        assertTrue(settings.state.value.lastUpdateFailed)

        assertTrue(settings.retryLastUpdate())
        assertFalse(settings.state.value.hasEnabledRoutes)
        assertFalse(settings.state.value.lastUpdateFailed)
    }

    @Test
    fun `failed privacy write blocks unrelated route changes until retry succeeds`() = runTest {
        val enablement = FakeEnablement(
            initialEnabledIds = setOf("fixture-payment"),
            writeResults = ArrayDeque(listOf(false, true)),
        )
        val settings = NotificationRouteSettings(
            catalog = NotificationRouteCatalog(
                listOf(
                    fixtureRoute(),
                    fixtureRoute(
                        routeId = "fixture-refund",
                        safeLabel = "Fixture refund notification",
                    ),
                ),
            ),
            enablement = enablement,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertFalse(settings.setEnabled("fixture-payment", false))
        assertFalse(settings.setEnabled("fixture-refund", true))
        assertEquals(1, enablement.writeCalls)
        assertFalse(settings.state.value.routes.single { it.routeId == "fixture-refund" }.enabled)
        assertTrue(settings.state.value.lastUpdateFailed)

        assertTrue(settings.retryLastUpdate())
        assertEquals(2, enablement.writeCalls)
        assertFalse(settings.state.value.lastUpdateFailed)
    }

    @Test
    fun `repeated request for current value is a no-op`() = runTest {
        val enablement = FakeEnablement()
        val settings = NotificationRouteSettings(
            catalog = NotificationRouteCatalog(listOf(fixtureRoute())),
            enablement = enablement,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(settings.setEnabled("fixture-payment", false))
        assertEquals(0, enablement.writeCalls)

        assertTrue(settings.setEnabled("fixture-payment", true))
        assertTrue(settings.setEnabled("fixture-payment", true))
        assertEquals(1, enablement.writeCalls)
        assertTrue(settings.state.value.hasEnabledRoutes)
    }

    private fun fixtureRoute(
        routeId: String = "fixture-payment",
        safeLabel: String = "Fixture payment notification",
    ) = VerifiedNotificationRoute(
        routeId = routeId,
        sourceIdentity = SourceIdentity(
            parserId = ParserId(routeId),
            providerId = ProviderId("fixture-provider"),
            sourceFamily = SourceFamily.BANK,
            connectorId = ConnectorId(routeId),
            capabilities = emptySet(),
            supportedCaptureMethods = setOf(CaptureMethod.NOTIFICATION),
            parserVersion = VersionId("parser-1"),
            ruleVersion = VersionId("rules-1"),
        ),
        template = NotificationTemplate(
            id = routeId,
            version = "v1",
            packageName = "fixture.payment",
            channelId = "transaction",
            category = "status",
            contentMatcher = { true },
        ),
        safeLabel = safeLabel,
    )

    private class FakeEnablement(
        initialEnabledIds: Set<String> = emptySet(),
        private val writeResults: ArrayDeque<Boolean> = ArrayDeque(),
    ) : NotificationRouteEnablement {
        private var enabledIds = initialEnabledIds
        var writeCalls: Int = 0
            private set

        override fun isEnabled(routeId: String): Boolean = routeId in enabledIds

        override fun setEnabled(routeId: String, enabled: Boolean): Boolean {
            writeCalls += 1
            val writeSucceeded = writeResults.removeFirstOrNull() ?: true
            if (!enabled) {
                enabledIds = enabledIds - routeId
            } else if (writeSucceeded) {
                enabledIds = enabledIds + routeId
            }
            return writeSucceeded
        }

        override fun enabledRouteIds(): Set<String> = enabledIds
    }
}
