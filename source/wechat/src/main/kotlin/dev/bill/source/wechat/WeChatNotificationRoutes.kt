package dev.bill.source.wechat

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.SourceCapability
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.VersionId
import dev.bill.source.genericnotification.CnyNotificationAmounts
import dev.bill.source.genericnotification.FinancialNotificationParser
import dev.bill.source.genericnotification.NotificationTemplate
import dev.bill.source.genericnotification.VerifiedNotificationRoute

/**
 * Current verified English-body payment completion route.
 *
 * The stable route id is retained for existing local opt-ins. English and Chinese payment titles
 * are exact aliases, but Chinese body wording remains rejected until a real callback is reviewed.
 * 红包/转账/提现 state messages are excluded.
 */
object WeChatNotificationRoutes {
    val routes: List<VerifiedNotificationRoute> = listOf(
        VerifiedNotificationRoute(
            routeId = ROUTE_ID,
            sourceIdentity = SourceIdentity(
                parserId = ParserId(ROUTE_ID),
                providerId = ProviderId("wechat-pay-cn-app"),
                sourceFamily = SourceFamily.WECHAT,
                connectorId = ConnectorId(ROUTE_ID),
                capabilities = setOf(
                    SourceCapability.AMOUNT,
                    SourceCapability.MONEY_DIRECTION,
                ),
                supportedCaptureMethods = setOf(CaptureMethod.NOTIFICATION),
                parserVersion = VersionId("parser-2"),
                ruleVersion = VersionId("sample-2026-07-31-title-alias-v2"),
            ),
            template = NotificationTemplate(
                id = ROUTE_ID,
                version = TEMPLATE_VERSION,
                packageName = PACKAGE_NAME,
                channelId = MESSAGE_CHANNEL,
                category = MESSAGE_CATEGORY,
                contentMatcher = { content ->
                    content.field(NotificationField.TITLE) in PAYMENT_TITLES &&
                        content.field(NotificationField.TEXT)?.let(::isCompletedPayment) == true &&
                        CnyNotificationAmounts.parseConsistentSingle(
                            content,
                            NotificationField.TEXT,
                        ) != null
                },
            ),
            safeLabel = "微信支付付款通知（中文正文待样本；会本地检查同频道消息，实验性）",
            parserFactory = { route ->
                FinancialNotificationParser(
                    route = route,
                    amountField = NotificationField.TEXT,
                    direction = ObservedMoneyDirection.OUTBOUND,
                )
            },
        ),
    )

    private fun isCompletedPayment(text: String): Boolean {
        val trimmed = text.trim()
        return COMPLETED_PAYMENT.matches(trimmed)
    }

    internal const val ROUTE_ID = "wechat.notification.paid-en-cny.v1"
    internal const val PACKAGE_NAME = "com.tencent.mm"
    internal const val MESSAGE_CHANNEL = "message_channel_new_id"
    internal const val MESSAGE_CATEGORY = "msg"
    internal const val TEMPLATE_VERSION = "android-16-en-body-title-alias-2026-08-01-v2"

    private val PAYMENT_TITLES = setOf("Weixin Pay", "微信支付")

    private val COMPLETED_PAYMENT = Regex(
        """[¥￥]\s*\d{1,9}(?:,\d{3})*(?:\.\d{1,2})?\s+paid""",
        RegexOption.IGNORE_CASE,
    )
}
