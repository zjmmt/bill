package dev.bill.app.notification

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesNotificationRouteEnablementTest {
    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearPreferences() {
        targetContext.deleteSharedPreferences(TEST_PREFERENCES_NAME)
    }

    @After
    fun cleanUpPreferences() {
        targetContext.deleteSharedPreferences(TEST_PREFERENCES_NAME)
    }

    @Test
    fun routeChangesSurviveFreshEnablementInstancesWithoutPersistingMetadata() {
        val catalog = NotificationRouteCatalog(listOf(fixtureRoute()))
        val first = enablement(catalog)

        assertFalse(first.isEnabled(FIXTURE_ROUTE_ID))
        assertTrue(first.setEnabled(FIXTURE_ROUTE_ID, true))

        val afterEnable = enablement(catalog)
        assertTrue(afterEnable.isEnabled(FIXTURE_ROUTE_ID))
        assertEquals(setOf(FIXTURE_ROUTE_ID), persistedRouteIds())

        assertTrue(afterEnable.setEnabled(FIXTURE_ROUTE_ID, false))

        val afterDisable = enablement(catalog)
        assertFalse(afterDisable.isEnabled(FIXTURE_ROUTE_ID))
        assertEquals(emptySet<String>(), persistedRouteIds())
    }

    @Test
    fun stalePersistedRouteCannotOpenAnUnknownRuntimeRoute() {
        assertTrue(
            preferences().edit()
                .putStringSet(ENABLED_ROUTE_IDS_KEY, setOf("fixture-removed"))
                .commit(),
        )

        val enablement = enablement(NotificationRouteCatalog(listOf(fixtureRoute())))

        assertEquals(emptySet<String>(), enablement.enabledRouteIds())
        assertFalse(enablement.isEnabled("fixture-removed"))
    }

    @Test
    fun malformedPersistedTypeFailsClosed() {
        assertTrue(
            preferences().edit()
                .putString(ENABLED_ROUTE_IDS_KEY, FIXTURE_ROUTE_ID)
                .commit(),
        )

        val enablement = enablement(NotificationRouteCatalog(listOf(fixtureRoute())))

        assertEquals(emptySet<String>(), enablement.enabledRouteIds())
        assertFalse(enablement.isEnabled(FIXTURE_ROUTE_ID))
    }

    private fun persistedRouteIds(): Set<String> =
        preferences().getStringSet(ENABLED_ROUTE_IDS_KEY, emptySet()).orEmpty().toSet()

    private fun enablement(catalog: NotificationRouteCatalog) =
        SharedPreferencesNotificationRouteEnablement(
            context = targetContext,
            catalog = catalog,
            preferencesName = TEST_PREFERENCES_NAME,
        )

    private fun preferences() = targetContext.getSharedPreferences(
        TEST_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private fun fixtureRoute() = VerifiedNotificationRoute(
        routeId = FIXTURE_ROUTE_ID,
        sourceIdentity = SourceIdentity(
            parserId = ParserId(FIXTURE_ROUTE_ID),
            providerId = ProviderId("fixture-provider"),
            sourceFamily = SourceFamily.BANK,
            connectorId = ConnectorId(FIXTURE_ROUTE_ID),
            capabilities = emptySet(),
            supportedCaptureMethods = setOf(CaptureMethod.NOTIFICATION),
            parserVersion = VersionId("parser-1"),
            ruleVersion = VersionId("rules-1"),
        ),
        template = NotificationTemplate(
            id = FIXTURE_ROUTE_ID,
            version = "v1",
            packageName = "fixture.payment",
            channelId = "transaction",
            category = "status",
            contentMatcher = { true },
        ),
        safeLabel = "Fixture payment notification",
    )

    private companion object {
        const val TEST_PREFERENCES_NAME = "bill.notification-route-enablement.instrumentation-test"
        const val ENABLED_ROUTE_IDS_KEY = "enabled-route-ids"
        const val FIXTURE_ROUTE_ID = "fixture-payment"
    }
}
