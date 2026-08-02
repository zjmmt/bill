package dev.bill.app.quickcapture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickCaptureOutcomeStoreInstrumentedTest {
    @Test
    fun outcomeIsDeliveredOnceAndExpiredWithoutFinancialPayload() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val saved = QuickCaptureOutcome.Saved(alreadyPresent = false)
        QuickCaptureOutcomeStore.record(context, saved, nowEpochMillis = 1_000L)

        assertEquals(
            saved,
            QuickCaptureOutcomeStore.consumeUnread(context, nowEpochMillis = 1_100L),
        )
        assertNull(QuickCaptureOutcomeStore.consumeUnread(context, nowEpochMillis = 1_200L))

        QuickCaptureOutcomeStore.record(
            context,
            QuickCaptureOutcome.Failed(QuickCaptureFailure.OCR_FAILED),
            nowEpochMillis = 2_000L,
        )
        assertNull(
            QuickCaptureOutcomeStore.consumeUnread(
                context,
                nowEpochMillis = 2_000L + 24L * 60L * 60L * 1_000L + 1L,
            ),
        )
    }
}
