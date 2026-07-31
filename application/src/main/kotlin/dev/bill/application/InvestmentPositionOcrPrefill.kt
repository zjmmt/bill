package dev.bill.application

import java.math.BigDecimal
import java.util.Locale

data class InvestmentPositionOcrPrefill(
    val name: String?,
    val instrumentCode: String?,
    val currentValue: String?,
    val units: String?,
    val costBasis: String?,
) {
    init {
        require(listOf(name, instrumentCode, currentValue, units, costBasis).any { it != null })
    }
}

enum class InvestmentPositionOcrPrefillError {
    NO_RECOGNIZED_FIELDS,
    AMBIGUOUS_FIELDS,
}

sealed interface InvestmentPositionOcrPrefillResult {
    data class Success(
        val prefill: InvestmentPositionOcrPrefill,
    ) : InvestmentPositionOcrPrefillResult

    data class Failure(
        val error: InvestmentPositionOcrPrefillError,
    ) : InvestmentPositionOcrPrefillResult
}

/**
 * Extracts only explicitly labelled investment fields from an ephemeral local OCR result.
 *
 * It deliberately has no fallback that guesses the largest amount on screen: payment amount,
 * daily gain and current value often appear together on finance pages. Any conflicting labelled
 * values reject the whole prefill so the UI cannot make an unsafe field look authoritative.
 */
object InvestmentPositionOcrPrefillParser {
    fun parse(recognizedLines: List<String>): InvestmentPositionOcrPrefillResult {
        val lines = recognizedLines.mapNotNull(::normalizeLine).take(MAX_LINES)
        if (lines.isEmpty()) {
            return InvestmentPositionOcrPrefillResult.Failure(
                InvestmentPositionOcrPrefillError.NO_RECOGNIZED_FIELDS,
            )
        }

        val name = candidates(lines, NAME_LABELS, ::parseName)
        val code = candidates(lines, CODE_LABELS, ::parseCode)
        val currentValue = candidates(lines, CURRENT_VALUE_LABELS, ::parseMoney)
        val units = candidates(lines, UNITS_LABELS, ::parseUnits)
        val costBasis = candidates(lines, COST_LABELS, ::parseMoney)
        val fields = listOf(name, code, currentValue, units, costBasis)
        if (fields.any(FieldCandidates::ambiguous)) {
            return InvestmentPositionOcrPrefillResult.Failure(
                InvestmentPositionOcrPrefillError.AMBIGUOUS_FIELDS,
            )
        }
        if (fields.none { it.value != null }) {
            return InvestmentPositionOcrPrefillResult.Failure(
                InvestmentPositionOcrPrefillError.NO_RECOGNIZED_FIELDS,
            )
        }
        return InvestmentPositionOcrPrefillResult.Success(
            InvestmentPositionOcrPrefill(
                name = name.value,
                instrumentCode = code.value,
                currentValue = currentValue.value,
                units = units.value,
                costBasis = costBasis.value,
            ),
        )
    }

    private fun candidates(
        lines: List<String>,
        labels: List<String>,
        parse: (String) -> String?,
    ): FieldCandidates {
        val values = buildSet {
            lines.forEachIndexed { index, line ->
                val matchedLabel = labels.firstOrNull { label ->
                    line.contains(label, ignoreCase = true)
                } ?: return@forEachIndexed
                val labelEnd = line.indexOf(matchedLabel, ignoreCase = true) + matchedLabel.length
                val inlineValue = line.substring(labelEnd).trim(*FIELD_DELIMITERS)
                val source = inlineValue.takeIf(String::isNotBlank)
                    ?: lines.getOrNull(index + 1)
                        ?.takeUnless { next ->
                            ALL_LABELS.any { label -> next.contains(label, ignoreCase = true) }
                        }
                    ?: return@forEachIndexed
                parse(source)?.let(::add)
            }
        }
        return FieldCandidates(
            value = values.singleOrNull(),
            ambiguous = values.size > 1,
        )
    }

    private fun parseName(value: String): String? {
        val normalized = value.trim(*FIELD_DELIMITERS).replace(WHITESPACE, " ")
        if (normalized.isBlank() || normalized.length > MAX_NAME_LENGTH) return null
        if (normalized.hasControlCharacter() || MONEY_ONLY.matches(normalized)) return null
        return normalized
    }

    private fun parseCode(value: String): String? {
        val matches = CODE_TOKEN.findAll(value.uppercase(Locale.ROOT))
            .map(MatchResult::value)
            .distinct()
            .toList()
        return matches.singleOrNull()
    }

    private fun parseMoney(value: String): String? {
        if (BLOCKED_AMOUNT_CONTEXT.containsMatchIn(value)) return null
        val normalized = value.replace(",", "")
        val matches = DECIMAL_TOKEN.findAll(normalized)
            .map(MatchResult::value)
            .distinct()
            .toList()
        val decimal = matches.singleOrNull()?.toBigDecimalOrNull() ?: return null
        if (decimal <= BigDecimal.ZERO || decimal.scale() > 2) return null
        return decimal.stripTrailingZeros().toPlainString()
    }

    private fun parseUnits(value: String): String? {
        val normalized = value.replace(",", "")
        val matches = DECIMAL_TOKEN.findAll(normalized)
            .map(MatchResult::value)
            .distinct()
            .toList()
        val decimal = matches.singleOrNull()?.toBigDecimalOrNull() ?: return null
        if (decimal <= BigDecimal.ZERO || decimal.scale() > 8 || decimal.precision() > 24) {
            return null
        }
        return decimal.stripTrailingZeros().toPlainString()
    }

    private fun normalizeLine(value: String): String? {
        if (value.hasControlCharacter()) return null
        val normalized = value.trim().replace(WHITESPACE, " ")
        return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_LINE_LENGTH }
    }

    private fun String.hasControlCharacter(): Boolean = any(Char::isISOControl)

    private data class FieldCandidates(
        val value: String?,
        val ambiguous: Boolean,
    )

    private const val MAX_LINES = 128
    private const val MAX_LINE_LENGTH = 256
    private const val MAX_NAME_LENGTH = 80
    private val FIELD_DELIMITERS = charArrayOf(' ', '\t', ':', '：', '-', '—')
    private val WHITESPACE = Regex("[\\t ]+")
    private val MONEY_ONLY = Regex("^[¥￥$]?\\s*[0-9][0-9,.]*\\s*(?:元|CNY|RMB)?$", RegexOption.IGNORE_CASE)
    private val CODE_TOKEN = Regex("(?<![A-Z0-9])[A-Z0-9][A-Z0-9._-]{1,31}(?![A-Z0-9])")
    private val DECIMAL_TOKEN = Regex("(?<![0-9.])(?:0|[1-9][0-9]{0,15})(?:\\.[0-9]{1,8})?(?![0-9.])")
    private val BLOCKED_AMOUNT_CONTEXT = Regex(
        "[%％]|收益|收益率|涨跌|漲跌|盈亏|盈虧|gain|return|profit|yield|損益|増減|騰落",
        RegexOption.IGNORE_CASE,
    )

    private val NAME_LABELS = listOf(
        "基金名称", "基金名稱", "产品名称", "產品名稱", "持仓名称", "持倉名稱",
        "fund name", "product name", "investment name", "ファンド名", "商品名", "銘柄名",
    )
    private val CODE_LABELS = listOf(
        "基金代码", "基金代碼", "产品代码", "產品代碼", "fund code", "product code",
        "ファンドコード", "銘柄コード",
    )
    private val CURRENT_VALUE_LABELS = listOf(
        "当前持有金额", "目前持有金額", "当前金额", "目前金額", "持有金额", "持有金額",
        "持有市值", "基金市值", "参考市值", "參考市值", "最新市值", "总资产", "總資產",
        "current value", "market value", "holding value", "valuation",
        "評価額", "時価評価額", "保有金額", "現在価値", "資産額",
    )
    private val UNITS_LABELS = listOf(
        "持有份额", "持有份額", "基金份额", "基金份額", "持有数量", "持有數量",
        "holding units", "units held", "holding shares", "保有口数", "保有数量",
    )
    private val COST_LABELS = listOf(
        "持仓成本", "持倉成本", "累计投入", "累計投入", "投资本金", "投資本金",
        "cost basis", "invested amount", "取得価額", "投資元本",
    )
    private val ALL_LABELS =
        NAME_LABELS + CODE_LABELS + CURRENT_VALUE_LABELS + UNITS_LABELS + COST_LABELS
}
