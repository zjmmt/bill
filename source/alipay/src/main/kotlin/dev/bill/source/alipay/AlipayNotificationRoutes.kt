package dev.bill.source.alipay

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

/** Strict, default-disabled routes derived from owner-authorized Android 16 samples. */
object AlipayNotificationRoutes {
    val routes: List<VerifiedNotificationRoute> = listOf(
        route(
            id = "alipay.notification.outbound-cny.v1",
            safeLabel = "支付宝支出通知（实验性）",
            amountField = NotificationField.TEXT,
            direction = ObservedMoneyDirection.OUTBOUND,
            matcher = { content ->
                content.field(NotificationField.TITLE) == "交易提醒" &&
                    content.field(NotificationField.TEXT)?.let { text ->
                        text.startsWith("你有一笔") &&
                            "的支出" in text &&
                            CnyNotificationAmounts.parseConsistentSingle(
                                content,
                                NotificationField.TEXT,
                            ) != null
                    } == true
            },
        ),
        route(
            id = "alipay.notification.balance-receipt-cny.v1",
            safeLabel = "支付宝余额收款通知（实验性）",
            amountField = NotificationField.TITLE,
            direction = ObservedMoneyDirection.INBOUND,
            matcher = { content ->
                content.field(NotificationField.TITLE)?.let { title ->
                    "成功收款" in title &&
                        CnyNotificationAmounts.parseConsistentSingle(
                            content,
                            NotificationField.TITLE,
                        ) != null
                } == true &&
                    content.field(NotificationField.TEXT)?.startsWith("已转入余额") == true
            },
        ),
    )

    private fun route(
        id: String,
        safeLabel: String,
        amountField: NotificationField,
        direction: ObservedMoneyDirection,
        matcher: (dev.bill.source.genericnotification.NotificationContent) -> Boolean,
    ) = VerifiedNotificationRoute(
        routeId = id,
        sourceIdentity = identity(id),
        template = NotificationTemplate(
            id = id,
            version = TEMPLATE_VERSION,
            packageName = PACKAGE_NAME,
            channelId = DEFAULT_CHANNEL,
            category = null,
            contentMatcher = matcher,
        ),
        safeLabel = safeLabel,
        parserFactory = { verifiedRoute ->
            FinancialNotificationParser(
                route = verifiedRoute,
                amountField = amountField,
                direction = direction,
            )
        },
    )

    private fun identity(id: String) = SourceIdentity(
        parserId = ParserId(id),
        providerId = ProviderId("alipay-cn-app"),
        sourceFamily = SourceFamily.ALIPAY,
        connectorId = ConnectorId(id),
        capabilities = setOf(SourceCapability.AMOUNT, SourceCapability.MONEY_DIRECTION),
        supportedCaptureMethods = setOf(CaptureMethod.NOTIFICATION),
        parserVersion = VersionId("parser-1"),
        ruleVersion = VersionId("sample-2026-07-31-v1"),
    )

    internal const val PACKAGE_NAME = "com.eg.android.AlipayGphone"
    internal const val DEFAULT_CHANNEL = "alipay_default"
    internal const val TEMPLATE_VERSION = "android-16-2026-07-31-v1"
}
