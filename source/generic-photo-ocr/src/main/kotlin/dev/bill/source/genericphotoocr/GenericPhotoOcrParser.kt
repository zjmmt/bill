package dev.bill.source.genericphotoocr

import dev.bill.core.model.Money
import dev.bill.core.model.toLongExactCompat
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
        parserVersion = VersionId("parser-4"),
        ruleVersion = VersionId("rules-4"),
    )

    override fun parse(rawEvent: RawEvent, evidenceInput: EvidenceInput): ParseResult {
        if (!identity.accepts(rawEvent)) return rejected(DiagnosticCode.SOURCE_NOT_ACCEPTED)
        if (evidenceInput.mediaType !in OcrTranscript.SUPPORTED_MEDIA_TYPES) {
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
        if (transcript.mediaType != evidenceInput.mediaType) {
            return rejected(DiagnosticCode.MALFORMED_EVIDENCE)
        }

        val amount = uniqueAmount(transcript)
        val direction = direction(transcript, amount)
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
        if (hasNonPostingStatus(transcript)) return null
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
                        bounds = line.bounds,
                    )
                }.toList()
            }
        }
        val distinctValues = matches.map(AmountMatch::minorUnits).distinct()
        val dominantSpatial = dominantSpatialAmount(transcript)
        val selected = when (distinctValues.size) {
            0 -> dominantSpatial ?: return null
            1 -> dominantSpatial ?: matches.first { it.minorUnits == distinctValues.single() }
            else -> dominantSpatial ?: return null
        }
        return FieldCandidate(
            value = Money.cny(selected.minorUnits),
            confidence = 0.86,
            evidenceLocator = selected.locator,
        )
    }

    /**
     * A screenshot may contain list price, discount and balance alongside one large result amount.
     * We select only an otherwise standalone amount whose OCR box is both globally prominent and
     * clearly taller than every competing standalone amount. Text-only v1 evidence cannot enter
     * this path, and equally prominent values remain ambiguous.
     */
    private fun dominantSpatialAmount(transcript: OcrTranscript.Decoded): AmountMatch? {
        val candidatePatterns = if (hasCompletedMoneyContext(transcript)) {
            standaloneAmountPatterns + contextualHeroAmountPattern
        } else {
            standaloneAmountPatterns
        }
        val candidates = transcript.lines.mapNotNull { line ->
            val bounds = line.bounds ?: return@mapNotNull null
            val match = candidatePatterns.firstNotNullOfOrNull { pattern ->
                pattern.matchEntire(line.value)
            } ?: return@mapNotNull null
            val amountGroup = match.groups["amount"] ?: return@mapNotNull null
            val minorUnits = parseMinorUnits(amountGroup.value) ?: return@mapNotNull null
            if (minorUnits <= 0L || bounds.top >= MAX_HERO_TOP) {
                return@mapNotNull null
            }
            AmountMatch(
                minorUnits = minorUnits,
                locator = EvidenceLocator.TextRange(
                    startInclusive = line.startInclusive + match.range.first,
                    endExclusive = line.startInclusive + match.range.last + 1,
                ),
                bounds = bounds,
            )
        }
        val strongestByValue = candidates
            .groupBy(AmountMatch::minorUnits)
            .values
            .mapNotNull { sameValue -> sameValue.maxByOrNull { it.bounds?.height ?: 0 } }
            .sortedByDescending { it.bounds?.height ?: 0 }
        val strongest = strongestByValue.firstOrNull() ?: return null
        val strongestHeight = strongest.bounds?.height ?: return null
        val medianHeight = transcript.lines
            .mapNotNull { it.bounds?.height }
            .sorted()
            .let(::medianOrNull)
            ?: return null
        if (strongestHeight * HERO_MEDIAN_DENOMINATOR < medianHeight * HERO_MEDIAN_NUMERATOR) {
            return null
        }
        val runnerUpHeight = strongestByValue.getOrNull(1)?.bounds?.height
        if (
            runnerUpHeight != null &&
            strongestHeight * HERO_RUNNER_UP_DENOMINATOR <
            runnerUpHeight * HERO_RUNNER_UP_NUMERATOR
        ) {
            return null
        }
        return strongest
    }

    private fun direction(
        transcript: OcrTranscript.Decoded,
        amount: FieldCandidate<Money>?,
    ): FieldCandidate<ObservedMoneyDirection>? {
        if (hasNonPostingStatus(transcript)) return null
        if (hasBlockingMoneySemantics(transcript)) return null

        val outbound = firstToken(transcript, outboundTokens)
        val inbound = firstToken(transcript, inboundTokens)
        if (outbound == null && inbound == null) {
            return signedDirection(transcript, amount)
        }
        if (outbound != null && inbound != null) return null
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
                val start = line.value.indexOf(token, ignoreCase = true)
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

    private fun hasCompletedMoneyContext(transcript: OcrTranscript.Decoded): Boolean =
        transcript.lines.any { line ->
            completedMoneyContextTokens.any { token ->
                line.value.contains(token, ignoreCase = true)
            }
        }

    private fun hasBlockingMoneySemantics(transcript: OcrTranscript.Decoded): Boolean {
        val ordinaryPaymentCompleted = firstToken(transcript, outboundTokens) != null
        return transcript.lines.any { line ->
            val matched = blockedMoneySemantics.filter { token ->
                line.value.contains(token, ignoreCase = true)
            }
            if (matched.isEmpty()) return@any false
            val promotionalRedPacket = ordinaryPaymentCompleted &&
                matched.all { matchedToken ->
                    redPacketSemantics.any { token ->
                        matchedToken.equals(token, ignoreCase = true)
                    }
                } &&
                promotionTokens.any { token ->
                    line.value.contains(token, ignoreCase = true)
                }
            !promotionalRedPacket
        }
    }

    private fun signedDirection(
        transcript: OcrTranscript.Decoded,
        amount: FieldCandidate<Money>?,
    ): FieldCandidate<ObservedMoneyDirection>? {
        if (
            transcript.lines.none { line ->
                signedDirectionContextTokens.any { token ->
                    line.value.contains(token, ignoreCase = true)
                }
            }
        ) {
            return null
        }
        val amountLocator = amount?.evidenceLocator as? EvidenceLocator.TextRange ?: return null
        val line = transcript.lines.firstOrNull { candidate ->
            amountLocator.startInclusive >= candidate.startInclusive &&
                amountLocator.endExclusive <= candidate.endExclusive
        } ?: return null
        val match = signedAmountPattern.matchEntire(line.value) ?: return null
        val amountGroup = match.groups["amount"] ?: return null
        if (parseMinorUnits(amountGroup.value) != amount.value.minorUnits) return null
        val signGroup = match.groups["sign"] ?: return null
        val direction = if (signGroup.value == "+") {
            ObservedMoneyDirection.INBOUND
        } else {
            ObservedMoneyDirection.OUTBOUND
        }
        return FieldCandidate(
            value = direction,
            confidence = 0.80,
            evidenceLocator = EvidenceLocator.TextRange(
                startInclusive = line.startInclusive + signGroup.range.first,
                endExclusive = line.startInclusive + signGroup.range.last + 1,
            ),
        )
    }

    private fun hasNonPostingStatus(transcript: OcrTranscript.Decoded): Boolean {
        val nonPostingLines = transcript.lines.filter { line ->
            nonPostingStatusPhrases.any { phrase ->
                line.value.contains(phrase, ignoreCase = true)
            } || normalizedStatusLine(line.value) in nonPostingWholeLineStatuses
        }
        if (nonPostingLines.isEmpty()) return false
        if (nonPostingLines.any { it.bounds == null }) return true
        val lastNonPostingTop = nonPostingLines.maxOf { requireNotNull(it.bounds).top }
        return transcript.lines.none { line ->
            val completionTop = line.bounds?.top ?: return@none false
            completionTop > lastNonPostingTop && authoritativeCompletionPhrases.any { phrase ->
                line.value.contains(phrase, ignoreCase = true)
            }
        }
    }

    private fun normalizedStatusLine(value: String): String = value
        .trim()
        .trimEnd('.', '!', '！', '。', '…', ':', '：')
        .trim()
        .lowercase()

    private fun parseMinorUnits(value: String): Long? = try {
        BigDecimal(value.replace(",", ""))
            .movePointRight(2)
            .toLongExactCompat()
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private fun rejected(code: DiagnosticCode) = ParseResult.Rejected(
        SafeDiagnostic(code = code, recoverable = false),
    )

    private data class AmountMatch(
        val minorUnits: Long,
        val locator: EvidenceLocator.TextRange,
        val bounds: OcrTranscript.Bounds?,
    )

    private data class CounterpartyMatch(
        val value: String,
        val locator: EvidenceLocator.TextRange,
    )

    private companion object {
        const val MAX_COUNTERPARTY_CHARS = 80
        const val MAX_HERO_TOP = 7_500
        const val HERO_MEDIAN_NUMERATOR = 14
        const val HERO_MEDIAN_DENOMINATOR = 10
        const val HERO_RUNNER_UP_NUMERATOR = 5
        const val HERO_RUNNER_UP_DENOMINATOR = 4
        val moneyNumber = "\\d{1,12}(?:,\\d{3})*(?:\\.\\d{1,2})?"
        val amountPatterns = listOf(
            Regex("""(?:人民币\s*)?[¥￥]\s*(?<amount>$moneyNumber)"""),
            Regex("""(?:RMB|CNY)\s*(?<amount>$moneyNumber)""", RegexOption.IGNORE_CASE),
            Regex("""(?<amount>$moneyNumber)\s*元"""),
            Regex("""(?<amount>$moneyNumber)\s*(?:RMB|CNY)""", RegexOption.IGNORE_CASE),
        )
        val standaloneAmountPatterns = listOf(
            Regex("""[+-]?\s*(?:人民币\s*)?[¥￥]\s*(?<amount>$moneyNumber)(?:\s*元)?"""),
            Regex("""(?:RMB|CNY)\s*(?<amount>$moneyNumber)""", RegexOption.IGNORE_CASE),
            Regex("""(?<amount>$moneyNumber)\s*(?:元|RMB|CNY)""", RegexOption.IGNORE_CASE),
            Regex("""[+-]\s*(?<amount>$moneyNumber)"""),
        )
        val contextualHeroAmountPattern = Regex("""(?<amount>\d{1,12}\.\d{1,2})""")
        val signedAmountPattern = Regex(
            """\s*(?<sign>[+\-−﹣－])\s*(?:人民币\s*)?[¥￥]?\s*""" +
                """(?<amount>$moneyNumber)(?:\s*(?:元|RMB|CNY))?\s*""",
            RegexOption.IGNORE_CASE,
        )
        val outboundTokens = listOf(
            "支付成功",
            "付款成功",
            "已付款",
            "扣款成功",
            "支出",
            "Payment successful",
            "Payment complete",
            "支払い完了",
            "支払完了",
        )
        val inboundTokens = listOf(
            "收款成功",
            "已收款",
            "入账成功",
            "入賬成功",
            "到账",
            "到賬",
            "到帳",
            "收入",
            "领取成功",
            "領取成功",
            "Payment received",
            "Received successfully",
            "受取完了",
        )
        val blockedMoneySemantics = listOf(
            "退款",
            "退回",
            "红包",
            "紅包",
            "转账",
            "轉賬",
            "充值",
            "提现",
            "提現",
            "还款",
            "還款",
            "信用卡",
            "零钱通",
            "零錢通",
            "基金",
            "Refund",
            "Transfer",
            "Red Packet",
            "Withdraw",
            "Withdrawal",
            "Credit Card",
        )
        val redPacketSemantics = listOf("红包", "紅包", "Red Packet")
        val promotionTokens = listOf(
            "恭喜",
            "获得",
            "獲得",
            "优惠",
            "優惠",
            "奖励",
            "獎勵",
            "无门槛",
            "無門檻",
            "去领取",
            "去領取",
            "券",
            "能量",
        )
        val completedMoneyContextTokens = outboundTokens + inboundTokens + listOf(
            "交易成功",
            "转账成功",
            "轉賬成功",
            "Transferred to Wallet",
            "Transfer successful",
            "Withdrawal completed",
        )
        val signedDirectionContextTokens = listOf(
            "交易详情",
            "交易詳情",
            "交易明细",
            "交易明細",
            "账单详情",
            "賬單詳情",
            "Transaction details",
        )
        val nonPostingStatusPhrases = listOf(
            "支付失败",
            "支付失敗",
            "付款失败",
            "付款失敗",
            "交易失败",
            "交易失敗",
            "已取消",
            "取消支付",
            "正在处理",
            "正在處理",
            "处理中",
            "處理中",
            "待处理",
            "待處理",
            "待完成",
            "等待到账",
            "等待到賬",
            "等待到帳",
            "预计到账",
            "預計到賬",
            "預計到帳",
            "未领取",
            "未領取",
            "尚未领取",
            "尚未領取",
            "待领取",
            "待領取",
            "未打开",
            "未打開",
            "尚未打开",
            "尚未打開",
            "未拆开",
            "未拆開",
            "Payment failed",
            "Transaction failed",
            "Transfer failed",
            "Withdrawal failed",
            "Payment rejected",
            "Transaction rejected",
            "Transfer rejected",
            "Withdrawal rejected",
            "Payment cancelled",
            "Payment canceled",
            "Transaction cancelled",
            "Transaction canceled",
            "Transfer cancelled",
            "Transfer canceled",
            "Withdrawal cancelled",
            "Withdrawal canceled",
            "Not yet opened",
            "Not opened",
            "Not yet received",
            "Bank is processing",
            "Being processed",
            "Estimated to arrive",
            "Request withdrawal",
            "Withdrawal requested",
            "Will be refunded",
            "支払い失敗",
            "取引失敗",
            "キャンセル",
            "処理中",
            "手続き中",
            "保留中",
            "未受取",
            "未受領",
            "未開封",
            "受取待ち",
            "完了待ち",
            "到着予定",
            "入金予定",
        )
        val nonPostingWholeLineStatuses = setOf(
            "rejected",
            "cancelled",
            "canceled",
            "pending",
            "processing",
        )
        val authoritativeCompletionPhrases = listOf(
            "银行告知已到账",
            "銀行告知已到賬",
            "銀行告知已到帳",
        )
        val counterpartyPattern = Regex(
            """(?:商户|商戶|收款方|付款方|对方|對方|收款人|付款人)\s*[:：]\s*(?<value>\S.{0,79})""",
        )
    }
}

private fun medianOrNull(sortedValues: List<Int>): Int? {
    if (sortedValues.isEmpty()) return null
    val middle = sortedValues.size / 2
    return if (sortedValues.size % 2 == 1) {
        sortedValues[middle]
    } else {
        (sortedValues[middle - 1] + sortedValues[middle]) / 2
    }
}
