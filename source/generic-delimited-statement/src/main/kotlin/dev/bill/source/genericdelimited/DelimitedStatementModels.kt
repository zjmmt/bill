package dev.bill.source.genericdelimited

import dev.bill.core.model.CurrencyCode
import dev.bill.source.contract.EvidenceHash
import java.util.Locale

enum class DelimitedDelimiter(val character: Char) {
    COMMA(','),
    TAB('\t'),
}

data class DelimitedReadLimits(
    val maxBytes: Int = DEFAULT_MAX_BYTES,
    val maxRecords: Int = DEFAULT_MAX_RECORDS,
    val maxColumns: Int = DEFAULT_MAX_COLUMNS,
    val maxCellChars: Int = DEFAULT_MAX_CELL_CHARS,
    val maxRecordChars: Int = DEFAULT_MAX_RECORD_CHARS,
) {
    init {
        require(maxBytes in 1..HARD_MAX_BYTES)
        require(maxRecords in 2..HARD_MAX_RECORDS)
        require(maxColumns in 1..HARD_MAX_COLUMNS)
        require(maxCellChars in 1..HARD_MAX_CELL_CHARS)
        require(maxRecordChars in maxCellChars..HARD_MAX_RECORD_CHARS)
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024
        const val DEFAULT_MAX_RECORDS = 5_001
        const val DEFAULT_MAX_COLUMNS = 64
        const val DEFAULT_MAX_CELL_CHARS = 1_024
        const val DEFAULT_MAX_RECORD_CHARS = 16 * 1_024

        const val HARD_MAX_BYTES = 4 * 1024 * 1024
        const val HARD_MAX_RECORDS = 10_001
        const val HARD_MAX_COLUMNS = 128
        const val HARD_MAX_CELL_CHARS = 4_096
        const val HARD_MAX_RECORD_CHARS = 64 * 1_024
    }
}

data class DelimitedRow(
    /** Zero-based table row, including the header at index zero. */
    val tableRowIndex: Int,
    val cells: List<String>,
) {
    init {
        require(tableRowIndex > 0) { "A statement data row must follow the header" }
        require(cells.isNotEmpty()) { "A statement row must contain at least one cell" }
    }
}

data class DelimitedDocument(
    val delimiter: DelimitedDelimiter,
    val fileHash: EvidenceHash,
    val header: List<String>,
    val rows: List<DelimitedRow>,
) {
    init {
        require(header.isNotEmpty()) { "A delimited document must contain a header" }
    }
}

enum class DelimitedReadError {
    EMPTY_DOCUMENT,
    CONTENT_TOO_LARGE,
    MALFORMED_UTF8,
    NUL_CHARACTER,
    UNEXPECTED_QUOTE,
    CHARACTERS_AFTER_CLOSING_QUOTE,
    UNCLOSED_QUOTED_FIELD,
    TOO_MANY_RECORDS,
    TOO_MANY_COLUMNS,
    CELL_TOO_LONG,
    RECORD_TOO_LONG,
}

sealed interface DelimitedReadResult {
    data class Success(val document: DelimitedDocument) : DelimitedReadResult

    data class Failure(val error: DelimitedReadError) : DelimitedReadResult
}

enum class StatementDateFormat(
    internal val pattern: String,
    internal val hasTime: Boolean,
    internal val hasSeconds: Boolean,
) {
    DATE_DASH("uuuu-MM-dd", hasTime = false, hasSeconds = false),
    DATE_SLASH("uuuu/MM/dd", hasTime = false, hasSeconds = false),
    DATE_TIME_MINUTE_DASH("uuuu-MM-dd HH:mm", hasTime = true, hasSeconds = false),
    DATE_TIME_MINUTE_SLASH("uuuu/MM/dd HH:mm", hasTime = true, hasSeconds = false),
    DATE_TIME_SECOND_DASH("uuuu-MM-dd HH:mm:ss", hasTime = true, hasSeconds = true),
    DATE_TIME_SECOND_SLASH("uuuu/MM/dd HH:mm:ss", hasTime = true, hasSeconds = true),
    ISO_DATE_TIME_MINUTE("uuuu-MM-dd'T'HH:mm", hasTime = true, hasSeconds = false),
    ISO_DATE_TIME_SECOND("uuuu-MM-dd'T'HH:mm:ss", hasTime = true, hasSeconds = true),
}

enum class StatementAmountFormat {
    DOT_DECIMAL,
    COMMA_GROUP_DOT_DECIMAL,
    COMMA_DECIMAL,
    DOT_GROUP_COMMA_DECIMAL,
}

enum class StatementDirection {
    INBOUND,
    OUTBOUND,
}

sealed interface StatementDirectionMapping {
    data class SignedAmount(
        val positiveDirection: StatementDirection,
    ) : StatementDirectionMapping

    data class DirectionColumn(
        val columnIndex: Int,
        val inboundTokens: Set<String>,
        val outboundTokens: Set<String>,
        val caseSensitive: Boolean = false,
    ) : StatementDirectionMapping {
        init {
            require(columnIndex >= 0)
            require(inboundTokens.isNotEmpty())
            require(outboundTokens.isNotEmpty())
            require(inboundTokens.size <= MAX_DIRECTION_TOKENS)
            require(outboundTokens.size <= MAX_DIRECTION_TOKENS)
            require((inboundTokens + outboundTokens).all(::isValidDirectionToken))

            val normalizedInbound = inboundTokens.mapTo(mutableSetOf()) {
                normalizeDirectionToken(it, caseSensitive)
            }
            val normalizedOutbound = outboundTokens.mapTo(mutableSetOf()) {
                normalizeDirectionToken(it, caseSensitive)
            }
            require(normalizedInbound.size == inboundTokens.size)
            require(normalizedOutbound.size == outboundTokens.size)
            require(normalizedInbound.intersect(normalizedOutbound).isEmpty()) {
                "Inbound and outbound direction tokens cannot overlap"
            }
        }
    }
}

data class DelimitedStatementMapping(
    val delimiter: DelimitedDelimiter,
    val dateColumnIndex: Int,
    val dateFormat: StatementDateFormat,
    val amountColumnIndex: Int,
    val amountFormat: StatementAmountFormat,
    val directionMapping: StatementDirectionMapping,
    val counterpartyColumnIndex: Int,
    val referenceColumnIndex: Int?,
    val currency: CurrencyCode,
) {
    init {
        require(dateColumnIndex >= 0)
        require(amountColumnIndex >= 0)
        require(counterpartyColumnIndex >= 0)
        require(referenceColumnIndex == null || referenceColumnIndex >= 0)
        require(currency == CurrencyCode.CNY || currency == CurrencyCode.USD) {
            "Delimited statement imports support CNY and USD without conversion"
        }

        val mappedColumns = buildList {
            add(dateColumnIndex)
            add(amountColumnIndex)
            add(counterpartyColumnIndex)
            referenceColumnIndex?.let(::add)
            (directionMapping as? StatementDirectionMapping.DirectionColumn)
                ?.columnIndex
                ?.let(::add)
        }
        require(mappedColumns.distinct().size == mappedColumns.size) {
            "Each statement field must map to a distinct column"
        }
    }
}

enum class StatementMappingError {
    DELIMITER_MISMATCH,
    COLUMN_OUT_OF_RANGE,
    EMPTY_DATE,
    INVALID_DATE,
    EMPTY_AMOUNT,
    INVALID_AMOUNT,
    AMOUNT_OUT_OF_RANGE,
    ZERO_AMOUNT,
    SIGNED_AMOUNT_WITH_DIRECTION_COLUMN,
    EMPTY_DIRECTION,
    UNKNOWN_DIRECTION,
    EMPTY_COUNTERPARTY,
    COUNTERPARTY_TOO_LONG,
    REFERENCE_TOO_LONG,
}

internal const val MAX_DIRECTION_TOKENS = 16
internal const val MAX_DIRECTION_TOKEN_CHARS = 64
internal const val MAX_COUNTERPARTY_CHARS = 256
internal const val MAX_REFERENCE_CHARS = 512

internal fun normalizeDirectionToken(value: String, caseSensitive: Boolean): String {
    val trimmed = value.trim()
    return if (caseSensitive) trimmed else trimmed.lowercase(Locale.ROOT)
}

private fun isValidDirectionToken(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= MAX_DIRECTION_TOKEN_CHARS &&
        '\u0000' !in value
