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
import org.junit.Assert.assertNull
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

    @Test
    fun bundledEngineRecognizesSimplifiedWechatStatusPages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = listOf(
            SimplifiedStatusFixture(
                title = "微信红包",
                amount = "0.01元",
                status = "领取成功，已存入余额",
                expectedAmountMinorUnits = 1L,
            ),
            SimplifiedStatusFixture(
                title = "微信红包",
                amount = "￥0.02",
                status = "红包尚未领取",
                expectedAmountMinorUnits = null,
            ),
            SimplifiedStatusFixture(
                title = "微信提现",
                amount = "￥0.03",
                status = "提现处理中",
                expectedAmountMinorUnits = null,
            ),
            SimplifiedStatusFixture(
                title = "微信转账",
                amount = "+0.04",
                status = "转账成功",
                expectedAmountMinorUnits = 4L,
            ),
        )

        fixtures.forEachIndexed { index, fixture ->
            val bitmap = simplifiedStatusFixture(fixture)
            try {
                val recognized = BundledLocalOcrEngine.recognize(context, bitmap)
                assertTrue(recognized is LocalOcrResult.Lines)
                val lines = (recognized as LocalOcrResult.Lines).values
                assertTrue(lines.any { fixture.status in it.value })

                val evidence = requireNotNull(OcrTranscript.encodeSpatial(lines))
                try {
                    val parsed = GenericPhotoOcrParser().parse(
                        rawEvent(evidence).copy(
                            id = RawEventId("simplified-status-$index"),
                            payloadId = PayloadId("simplified-status-$index-payload"),
                        ),
                        EvidenceInput(OcrTranscript.MEDIA_TYPE, evidence),
                    ) as ParseResult.NeedsUserReview
                    assertEquals(
                        fixture.expectedAmountMinorUnits,
                        parsed.candidate?.amount?.value?.minorUnits,
                    )
                    assertNull(parsed.candidate?.moneyDirection)
                } finally {
                    evidence.fill(0)
                }
            } finally {
                bitmap.recycle()
            }
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

    private fun simplifiedStatusFixture(fixture: SimplifiedStatusFixture): Bitmap {
        val bitmap = Bitmap.createBitmap(1_200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        paint.textSize = 64f
        canvas.drawText(fixture.title, 80f, 150f, paint)
        paint.textSize = 144f
        canvas.drawText(fixture.amount, 330f, 430f, paint)
        paint.textSize = 68f
        canvas.drawText(fixture.status, 160f, 650f, paint)
        return bitmap
    }

    private data class SimplifiedStatusFixture(
        val title: String,
        val amount: String,
        val status: String,
        val expectedAmountMinorUnits: Long?,
    )

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
