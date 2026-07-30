package dev.bill.app

import android.content.Intent
import dev.bill.app.quickcapture.BillQuickCaptureTileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivityRoutingTest {
    @Test
    fun `quick capture setup action opens settings`() {
        assertEquals(
            AppDestination.SETTINGS,
            destinationForIntentAction(
                BillQuickCaptureTileService.ACTION_OPEN_QUICK_CAPTURE_SETUP,
            ),
        )
    }

    @Test
    fun `ordinary and share actions do not override navigation`() {
        assertNull(destinationForIntentAction(null))
        assertNull(destinationForIntentAction(Intent.ACTION_MAIN))
        assertNull(destinationForIntentAction(Intent.ACTION_SEND))
    }
}
