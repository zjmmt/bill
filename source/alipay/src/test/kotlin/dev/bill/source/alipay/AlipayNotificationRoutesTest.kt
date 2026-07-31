package dev.bill.source.alipay

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.ObservedEconomicEvent
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlipayNotificationRoutesTest {
    private val catalog = NotificationRouteCatalog(AlipayNotificationRoutes.routes)
    private val gate = NotificationTemplateGate(catalog) { true }

    @Test
    fun `sanitized outbound and balance receipt replays produce CNY review candidates`() {
        val outboundContent = content(
            title = "交易提醒",
            text = "你有一笔￥12.34的支出，请在支付宝内核对",
        )
        val inboundContent = content(
            title = "示例成功收款56.78元，获得消费金",
            text = "已转入余额，请在账单中核对",
        )

        val outboundRoute = accepted(outboundContent)
        val inboundRoute = accepted(inboundContent)
        val outbound = parse(outboundRoute, outboundContent) as ParseResult.Parsed
        val inbound = parse(inboundRoute, inboundContent) as ParseResult.Parsed

        assertEquals("alipay.notification.outbound-cny.v1", outboundRoute.routeId)
        assertEquals(1_234L, outbound.candidate.amount?.value?.minorUnits)
        assertEquals(ObservedMoneyDirection.OUTBOUND, outbound.candidate.moneyDirection?.value)
        assertEquals("alipay.notification.balance-receipt-cny.v1", inboundRoute.routeId)
        assertEquals(5_678L, inbound.candidate.amount?.value?.minorUnits)
        assertEquals(ObservedMoneyDirection.INBOUND, inbound.candidate.moneyDirection?.value)
    }

    @Test
    fun `missing amount drift promotion and competing amounts fail closed`() {
        val cases = listOf(
            content("交易提醒", "有一笔支出，请稍后查看"),
            content("账单提醒", "你有一笔￥12.34的支出"),
            content("优惠活动", "消费红包￥12.34，立即领取"),
            content("交易提醒", "支出￥12.34，余额￥56.78"),
            content("示例成功收款12.34元", "请打开支付宝参加活动"),
            content("示例成功收款12.34元", "已转入余额￥56.78"),
        )

        cases.forEach { candidate ->
            assertEquals(NotificationGateDecision.IgnoredContent, gate.evaluate(metadata()) { candidate })
        }
    }

    @Test
    fun `sanitized confirmed fund purchase proposes investment purchase only`() {
        val content = content(
            title = AlipayNotificationRoutes.CONFIRMED_FUND_BUY_TITLE,
            text = "确认金额：12.34元 手续费：0.00元",
        )

        val route = accepted(content)
        val parsed = parse(route, content) as ParseResult.Parsed

        assertEquals(AlipayNotificationRoutes.FUND_BUY_ROUTE_ID, route.routeId)
        assertEquals(1_234L, parsed.candidate.amount?.value?.minorUnits)
        assertEquals(ObservedMoneyDirection.OUTBOUND, parsed.candidate.moneyDirection?.value)
        assertEquals(ObservedEconomicEvent.INVEST_BUY, parsed.candidate.economicEvent?.value)
    }

    @Test
    fun `traditional Chinese fund confirmation keeps the same route and amount semantics`() {
        val content = content(
            title = AlipayNotificationRoutes.CONFIRMED_FUND_BUY_TITLE_TRADITIONAL,
            text = "確認金額：56.78元 手續費：0.00元",
        )

        val route = accepted(content)
        val parsed = parse(route, content) as ParseResult.Parsed

        assertEquals(AlipayNotificationRoutes.FUND_BUY_ROUTE_ID, route.routeId)
        assertEquals(5_678L, parsed.candidate.amount?.value?.minorUnits)
        assertEquals(ObservedEconomicEvent.INVEST_BUY, parsed.candidate.economicEvent?.value)
    }

    @Test
    fun `accepted gain and nonzero fee fund notices stay out of the route`() {
        val cases = listOf(
            content("基金申购申请已受理通知", "申请金额：12.34元"),
            content("基金收益提醒", "昨日收益：12.34元"),
            content(
                AlipayNotificationRoutes.CONFIRMED_FUND_BUY_TITLE,
                "确认金额：12.34元 手续费：0.01元",
            ),
            content(
                AlipayNotificationRoutes.CONFIRMED_FUND_BUY_TITLE,
                "确认金额：12.34元 确认金额：56.78元 手续费：0.00元",
            ),
        )

        cases.forEach { candidate ->
            assertEquals(NotificationGateDecision.IgnoredContent, gate.evaluate(metadata()) { candidate })
        }
    }

    @Test
    fun `wrong channel does not read notification fields`() {
        var reads = 0
        val decision = gate.evaluate(
            NotificationMetadata(
                packageName = AlipayNotificationRoutes.PACKAGE_NAME,
                channelId = "alipay_transfer",
                category = null,
            ),
        ) {
            reads += 1
            content("交易提醒", "支出￥12.34")
        }

        assertEquals(NotificationGateDecision.IgnoredMetadata, decision)
        assertEquals(0, reads)
    }

    @Test
    fun `parser rechecks the sanitized template after transport`() {
        val route = AlipayNotificationRoutes.routes.first()
        val drifted = content("交易提醒", "支出金额稍后确认")
        val result = parse(route, drifted)

        assertTrue(result is ParseResult.Rejected)
        assertEquals(DiagnosticCode.SOURCE_NOT_ACCEPTED, (result as ParseResult.Rejected).diagnostic.code)
        assertFalse(result.toString().contains("支出金额"))
    }

    private fun accepted(content: NotificationContent): dev.bill.source.genericnotification.VerifiedNotificationRoute {
        val decision = gate.evaluate(metadata()) { content }
        assertTrue(decision is NotificationGateDecision.Accepted)
        return (decision as NotificationGateDecision.Accepted).route
    }

    private fun parse(
        route: dev.bill.source.genericnotification.VerifiedNotificationRoute,
        content: NotificationContent,
    ): ParseResult {
        val parser = catalog.parsers().single { it.identity == route.sourceIdentity }
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
        return parser.parse(rawEvent(route), evidence)
    }

    private fun rawEvent(route: dev.bill.source.genericnotification.VerifiedNotificationRoute) = RawEvent(
        id = RawEventId("sanitized-alipay-fixture"),
        sourceFamily = route.sourceIdentity.sourceFamily,
        connectorId = route.sourceIdentity.connectorId,
        captureMethod = CaptureMethod.NOTIFICATION,
        captureScope = CaptureScopeId("local-install"),
        contentHash = EvidenceHash.fromBytes("sanitized".toByteArray()),
        capturedAt = Instant.parse("2026-07-31T00:00:00Z"),
        payloadId = PayloadId("sanitized-alipay-payload"),
        payloadSizeBytes = 9L,
    )

    private fun metadata() = NotificationMetadata(
        packageName = AlipayNotificationRoutes.PACKAGE_NAME,
        channelId = AlipayNotificationRoutes.DEFAULT_CHANNEL,
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
