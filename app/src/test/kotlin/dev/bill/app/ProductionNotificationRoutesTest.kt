package dev.bill.app

import dev.bill.source.contract.SourceFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionNotificationRoutesTest {
    @Test
    fun `production catalog exposes four safe-labelled provider parsers`() {
        val presentations = ProductionNotificationRoutes.catalog.presentations()
        val parsers = ProductionNotificationRoutes.catalog.parsers()

        assertEquals(4, presentations.size)
        assertEquals(4, presentations.map { it.routeId }.toSet().size)
        assertEquals(4, parsers.map { it.identity }.toSet().size)
        assertEquals(
            setOf(SourceFamily.ALIPAY, SourceFamily.WECHAT, SourceFamily.BANK),
            parsers.map { it.identity.sourceFamily }.toSet(),
        )
        assertTrue(presentations.all { "实验性" in it.safeLabel })
        assertFalse(
            presentations.any { presentation ->
                presentation.safeLabel.contains("com.") || presentation.safeLabel.contains("cmb.pb")
            },
        )
    }
}
