package dev.bill.source.bank.cmb

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.genericnotification.NotificationContent
import dev.bill.source.genericnotification.NotificationEnvelope
import dev.bill.source.genericnotification.NotificationEnvelopeCodec
import dev.bill.source.genericnotification.NotificationEvidenceMediaTypes
import dev.bill.source.genericnotification.NotificationGateDecision
import dev.bill.source.genericnotification.NotificationMetadata
import dev.bill.source.genericnotification.NotificationRouteCatalog
import dev.bill.source.genericnotification.NotificationTemplateGate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CmbNotificationRoutesTest {
    private val route = CmbNotificationRoutes.routes.single()
    private val catalog = NotificationRouteCatalog(listOf(route))
    private val gate = NotificationTemplateGate(catalog) { true }

    @Test
    fun `sanitized quick payment refund replay produces an inbound CNY candidate`() {
        val content = content(
            title = "招商银行",
            text = "示例账户支付完成，现收到快捷支付退款人民币12.34元",
        )
        val decision = gate.evaluate(metadata()) { content }
        assertTrue(decision is NotificationGateDecision.Accepted)

        val parsed = parse(content) as ParseResult.Parsed
        assertEquals(1_234L, parsed.candidate.amount?.value?.minorUnits)
        assertEquals(ObservedMoneyDirection.INBOUND, parsed.candidate.moneyDirection?.value)
    }

    @Test
    fun `login marketing missing amount and competing amount notices fail closed`() {
        listOf(
            content("招商银行", "您已安全登录"),
            content("招商银行", "快捷支付退款使用指南，示例金额12.34元"),
            content("招商银行", "支付完成，快捷支付退款正在处理"),
            content("招商银行", "支付12.34元，快捷支付退款56.78元"),
            content("其他银行", "支付完成，快捷支付退款12.34元"),
        ).forEach { candidate ->
            assertEquals(NotificationGateDecision.IgnoredContent, gate.evaluate(metadata()) { candidate })
        }
    }

    @Test
    fun `login and SMS channels are rejected before reading content`() {
        listOf(
            NotificationMetadata(CmbNotificationRoutes.PACKAGE_NAME, "cmb.pb.LoginNotice", null),
            NotificationMetadata("com.samsung.android.messaging", "CHANNEL_ID_SMS_MMS", "msg"),
        ).forEach { metadata ->
            var reads = 0
            val decision = gate.evaluate(metadata) {
                reads += 1
                content("招商银行", "支付完成，快捷支付退款12.34元")
            }
            assertEquals(NotificationGateDecision.IgnoredMetadata, decision)
            assertEquals(0, reads)
        }
    }

    @Test
    fun `transport replay rechecks the refund completion rule`() {
        val result = parse(content("招商银行", "快捷支付退款12.34元"))

        assertTrue(result is ParseResult.Rejected)
        assertEquals(DiagnosticCode.SOURCE_NOT_ACCEPTED, (result as ParseResult.Rejected).diagnostic.code)
    }

    private fun parse(content: NotificationContent): ParseResult {
        val envelope = NotificationEnvelope(
            templateId = route.routeId,
            templateVersion = route.template.version,
            postedAtEpochMillis = 1_700_000_000_000L,
            content = content,
        )
        val encoded = checkNotNull(NotificationEnvelopeCodec.encode(envelope))
        val evidence = try {
            EvidenceInput(NotificationEvidenceMediaTypes.ENVELOPE, encoded)
        } finally {
            encoded.fill(0)
        }
        return catalog.parsers().single().parse(rawEvent(), evidence)
    }

    private fun rawEvent() = RawEvent(
        id = RawEventId("sanitized-cmb-fixture"),
        sourceFamily = route.sourceIdentity.sourceFamily,
        connectorId = route.sourceIdentity.connectorId,
        captureMethod = CaptureMethod.NOTIFICATION,
        captureScope = CaptureScopeId("local-install"),
        contentHash = EvidenceHash.fromBytes("sanitized".toByteArray()),
        capturedAt = Instant.parse("2026-07-31T00:00:00Z"),
        payloadId = PayloadId("sanitized-cmb-payload"),
        payloadSizeBytes = 9L,
    )

    private fun metadata() = NotificationMetadata(
        packageName = CmbNotificationRoutes.PACKAGE_NAME,
        channelId = CmbNotificationRoutes.TRANSACTION_CHANNEL,
        category = null,
    )

    private fun content(title: String, text: String): NotificationContent = checkNotNull(
        NotificationContent.from(
            mapOf(
                NotificationField.TITLE to title,
                NotificationField.TEXT to text,
            ),
        ),
    )
}
