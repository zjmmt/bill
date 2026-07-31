package dev.bill.source.bank.cmb

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

/** 招商银行 App route; login notices and Samsung SMS notifications are deliberately excluded. */
object CmbNotificationRoutes {
    val routes: List<VerifiedNotificationRoute> = listOf(
        VerifiedNotificationRoute(
            routeId = ROUTE_ID,
            sourceIdentity = SourceIdentity(
                parserId = ParserId(ROUTE_ID),
                providerId = ProviderId("bank-cmb-cn-app"),
                sourceFamily = SourceFamily.BANK,
                connectorId = ConnectorId(ROUTE_ID),
                capabilities = setOf(
                    SourceCapability.AMOUNT,
                    SourceCapability.MONEY_DIRECTION,
                ),
                supportedCaptureMethods = setOf(CaptureMethod.NOTIFICATION),
                parserVersion = VersionId("parser-1"),
                ruleVersion = VersionId("sample-2026-07-31-v1"),
            ),
            template = NotificationTemplate(
                id = ROUTE_ID,
                version = TEMPLATE_VERSION,
                packageName = PACKAGE_NAME,
                channelId = TRANSACTION_CHANNEL,
                category = null,
                contentMatcher = { content ->
                    content.field(NotificationField.TITLE) == "招商银行" &&
                        content.field(NotificationField.TEXT)?.let { text ->
                            val firstPayment = text.indexOf("支付")
                            val refund = text.indexOf("快捷支付退款")
                            firstPayment >= 0 && firstPayment < refund &&
                                CnyNotificationAmounts.parseConsistentSingle(
                                    content,
                                    NotificationField.TEXT,
                                ) != null
                        } == true
                },
            ),
            safeLabel = "招商银行快捷支付退款通知（实验性）",
            parserFactory = { route ->
                FinancialNotificationParser(
                    route = route,
                    amountField = NotificationField.TEXT,
                    direction = ObservedMoneyDirection.INBOUND,
                )
            },
        ),
    )

    internal const val ROUTE_ID = "bank.cmb.notification.quick-refund-cny.v1"
    internal const val PACKAGE_NAME = "cmb.pb"
    internal const val TRANSACTION_CHANNEL = "channelId4oppoandroidp"
    internal const val TEMPLATE_VERSION = "android-16-2026-07-31-v1"
}
