package dev.bill.source.genericnotification

import dev.bill.core.model.Money
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.EvidenceLocator
import dev.bill.source.contract.FieldCandidate
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.SourceParser
import java.math.BigDecimal

/**
 * Strict shared mechanics for provider-owned notification rules. The owning provider still chooses
 * exact metadata, completion words, amount field and direction; this class only replays the
 * accepted envelope and turns one unambiguous CNY amount into review candidates.
 */
class FinancialNotificationParser(
    private val route: VerifiedNotificationRoute,
    private val amountField: NotificationField,
    private val direction: ObservedMoneyDirection,
    private val amountConfidence: Double = 0.99,
    private val directionConfidence: Double = 0.99,
) : SourceParser {
    override val identity: SourceIdentity = route.sourceIdentity

    init {
        require(amountConfidence in 0.0..1.0)
        require(directionConfidence in 0.0..1.0)
    }

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
                !matchesContent(envelope)
        ) {
            return rejected(DiagnosticCode.SOURCE_NOT_ACCEPTED)
        }

        val amount = CnyNotificationAmounts.parseConsistentSingle(envelope.content, amountField)
            ?: return rejected(DiagnosticCode.INSUFFICIENT_FIELDS)
        val locator = EvidenceLocator.NotificationFieldLocator(amountField)
        return ParseResult.Parsed(
            NormalizedCandidate(
                amount = FieldCandidate(
                    value = amount,
                    confidence = amountConfidence,
                    evidenceLocator = locator,
                ),
                moneyDirection = FieldCandidate(
                    value = direction,
                    confidence = directionConfidence,
                    evidenceLocator = locator,
                ),
            ),
        )
    }

    private fun rejected(code: DiagnosticCode) = ParseResult.Rejected(
        SafeDiagnostic(code = code, recoverable = false),
    )

    private fun matchesContent(envelope: NotificationEnvelope): Boolean = try {
        route.template.matchesContent(envelope.content)
    } catch (_: RuntimeException) {
        false
    }
}

/** Parses exactly one positive CNY token and refuses balances, card tails or competing amounts. */
object CnyNotificationAmounts {
    private val pattern = Regex(
        """(?:(?:人民币|(?<![A-Za-z])CNY)\s*|[¥￥]\s*)(\d{1,9}(?:,\d{3})*(?:\.\d{1,2})?)(?![\d.,])|(?<![-+\d.,])(\d{1,9}(?:,\d{3})*(?:\.\d{1,2})?)\s*元""",
    )
    private val foreignCurrencyMarker = Regex(
        """(?:(?<![A-Za-z])(?:USD|HKD|EUR|JPY)(?![A-Za-z])|US[${'$'}]|HK[${'$'}]|美元|港币|港元|欧元|日元|[${'$'}＄€])""",
        RegexOption.IGNORE_CASE,
    )

    fun parseSingle(text: String): Money? {
        if (text.isBlank() || text.length > NotificationContent.MAX_FIELD_CHARACTERS) return null
        val matches = pattern.findAll(text).take(2).toList()
        if (matches.size != 1) return null
        val match = matches.single()
        if (isSigned(text, match)) return null
        return parseMoney(match)
    }

    /**
     * Accepts one amount in the provider-selected field and rejects a different or signed amount
     * anywhere else in the bounded notification content. Repeated rendering of the same amount in
     * bigText/subText is allowed because Android commonly mirrors the visible line.
     */
    fun parseConsistentSingle(
        content: NotificationContent,
        amountField: NotificationField,
    ): Money? {
        if (
            NotificationField.entries.any { field ->
                content.field(field)?.contains(foreignCurrencyMarker) == true
            }
        ) {
            return null
        }
        val selected = content.field(amountField)?.let(::parseSingle) ?: return null
        val consistent = NotificationField.entries.all { field ->
            val text = content.field(field) ?: return@all true
            pattern.findAll(text).all { match ->
                !isSigned(text, match) && parseMoney(match) == selected
            }
        }
        return selected.takeIf { consistent }
    }

    private fun isSigned(text: String, match: MatchResult): Boolean {
        val preceding = text
            .substring(0, match.range.first)
            .trimEnd()
            .lastOrNull()
        return preceding == '-' || preceding == '+'
    }

    private fun parseMoney(match: MatchResult): Money? {
        val decimal = match.groupValues
            .drop(1)
            .firstOrNull(String::isNotEmpty)
            ?.replace(",", "")
            ?: return null
        val minorUnits = try {
            BigDecimal(decimal).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            return null
        } catch (_: NumberFormatException) {
            return null
        }
        return minorUnits.takeIf { it > 0L }?.let(Money::cny)
    }
}
