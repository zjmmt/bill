package dev.bill.source.genericphotoocr

import dev.bill.core.model.Money
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.EvidenceLocator
import dev.bill.source.contract.FieldCandidate
import dev.bill.source.contract.NormalizedCandidate
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
import java.math.BigDecimal

/**
 * Conservative, provider-neutral parser for a locally produced OCR transcript.
 *
 * It proposes fields only for explicit synthetic patterns. A generic screenshot is never treated
 * as provider proof and never becomes an automatically confirmed transaction.
 */
class GenericPhotoOcrParser : SourceParser {
    override val identity: SourceIdentity = SourceIdentity(
        parserId = ParserId("generic-photo-ocr"),
        providerId = ProviderId("user-triggered-photo-ocr"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId(OcrTranscript.CONNECTOR_ID),
        capabilities = setOf(
            SourceCapability.AMOUNT,
            SourceCapability.MONEY_DIRECTION,
            SourceCapability.COUNTERPARTY,
        ),
        supportedCaptureMethods = setOf(CaptureMethod.PHOTO_OCR),
        parserVersion = VersionId("parser-1"),
        ruleVersion = VersionId("rules-1"),
    )

    override fun parse(rawEvent: RawEvent, evidenceInput: EvidenceInput): ParseResult {
        if (!identity.accepts(rawEvent)) return rejected(DiagnosticCode.SOURCE_NOT_ACCEPTED)
        if (evidenceInput.mediaType != OcrTranscript.MEDIA_TYPE) {
            return rejected(DiagnosticCode.UNSUPPORTED_MEDIA_TYPE)
        }
        if (evidenceInput.sizeBytes > OcrTranscript.MAX_EVIDENCE_BYTES) {
            return rejected(DiagnosticCode.EVIDENCE_TOO_LARGE)
        }

        val bytes = evidenceInput.copyBytes()
        val transcript = try {
            OcrTranscript.decode(bytes)
        } finally {
            bytes.fill(0)
        } ?: return rejected(DiagnosticCode.MALFORMED_EVIDENCE)

        val amount = uniqueAmount(transcript)
        val direction = direction(transcript)
        val counterparty = uniqueCounterparty(transcript)
        val candidate = if (amount == null && direction == null && counterparty == null) {
            null
        } else {
            NormalizedCandidate(
                amount = amount,
                moneyDirection = direction,
                counterparty = counterparty,
            )
        }
        return ParseResult.NeedsUserReview(
            candidate = candidate,
            diagnostic = SafeDiagnostic(
                code = DiagnosticCode.INSUFFICIENT_FIELDS,
                recoverable = true,
            ),
        )
    }

    private fun uniqueAmount(
        transcript: OcrTranscript.Decoded,
    ): FieldCandidate<Money>? {
        val matches = transcript.lines.flatMap { line ->
            amountPatterns.flatMap { pattern ->
                pattern.findAll(line.value).mapNotNull { match ->
                    val amountGroup = match.groups["amount"] ?: return@mapNotNull null
                    val minorUnits = parseMinorUnits(amountGroup.value) ?: return@mapNotNull null
                    if (minorUnits <= 0L) return@mapNotNull null
                    AmountMatch(
                        minorUnits = minorUnits,
                        locator = EvidenceLocator.TextRange(
                            startInclusive = line.startInclusive + match.range.first,
                            endExclusive = line.startInclusive + match.range.last + 1,
                        ),
                    )
                }.toList()
            }
        }
        val distinctValues = matches.map(AmountMatch::minorUnits).distinct()
        if (distinctValues.size != 1) return null
        val selected = matches.first { it.minorUnits == distinctValues.single() }
        return FieldCandidate(
            value = Money.cny(selected.minorUnits),
            confidence = 0.86,
            evidenceLocator = selected.locator,
        )
    }

    private fun direction(
        transcript: OcrTranscript.Decoded,
    ): FieldCandidate<ObservedMoneyDirection>? {
        val semanticBlocker = transcript.lines.firstOrNull { line ->
            blockedMoneySemantics.any(line.value::contains)
        }
        if (semanticBlocker != null) return null

        val outbound = firstToken(transcript, outboundTokens)
        val inbound = firstToken(transcript, inboundTokens)
        if ((outbound == null) == (inbound == null)) return null
        val selected = outbound ?: inbound!!
        return FieldCandidate(
            value = if (outbound != null) {
                ObservedMoneyDirection.OUTBOUND
            } else {
                ObservedMoneyDirection.INBOUND
            },
            confidence = 0.82,
            evidenceLocator = selected,
        )
    }

    private fun uniqueCounterparty(
        transcript: OcrTranscript.Decoded,
    ): FieldCandidate<String>? {
        val matches = transcript.lines.mapNotNull { line ->
            val match = counterpartyPattern.find(line.value) ?: return@mapNotNull null
            val valueGroup = match.groups["value"] ?: return@mapNotNull null
            val value = valueGroup.value.trim()
            if (value.isEmpty() || value.length > MAX_COUNTERPARTY_CHARS) return@mapNotNull null
            if (amountPatterns.any { it.containsMatchIn(value) }) return@mapNotNull null
            CounterpartyMatch(
                value = value,
                locator = EvidenceLocator.TextRange(
                    startInclusive = line.startInclusive + valueGroup.range.first,
                    endExclusive = line.startInclusive + valueGroup.range.last + 1,
                ),
            )
        }
        val distinct = matches.map(CounterpartyMatch::value).distinct()
        if (distinct.size != 1) return null
        val selected = matches.first { it.value == distinct.single() }
        return FieldCandidate(
            value = selected.value,
            confidence = 0.78,
            evidenceLocator = selected.locator,
        )
    }

    private fun firstToken(
        transcript: OcrTranscript.Decoded,
        tokens: List<String>,
    ): EvidenceLocator.TextRange? {
        transcript.lines.forEach { line ->
            tokens.forEach { token ->
                val start = line.value.indexOf(token)
                if (start >= 0) {
                    return EvidenceLocator.TextRange(
                        startInclusive = line.startInclusive + start,
                        endExclusive = line.startInclusive + start + token.length,
                    )
                }
            }
        }
        return null
    }

    private fun parseMinorUnits(value: String): Long? = runCatching {
        BigDecimal(value.replace(",", ""))
            .movePointRight(2)
            .longValueExact()
    }.getOrNull()

    private fun rejected(code: DiagnosticCode) = ParseResult.Rejected(
        SafeDiagnostic(code = code, recoverable = false),
    )

    private data class AmountMatch(
        val minorUnits: Long,
        val locator: EvidenceLocator.TextRange,
    )

    private data class CounterpartyMatch(
        val value: String,
        val locator: EvidenceLocator.TextRange,
    )

    private companion object {
        const val MAX_COUNTERPARTY_CHARS = 80
        val amountPatterns = listOf(
            Regex("""(?:人民币\s*)?[¥￥]\s*(?<amount>\d{1,12}(?:,\d{3})*(?:\.\d{1,2})?)"""),
            Regex("""(?:RMB|CNY)\s*(?<amount>\d{1,12}(?:,\d{3})*(?:\.\d{1,2})?)"""),
            Regex("""(?<amount>\d{1,12}(?:,\d{3})*(?:\.\d{1,2})?)\s*元"""),
        )
        val outboundTokens = listOf("支付成功", "付款成功", "已付款", "扣款成功", "支出")
        val inboundTokens = listOf("收款成功", "已收款", "入账成功", "到账", "收入")
        val blockedMoneySemantics = listOf(
            "退款",
            "退回",
            "红包",
            "转账",
            "充值",
            "提现",
            "还款",
            "信用卡",
            "零钱通",
            "基金",
        )
        val counterpartyPattern = Regex(
            """(?:商户|收款方|付款方|对方|收款人|付款人)\s*[:：]\s*(?<value>\S.{0,79})""",
        )
    }
}
