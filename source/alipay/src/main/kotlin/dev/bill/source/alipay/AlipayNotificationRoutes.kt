package dev.bill.source.alipay

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.EvidenceLocator
import dev.bill.source.contract.FieldCandidate
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.ObservedEconomicEvent
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceCapability
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.SourceParser
import dev.bill.source.contract.VersionId
import dev.bill.source.genericnotification.CnyNotificationAmounts
import dev.bill.source.genericnotification.FinancialNotificationParser
import dev.bill.source.genericnotification.NotificationEnvelope
import dev.bill.source.genericnotification.NotificationEnvelopeCodec
import dev.bill.source.genericnotification.NotificationEnvelopeDecodeResult
import dev.bill.source.genericnotification.NotificationEvidenceMediaTypes
import dev.bill.source.genericnotification.NotificationTemplate
import dev.bill.source.genericnotification.VerifiedNotificationRoute
import java.math.BigDecimal

/** Strict, default-disabled routes derived from owner-authorized Android 16 samples. */
object AlipayNotificationRoutes {
    val routes: List<VerifiedNotificationRoute> = listOf(
        route(
            id = "alipay.notification.outbound-cny.v1",
            safeLabel = "支付宝 · 支出",
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
            safeLabel = "支付宝 · 余额收款",
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
        VerifiedNotificationRoute(
            routeId = FUND_BUY_ROUTE_ID,
            sourceIdentity = fundBuyIdentity(),
            template = NotificationTemplate(
                id = FUND_BUY_ROUTE_ID,
                version = TEMPLATE_VERSION,
                packageName = PACKAGE_NAME,
                channelId = DEFAULT_CHANNEL,
                category = null,
                contentMatcher = ::matchesConfirmedFundBuy,
            ),
            safeLabel = "支付宝 · 基金申购",
            parserFactory = ::AlipayConfirmedFundBuyParser,
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

    private fun fundBuyIdentity() = SourceIdentity(
        parserId = ParserId(FUND_BUY_ROUTE_ID),
        providerId = ProviderId("alipay-cn-app"),
        sourceFamily = SourceFamily.ALIPAY,
        connectorId = ConnectorId(FUND_BUY_ROUTE_ID),
        capabilities = setOf(
            SourceCapability.AMOUNT,
            SourceCapability.MONEY_DIRECTION,
            SourceCapability.ECONOMIC_EVENT,
        ),
        supportedCaptureMethods = setOf(CaptureMethod.NOTIFICATION),
        parserVersion = VersionId("parser-1"),
        ruleVersion = VersionId("sample-2026-07-31-v1"),
    )

    private fun matchesConfirmedFundBuy(
        content: dev.bill.source.genericnotification.NotificationContent,
    ): Boolean = content.field(NotificationField.TITLE) in CONFIRMED_FUND_BUY_TITLES &&
        content.field(NotificationField.TEXT)?.let(ConfirmedFundBuyAmounts::parse) != null

    internal const val PACKAGE_NAME = "com.eg.android.AlipayGphone"
    internal const val DEFAULT_CHANNEL = "alipay_default"
    internal const val TEMPLATE_VERSION = "android-16-2026-07-31-v1"
    internal const val FUND_BUY_ROUTE_ID = "alipay.notification.fund-buy-confirmed-cny.v1"
    internal const val CONFIRMED_FUND_BUY_TITLE = "基金申购确认成功通知"
    internal const val CONFIRMED_FUND_BUY_TITLE_TRADITIONAL = "基金申購確認成功通知"
    internal val CONFIRMED_FUND_BUY_TITLES = setOf(
        CONFIRMED_FUND_BUY_TITLE,
        CONFIRMED_FUND_BUY_TITLE_TRADITIONAL,
    )
}

private class AlipayConfirmedFundBuyParser(
    private val route: VerifiedNotificationRoute,
) : SourceParser {
    override val identity: SourceIdentity = route.sourceIdentity

    override fun parse(rawEvent: RawEvent, evidenceInput: EvidenceInput): ParseResult {
        if (!identity.accepts(rawEvent)) return rejected(DiagnosticCode.SOURCE_NOT_ACCEPTED)
        if (evidenceInput.mediaType != NotificationEvidenceMediaTypes.ENVELOPE) {
            return rejected(DiagnosticCode.UNSUPPORTED_MEDIA_TYPE)
        }
        if (evidenceInput.sizeBytes > NotificationEnvelopeCodec.MAX_ENCODED_BYTES) {
            return rejected(DiagnosticCode.EVIDENCE_TOO_LARGE)
        }
        val bytes = evidenceInput.copyBytes()
        val decoded = try {
            NotificationEnvelopeCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
        val envelope = (decoded as? NotificationEnvelopeDecodeResult.Decoded)?.envelope
            ?: return rejected(DiagnosticCode.MALFORMED_EVIDENCE)
        if (
            envelope.templateId != route.routeId ||
            envelope.templateVersion != route.template.version ||
            !matches(envelope)
        ) {
            return rejected(DiagnosticCode.SOURCE_NOT_ACCEPTED)
        }
        val amount = envelope.content.field(NotificationField.TEXT)
            ?.let(ConfirmedFundBuyAmounts::parse)
            ?: return rejected(DiagnosticCode.INSUFFICIENT_FIELDS)
        val locator = EvidenceLocator.NotificationFieldLocator(NotificationField.TEXT)
        return ParseResult.Parsed(
            NormalizedCandidate(
                amount = FieldCandidate(amount, 0.99, locator),
                moneyDirection = FieldCandidate(ObservedMoneyDirection.OUTBOUND, 0.99, locator),
                economicEvent = FieldCandidate(ObservedEconomicEvent.INVEST_BUY, 0.99, locator),
            ),
        )
    }

    private fun matches(envelope: NotificationEnvelope): Boolean = try {
        route.template.matchesContent(envelope.content)
    } catch (_: RuntimeException) {
        false
    }

    private fun rejected(code: DiagnosticCode) = ParseResult.Rejected(
        SafeDiagnostic(code = code, recoverable = false),
    )
}

/** V1 accepts only the verified zero-fee confirmation shape; non-zero fees fail closed. */
private object ConfirmedFundBuyAmounts {
    private val confirmedAmount = Regex(
        "(?:确认金额|確認金額)[：:]?\\s*(\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元",
    )
    private val feeAmount = Regex(
        "(?:手续费|手續費)[：:]?\\s*(\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元",
    )

    fun parse(text: String): dev.bill.core.model.Money? {
        if (text.isBlank() || text.length > 2_048) return null
        val amountMatches = confirmedAmount.findAll(text).take(2).toList()
        val feeMatches = feeAmount.findAll(text).take(2).toList()
        if (amountMatches.size != 1 || feeMatches.size != 1) return null
        val amount = amountMatches.single().groupValues[1].toCnyMinorUnits() ?: return null
        val fee = feeMatches.single().groupValues[1].toCnyMinorUnits(allowZero = true)
            ?: return null
        if (fee != 0L) return null
        return dev.bill.core.model.Money.cny(amount)
    }

    private fun String.toCnyMinorUnits(allowZero: Boolean = false): Long? {
        val minorUnits = try {
            BigDecimal(replace(",", "")).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            return null
        } catch (_: NumberFormatException) {
            return null
        }
        return minorUnits.takeIf { if (allowZero) it >= 0L else it > 0L }
    }
}
