package dev.bill.app.quickcapture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.genericphotoocr.GenericPhotoOcrParser
import dev.bill.source.genericphotoocr.OcrTranscript
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpatialOcrPipelineInstrumentedTest {
    @Test
    fun bundledEngineCarriesLayoutIntoDominantPaymentAmountProposal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = paymentFixture()
        try {
            val recognized = BundledLocalOcrEngine.recognize(context, bitmap)
            assertTrue(recognized is LocalOcrResult.Lines)
            val lines = (recognized as LocalOcrResult.Lines).values
            assertTrue(lines.size >= 3)
            assertTrue(lines.all { it.bounds != null })
            assertTrue(lines.any { "3.17" in it.value })
            assertTrue(lines.any { "3.50" in it.value || "0.33" in it.value })

            val evidence = requireNotNull(OcrTranscript.encodeSpatial(lines))
            try {
                val decoded = requireNotNull(OcrTranscript.decode(evidence))
                val primary = decoded.lines.first { "3.17" in it.value }
                val secondary = decoded.lines.first {
                    "3.50" in it.value || "0.33" in it.value
                }
                assertTrue(requireNotNull(primary.bounds).height > requireNotNull(secondary.bounds).height)

                val result = GenericPhotoOcrParser().parse(
                    rawEvent(evidence),
                    EvidenceInput(OcrTranscript.MEDIA_TYPE, evidence),
                ) as ParseResult.NeedsUserReview
                assertEquals(317L, result.candidate?.amount?.value?.minorUnits)
                assertEquals(
                    ObservedMoneyDirection.OUTBOUND,
                    result.candidate?.moneyDirection?.value,
                )
            } finally {
                evidence.fill(0)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun paymentFixture(): Bitmap {
        val bitmap = Bitmap.createBitmap(1_200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        paint.textSize = 56f
        canvas.drawText("Payment successful", 80f, 130f, paint)
        paint.textSize = 128f
        canvas.drawText("¥3.17", 340f, 360f, paint)
        paint.textSize = 48f
        canvas.drawText("商品金额 ¥3.50", 80f, 560f, paint)
        canvas.drawText("优惠 - ¥0.33", 80f, 690f, paint)
        return bitmap
    }

    private fun rawEvent(evidence: ByteArray) = RawEvent(
        id = RawEventId("spatial-ocr-device-fixture"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = GenericPhotoOcrParser().identity.connectorId,
        captureMethod = CaptureMethod.PHOTO_OCR,
        captureScope = CaptureScopeId("instrumentation-fixture"),
        contentHash = EvidenceHash.fromBytes(evidence),
        capturedAt = Instant.parse("2026-07-31T12:00:00Z"),
        payloadId = PayloadId("spatial-ocr-device-fixture-payload"),
        payloadSizeBytes = evidence.size.toLong(),
    )
}
