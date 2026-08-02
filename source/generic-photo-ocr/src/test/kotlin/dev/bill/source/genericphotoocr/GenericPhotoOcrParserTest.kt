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
    fun `spatial transcript selects only a clearly dominant standalone amount`() {
        val result = parseSpatial(
            spatial("Payment successful", 900, 1_200),
            spatial("￥3.17", 1_500, 2_400),
            spatial("商品金额 ￥3.50", 2_700, 2_950),
            spatial("- ￥0.33", 3_100, 3_350),
        ) as ParseResult.NeedsUserReview

        assertEquals(317L, result.candidate?.amount?.value?.minorUnits)
        assertEquals(
            ObservedMoneyDirection.OUTBOUND,
            result.candidate?.moneyDirection?.value,
        )
    }

    @Test
    fun `spatial transcript rejects equally prominent competing amounts`() {
        val result = parseSpatial(
            spatial("支付成功", 800, 1_100),
            spatial("￥18.00", 1_400, 2_000),
            spatial("￥15.00", 2_200, 2_760),
        ) as ParseResult.NeedsUserReview

        assertNull(result.candidate?.amount)
    }

    @Test
    fun `signed hero amount can outrank a smaller labelled balance`() {
        val result = parseSpatial(
            spatial("交易成功", 800, 1_100),
            spatial("+0.01", 1_400, 2_300),
            spatial("余额 ￥211.04", 2_600, 2_850),
        ) as ParseResult.NeedsUserReview

        assertEquals(1L, result.candidate?.amount?.value?.minorUnits)
        assertNull(result.candidate?.moneyDirection)
    }

    @Test
    fun `legacy text evidence remains readable but cannot use spatial tie breaking`() {
        val evidence = OcrTranscript.encodeLegacy(
            listOf("支付成功", "￥18.00", "优惠后实付 ￥15.00"),
        )!!
        val result = parser.parse(
            rawEvent(evidence),
            EvidenceInput(OcrTranscript.LEGACY_MEDIA_TYPE, evidence),
        ) as ParseResult.NeedsUserReview
        evidence.fill(0)

        assertNull(result.candidate?.amount)
        assertEquals(
            ObservedMoneyDirection.OUTBOUND,
            result.candidate?.moneyDirection?.value,
        )
    }

    @Test
    fun `transcript header must agree with its media type`() {
        val evidence = OcrTranscript.encode(listOf("￥1.00"))!!
        val result = parser.parse(
            rawEvent(evidence),
            EvidenceInput(OcrTranscript.LEGACY_MEDIA_TYPE, evidence),
        )
        evidence.fill(0)

        assertRejected(result, DiagnosticCode.MALFORMED_EVIDENCE)
    }

    @Test
    fun `invalid spatial bounds are rejected`() {
        val malformed = "BILL-OCR/2\n500,500,400,600\t￥1.00".toByteArray()
        val result = parser.parse(
            rawEvent(malformed),
            EvidenceInput(OcrTranscript.MEDIA_TYPE, malformed),
        )
        malformed.fill(0)

        assertRejected(result, DiagnosticCode.MALFORMED_EVIDENCE)
    }

    @Test
    fun `refund transfer and red packet semantics never become ordinary direction`() {
        listOf(
            "退款成功",
            "转账已收款",
            "红包已收款",
            "Refund completed",
            "RED PACKET received",
        ).forEach { semanticLine ->
            val result = parse(semanticLine, "￥20.00") as ParseResult.NeedsUserReview

            assertEquals(2_000L, result.candidate?.amount?.value?.minorUnits)
            assertNull(result.candidate?.moneyDirection)
        }
    }

    @Test
    fun `completed transfer and red packet pages retain amount without ordinary direction`() {
        listOf(
            listOf("Red Packet transferred to Wallet", "0.01 CNY"),
            listOf("红包", "0.01元", "领取成功，已存入余额"),
            listOf("转账", "+0.01", "交易成功"),
        ).forEach { lines ->
            val result = parseSpatial(
                spatial(lines.first(), 700, 1_000),
                spatial(lines[1], 1_300, 2_200),
                *lines.drop(2).mapIndexed { index, value ->
                    spatial(value, 2_500 + index * 350, 2_750 + index * 350)
                }.toTypedArray(),
            ) as ParseResult.NeedsUserReview

            assertEquals(1L, result.candidate?.amount?.value?.minorUnits)
            assertNull(result.candidate?.moneyDirection)
        }
    }

    @Test
    fun `completed spatial pages recover a prominent decimal when OCR splits or drops currency`() {
        listOf(
            listOf("Red Packet transferred to Wallet", "0.01", "CNY"),
            listOf("紅包", "0.01", "領取成功，已存入餘額"),
        ).forEach { lines ->
            val result = parseSpatial(
                spatial(lines[0], 900, 1_150),
                spatial(lines[1], 2_000, 3_000),
                spatial(lines[2], 3_200, 3_450),
            ) as ParseResult.NeedsUserReview

            assertEquals(1L, result.candidate?.amount?.value?.minorUnits)
            assertNull(result.candidate?.moneyDirection)
        }

        val contextless = parseSpatial(
            spatial("0.01", 2_000, 3_000),
            spatial("Details", 3_200, 3_450),
        ) as ParseResult.NeedsUserReview
        assertNull(contextless.candidate?.amount)
    }

    @Test
    fun `selected signed amount supplies direction without scanning unrelated signs`() {
        listOf(
            Triple("- ￥13.70", 1_370L, ObservedMoneyDirection.OUTBOUND),
            Triple("+0.01", 1L, ObservedMoneyDirection.INBOUND),
        ).forEach { (amountLine, expectedAmount, expectedDirection) ->
            val result = parseSpatial(
                spatial("交易详情", 800, 1_050),
                spatial(amountLine, 1_500, 2_300),
                spatial("优惠 - ￥2.00", 3_000, 3_200),
            ) as ParseResult.NeedsUserReview

            assertEquals(expectedAmount, result.candidate?.amount?.value?.minorUnits)
            assertEquals(expectedDirection, result.candidate?.moneyDirection?.value)
        }
    }

    @Test
    fun `promotional red packet does not block an otherwise completed payment`() {
        val result = parseSpatial(
            spatial("支付成功", 700, 950),
            spatial("￥3.17", 1_300, 2_200),
            spatial("恭喜获得无门槛红包+200能量", 3_000, 3_250),
            spatial("优惠 - ￥0.33", 3_600, 3_850),
        ) as ParseResult.NeedsUserReview

        assertEquals(317L, result.candidate?.amount?.value?.minorUnits)
        assertEquals(
            ObservedMoneyDirection.OUTBOUND,
            result.candidate?.moneyDirection?.value,
        )
    }

    @Test
    fun `unopened red packet pages never propose their displayed amount`() {
        listOf(
            "Red packet of ￥0.01 not yet opened",
            "Red Packets not opened within 24 hrs will be refunded",
            "0.01元红包尚未领取",
            "紅包未打開",
            "0.01 CNY 未受取",
        ).forEach { statusLine ->
            val result = parseSpatial(
                spatial("Red Packet", 700, 1_000),
                spatial("￥0.01", 1_300, 2_200),
                spatial(statusLine, 2_500, 2_800),
            ) as ParseResult.NeedsUserReview

            assertNull(result.candidate?.amount)
            assertNull(result.candidate?.moneyDirection)
        }
    }

    @Test
    fun `processing withdrawal pages fail closed even when future completion text is visible`() {
        listOf(
            "Bank is processing",
            "Processing",
            "PENDING...",
            "Estimated to arrive by 22:38",
            "Request Withdrawal",
            "提现处理中",
            "预计到账时间 22:38",
            "預計到帳",
            "手続き中",
        ).forEach { pendingLine ->
            val result = parseSpatial(
                spatial("Withdraw Balance", 600, 900),
                spatial(pendingLine, 1_000, 1_350),
                spatial("Withdrawal completed", 1_600, 1_900),
                spatial("￥0.01", 2_200, 3_100),
            ) as ParseResult.NeedsUserReview

            assertNull(result.candidate?.amount)
            assertNull(result.candidate?.moneyDirection)
        }
    }

    @Test
    fun `completed withdrawal keeps amount after historical processing step`() {
        listOf(
            "银行告知已到账",
            "銀行告知已到賬",
            "銀行告知已到帳",
        ).forEach { completedLine ->
            val result = parseSpatial(
                spatial("零钱提现-到建设银行(7495)", 1_100, 1_350),
                spatial("0.01", 1_600, 2_350),
                spatial("银行处理中", 3_700, 3_950),
                spatial(completedLine, 4_600, 4_900),
                spatial("提现金额 ¥0.01", 6_100, 6_350),
                spatial("服务费 ¥0.00", 6_600, 6_850),
            ) as ParseResult.NeedsUserReview

            assertEquals(1L, result.candidate?.amount?.value?.minorUnits)
            assertNull(result.candidate?.moneyDirection)
        }
    }

    @Test
    fun `later non-posting status is not overridden by earlier completion text`() {
        val result = parseSpatial(
            spatial("银行告知已到账", 1_000, 1_250),
            spatial("提现处理中", 2_000, 2_250),
            spatial("¥0.01", 2_700, 3_500),
        ) as ParseResult.NeedsUserReview

        assertNull(result.candidate?.amount)
        assertNull(result.candidate?.moneyDirection)
    }

    @Test
    fun `status words inside ordinary completed text do not suppress payment`() {
        val result = parse(
            "Payment successful",
            "￥8.00",
            "Processing fee waived",
            "Pending Coffee membership",
        ) as ParseResult.NeedsUserReview

        assertEquals(800L, result.candidate?.amount?.value?.minorUnits)
        assertEquals(
            ObservedMoneyDirection.OUTBOUND,
            result.candidate?.moneyDirection?.value,
        )
    }

    @Test
    fun `provider display name never gates a fixed completed payment amount`() {
        listOf(
            "Weixin Pay" to "Payment successful",
            "微信支付" to "支付成功",
            "微信支付" to "付款成功",
            "支付寶" to "交易成功",
        ).forEach { (displayName, completedStatus) ->
            val result = parseSpatial(
                spatial(displayName, 500, 750),
                spatial(completedStatus, 900, 1_150),
                spatial("￥6.66", 1_500, 2_400),
            ) as ParseResult.NeedsUserReview

            assertEquals(666L, result.candidate?.amount?.value?.minorUnits)
        }
    }

    @Test
    fun `simplified and traditional Chinese status variants keep the same semantics`() {
        listOf("入账成功", "入賬成功", "到账", "到賬", "到帳").forEach { status ->
            val result = parse(status, "￥8.00") as ParseResult.NeedsUserReview
            assertEquals(800L, result.candidate?.amount?.value?.minorUnits)
            assertEquals(
                ObservedMoneyDirection.INBOUND,
                result.candidate?.moneyDirection?.value,
            )
        }

        listOf(
            "支付失败",
            "支付失敗",
            "付款失败",
            "付款失敗",
            "交易失败",
            "交易失敗",
            "正在处理",
            "正在處理",
        ).forEach { status ->
            val result = parseSpatial(
                spatial(status, 800, 1_100),
                spatial("￥8.00", 1_500, 2_400),
            ) as ParseResult.NeedsUserReview
            assertNull(result.candidate?.amount)
            assertNull(result.candidate?.moneyDirection)
        }

        listOf("提现", "提現", "还款", "還款", "零钱通", "零錢通").forEach { semantic ->
            val result = parseSpatial(
                spatial("支付成功", 700, 950),
                spatial("￥8.00", 1_300, 2_200),
                spatial(semantic, 2_500, 2_800),
            ) as ParseResult.NeedsUserReview
            assertEquals(800L, result.candidate?.amount?.value?.minorUnits)
            assertNull(result.candidate?.moneyDirection)
        }

        listOf("商户：咖啡店", "商戶：咖啡店", "对方：咖啡店", "對方：咖啡店").forEach { line ->
            val result = parse("支付成功", "￥8.00", line) as ParseResult.NeedsUserReview
            assertEquals("咖啡店", result.candidate?.counterparty?.value)
        }
    }

    @Test
    fun `failed rejected and cancelled pages never propose an amount`() {
        listOf(
            "支付失败",
            "REJECTED",
            "Payment failed",
            "支払い失敗",
            "キャンセル",
        ).forEach { statusLine ->
            val result = parse(statusLine, "￥20.00") as ParseResult.NeedsUserReview

            assertNull(result.candidate?.amount)
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
    fun `ordinary encoder still rejects empty input`() {
        assertNull(OcrTranscript.encode(emptyList()))
        assertNull(OcrTranscript.encode(listOf(" ", "\t")))
    }

    @Test
    fun `explicit empty OCR evidence opens a blank manual review`() {
        val evidence = OcrTranscript.encodeEmpty()

        val decoded = OcrTranscript.decode(evidence)
        val result = parser.parse(
            rawEvent(evidence),
            EvidenceInput(OcrTranscript.MEDIA_TYPE, evidence),
        )
        evidence.fill(0)

        assertEquals(0, decoded?.lines?.size)
        assertTrue(result is ParseResult.NeedsUserReview)
        assertNull((result as ParseResult.NeedsUserReview).candidate)
        assertEquals(DiagnosticCode.INSUFFICIENT_FIELDS, result.diagnostic.code)
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

    @Test
    fun `transcript value objects redact recognized text from strings`() {
        val marker = "sensitive-ocr-value-marker"
        val recognized = OcrTranscript.RecognizedLine(marker, bounds = null)
        val evidence = OcrTranscript.encodeSpatial(listOf(recognized))!!
        val decoded = requireNotNull(OcrTranscript.decode(evidence))
        evidence.fill(0)

        assertFalse(recognized.toString().contains(marker))
        assertFalse(decoded.lines.single().toString().contains(marker))
        assertFalse(decoded.toString().contains(marker))
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

    private fun parseSpatial(vararg lines: OcrTranscript.RecognizedLine): ParseResult {
        val evidence = OcrTranscript.encodeSpatial(lines.toList())!!
        return parser.parse(
            rawEvent(evidence),
            EvidenceInput(OcrTranscript.MEDIA_TYPE, evidence),
        ).also {
            evidence.fill(0)
        }
    }

    private fun spatial(
        value: String,
        top: Int,
        bottom: Int,
    ) = OcrTranscript.RecognizedLine(
        value = value,
        bounds = OcrTranscript.Bounds(
            left = 1_000,
            top = top,
            right = 9_000,
            bottom = bottom,
        ),
    )

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
