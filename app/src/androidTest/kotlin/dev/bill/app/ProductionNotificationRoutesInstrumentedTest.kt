package dev.bill.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bill.app.notification.SharedPreferencesNotificationRouteEnablement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionNotificationRoutesInstrumentedTest {
    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearTestPreferences() {
        targetContext.deleteSharedPreferences(TEST_PREFERENCES_NAME)
    }

    @After
    fun cleanUpTestPreferences() {
        targetContext.deleteSharedPreferences(TEST_PREFERENCES_NAME)
    }

    @Test
    fun productionRoutesAreDefaultOffAndPersistOnlyOpaqueOptIns() {
        val catalog = ProductionNotificationRoutes.catalog
        val routeIds = catalog.presentations().map { it.routeId }.toSet()
        assertEquals(
            setOf(
                "alipay.notification.outbound-cny.v1",
                "alipay.notification.balance-receipt-cny.v1",
                "alipay.notification.fund-buy-confirmed-cny.v1",
                "wechat.notification.paid-en-cny.v1",
                "bank.cmb.notification.quick-refund-cny.v1",
            ),
            routeIds,
        )

        var enablement = newEnablement()
        assertTrue(enablement.enabledRouteIds().isEmpty())
        routeIds.forEach { routeId ->
            assertFalse(enablement.isEnabled(routeId))
        }

        routeIds.forEach { routeId ->
            assertTrue(enablement.setEnabled(routeId, true))
        }

        enablement = newEnablement()
        assertEquals(routeIds, enablement.enabledRouteIds())
        routeIds.forEach { routeId ->
            assertTrue(enablement.isEnabled(routeId))
            assertTrue(enablement.setEnabled(routeId, false))
        }

        assertTrue(newEnablement().enabledRouteIds().isEmpty())
    }

    private fun newEnablement() = SharedPreferencesNotificationRouteEnablement(
        context = targetContext,
        catalog = ProductionNotificationRoutes.catalog,
        preferencesName = TEST_PREFERENCES_NAME,
    )

    private companion object {
        const val TEST_PREFERENCES_NAME =
            "bill.production-notification-routes.instrumentation-test"
    }
}
