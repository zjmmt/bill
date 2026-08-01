package dev.bill.app

import dev.bill.source.contract.SourceFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionNotificationRoutesTest {
    @Test
    fun `production catalog exposes five safe-labelled provider parsers`() {
        val presentations = ProductionNotificationRoutes.catalog.presentations()
        val parsers = ProductionNotificationRoutes.catalog.parsers()

        assertEquals(5, presentations.size)
        assertEquals(5, presentations.map { it.routeId }.toSet().size)
        assertEquals(5, parsers.map { it.identity }.toSet().size)
        assertEquals(3, parsers.count { it.identity.sourceFamily == SourceFamily.ALIPAY })
        assertEquals(
            setOf(SourceFamily.ALIPAY, SourceFamily.WECHAT, SourceFamily.BANK),
            parsers.map { it.identity.sourceFamily }.toSet(),
        )
        assertTrue(presentations.all { notificationRouteLabelResourceId(it.routeId) != null })
        assertTrue(presentations.all { " · " in it.safeLabel })
        assertFalse(presentations.any { "实验性" in it.safeLabel })
        assertFalse(
            presentations.any { presentation ->
                presentation.safeLabel.contains("com.") || presentation.safeLabel.contains("cmb.pb")
            },
        )
    }
}
