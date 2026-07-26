package dev.bill.source.genericphotoocr

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericPhotoOcrParserTest {
    private val parser = GenericPhotoOcrParser()

    @Test
    fun `synthetic payment transcript proposes editable expense fields`() {
        val result = parse(
            "支付成功",
            "¥ 12.34",
            "商户：测试便利店",
        )

        assertTrue(result is ParseResult.NeedsUserReview)
        result as ParseResult.NeedsUserReview
        assertEquals(1_234L, result.candidate?.amount?.value?.minorUnits)
        assertEquals(
            ObservedMoneyDirection.OUTBOUND,
            result.candidate?.moneyDirection?.value,
        )
        assertEquals("测试便利店", result.candidate?.counterparty?.value)
        assertEquals(DiagnosticCode.INSUFFICIENT_FIELDS, result.diagnostic.code)
        assertTrue(result.diagnostic.recoverable)
    }

    @Test
    fun `synthetic receipt transcript proposes editable income fields`() {
        val result = parse(
            "收款成功",
            "人民币￥88元",
            "付款方：示例用户",
        ) as ParseResult.NeedsUserReview

        assertEquals(8_800L, result.candidate?.amount?.value?.minorUnits)
        assertEquals(
            ObservedMoneyDirection.INBOUND,
            result.candidate?.moneyDirection?.value,
        )
        assertEquals("示例用户", result.candidate?.counterparty?.value)
    }

    @Test
    fun `same amount repeated is not treated as an ambiguity`() {
        val result = parse(
            "付款成功",
            "金额 CNY 9.90",
            "实付 9.90元",
        ) as ParseResult.NeedsUserReview

        assertEquals(990L, result.candidate?.amount?.value?.minorUnits)
    }

    @Test
    fun `different currency amounts do not guess one`() {
        val result = parse(
            "支付成功",
            "订单金额 ￥18.00",
            "优惠后实付 ￥15.00",
        ) as ParseResult.NeedsUserReview

        assertNull(result.candidate?.amount)
        assertEquals(
            ObservedMoneyDirection.OUTBOUND,
            result.candidate?.moneyDirection?.value,
        )
    }

    @Test
    fun `refund transfer and red packet semantics never become ordinary direction`() {
        listOf("退款成功", "转账已收款", "红包已收款").forEach { semanticLine ->
            val result = parse(semanticLine, "￥20.00") as ParseResult.NeedsUserReview

            assertEquals(2_000L, result.candidate?.amount?.value?.minorUnits)
            assertNull(result.candidate?.moneyDirection)
        }
    }

    @Test
    fun `bare numbers are not treated as money`() {
        val result = parse(
            "支付成功",
            "订单号 202607261234",
        ) as ParseResult.NeedsUserReview

        assertNull(result.candidate?.amount)
        assertEquals(
            ObservedMoneyDirection.OUTBOUND,
            result.candidate?.moneyDirection?.value,
        )
    }

    @Test
    fun `empty OCR still produces no evidence envelope`() {
        assertNull(OcrTranscript.encode(emptyList()))
        assertNull(OcrTranscript.encode(listOf(" ", "\t")))
    }

    @Test
    fun `malformed envelope and invalid UTF-8 are rejected`() {
        val wrongHeader = parser.parse(
            rawEvent(),
            EvidenceInput(OcrTranscript.MEDIA_TYPE, "wrong\ntext".toByteArray()),
        )
        val malformedUtf8 = parser.parse(
            rawEvent(),
            EvidenceInput(
                OcrTranscript.MEDIA_TYPE,
                byteArrayOf(0xc3.toByte(), 0x28),
            ),
        )

        assertRejected(wrongHeader, DiagnosticCode.MALFORMED_EVIDENCE)
        assertRejected(malformedUtf8, DiagnosticCode.MALFORMED_EVIDENCE)
    }

    @Test
    fun `line and total evidence limits are closed`() {
        assertNull(OcrTranscript.encode(List(OcrTranscript.MAX_LINES + 1) { "line" }))
        assertNull(OcrTranscript.encode(listOf("x".repeat(OcrTranscript.MAX_LINE_CHARS + 1))))
    }

    @Test
    fun `wrong source or media type is rejected`() {
        val evidence = OcrTranscript.encode(listOf("支付成功", "￥1.00"))!!
        val wrongSource = parser.parse(
            rawEvent().copy(sourceFamily = SourceFamily.ALIPAY),
            EvidenceInput(OcrTranscript.MEDIA_TYPE, evidence),
        )
        val wrongMedia = parser.parse(
            rawEvent(),
            EvidenceInput("text/plain", evidence),
        )

        assertRejected(wrongSource, DiagnosticCode.SOURCE_NOT_ACCEPTED)
        assertRejected(wrongMedia, DiagnosticCode.UNSUPPORTED_MEDIA_TYPE)
    }

    @Test
    fun `result string does not expose OCR transcript`() {
        val marker = "sensitive-ocr-marker"
        val result = parse("支付成功", marker, "￥1.00")

        assertFalse(result.toString().contains(marker))
    }

    private fun parse(vararg lines: String): ParseResult {
        val evidence = OcrTranscript.encode(lines.toList())!!
        return parser.parse(
            rawEvent(evidence),
            EvidenceInput(OcrTranscript.MEDIA_TYPE, evidence),
        ).also {
            evidence.fill(0)
        }
    }

    private fun rawEvent(evidence: ByteArray = "fixture".toByteArray()) = RawEvent(
        id = RawEventId("event-ocr-1"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = parser.identity.connectorId,
        captureMethod = CaptureMethod.PHOTO_OCR,
        captureScope = CaptureScopeId("local-user"),
        contentHash = EvidenceHash.fromBytes(evidence),
        capturedAt = Instant.parse("2026-07-26T12:00:00Z"),
        payloadId = PayloadId("payload-ocr-1"),
    )

    private fun assertRejected(result: ParseResult, code: DiagnosticCode) {
        assertTrue(result is ParseResult.Rejected)
        result as ParseResult.Rejected
        assertEquals(code, result.diagnostic.code)
        assertFalse(result.diagnostic.recoverable)
    }
}
