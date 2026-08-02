package dev.bill.source.wechat

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

class WeChatNotificationRoutesTest {
    private val route = WeChatNotificationRoutes.routes.single()
    private val catalog = NotificationRouteCatalog(listOf(route))
    private val gate = NotificationTemplateGate(catalog) { true }

    @Test
    fun `localized title alias keeps the persisted route id stable`() {
        assertEquals("wechat.notification.paid-en-cny.v1", WeChatNotificationRoutes.ROUTE_ID)
        assertEquals(WeChatNotificationRoutes.ROUTE_ID, route.routeId)
    }

    @Test
    fun `sanitized English paid replay produces an outbound CNY candidate`() {
        val content = content("Weixin Pay", "¥12.34 paid")
        val decision = gate.evaluate(metadata()) { content }
        assertTrue(decision is NotificationGateDecision.Accepted)

        val parsed = parse(content) as ParseResult.Parsed
        assertEquals(1_234L, parsed.candidate.amount?.value?.minorUnits)
        assertEquals(ObservedMoneyDirection.OUTBOUND, parsed.candidate.moneyDirection?.value)
    }

    @Test
    fun `Chinese payment title accepts only the already verified English body shape`() {
        val verifiedBody = content("微信支付", "¥12.34 paid")
        assertTrue(gate.evaluate(metadata()) { verifiedBody } is NotificationGateDecision.Accepted)

        listOf(
            content("微信支付", "已支付¥12.34"),
            content("微信支付", "支付成功 ¥12.34"),
            content("微信支付", "¥12.34 付款成功"),
        ).forEach { unverifiedChineseBody ->
            assertEquals(
                NotificationGateDecision.IgnoredContent,
                gate.evaluate(metadata()) { unverifiedChineseBody },
            )
        }
    }

    @Test
    fun `red packet transfer withdrawal private chat and incomplete payment do not match`() {
        listOf(
            content("微信支付", "你收到一个红包"),
            content("微信支付", "Transfer request is waiting for acceptance"),
            content("Contact", "[Transfer] Accept transfer"),
            content("Weixin Pay", "零钱提现已到账"),
            content("Weixin Pay", "Withdrawal is processing"),
            content("Private Chat", "¥12.34 paid"),
            content("Weixin Pay", "¥12.34 not paid"),
            content("Weixin Pay", "payment completed"),
        ).forEach { candidate ->
            assertEquals(NotificationGateDecision.IgnoredContent, gate.evaluate(metadata()) { candidate })
        }
    }

    @Test
    fun `wrong category is rejected before reading private message fields`() {
        var reads = 0
        val decision = gate.evaluate(
            NotificationMetadata(
                packageName = WeChatNotificationRoutes.PACKAGE_NAME,
                channelId = WeChatNotificationRoutes.MESSAGE_CHANNEL,
                category = null,
            ),
        ) {
            reads += 1
            content("Weixin Pay", "¥12.34 paid")
        }

        assertEquals(NotificationGateDecision.IgnoredMetadata, decision)
        assertEquals(0, reads)
    }

    @Test
    fun `transport replay rechecks completion wording`() {
        val result = parse(content("Weixin Pay", "¥12.34 not paid"))

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
        id = RawEventId("sanitized-wechat-fixture"),
        sourceFamily = route.sourceIdentity.sourceFamily,
        connectorId = route.sourceIdentity.connectorId,
        captureMethod = CaptureMethod.NOTIFICATION,
        captureScope = CaptureScopeId("local-install"),
        contentHash = EvidenceHash.fromBytes("sanitized".toByteArray()),
        capturedAt = Instant.parse("2026-07-31T00:00:00Z"),
        payloadId = PayloadId("sanitized-wechat-payload"),
        payloadSizeBytes = 9L,
    )

    private fun metadata() = NotificationMetadata(
        packageName = WeChatNotificationRoutes.PACKAGE_NAME,
        channelId = WeChatNotificationRoutes.MESSAGE_CHANNEL,
        category = WeChatNotificationRoutes.MESSAGE_CATEGORY,
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
