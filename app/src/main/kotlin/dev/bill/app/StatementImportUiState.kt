package dev.bill.app

import dev.bill.core.model.CurrencyCode
import dev.bill.source.genericdelimited.DelimitedDelimiter
import dev.bill.source.genericdelimited.StatementAmountFormat
import dev.bill.source.genericdelimited.StatementDateFormat
import dev.bill.source.genericdelimited.StatementDirection
import dev.bill.source.genericdelimited.StatementMappingError

enum class StatementImportDirectionMode {
    SIGNED_AMOUNT,
    DIRECTION_COLUMN,
}

data class StatementImportMappingInput(
    val dateColumnIndex: Int?,
    val dateFormat: StatementDateFormat,
    val amountColumnIndex: Int?,
    val amountFormat: StatementAmountFormat,
    val directionMode: StatementImportDirectionMode,
    val positiveDirection: StatementDirection,
    val directionColumnIndex: Int?,
    val inboundTokens: String,
    val outboundTokens: String,
    val counterpartyColumnIndex: Int?,
    val referenceColumnIndex: Int?,
    val currency: CurrencyCode,
)

data class StatementImportColumnUi(
    val index: Int,
    val label: String,
) {
    override fun toString(): String = "StatementImportColumnUi(index=$index, redacted=true)"
}

data class StatementImportSampleRowUi(
    val tableRowIndex: Int,
    val cells: List<String>,
) {
    override fun toString(): String =
        "StatementImportSampleRowUi(row=$tableRowIndex, redacted=true)"
}

data class StatementImportRowOutcomeUi(
    val tableRowIndex: Int,
    val error: StatementMappingError?,
)

data class StatementImportMappingPreviewUi(
    val validRowCount: Int,
    val invalidRowCount: Int,
    val rowOutcomes: List<StatementImportRowOutcomeUi>,
)

enum class StatementImportConfigurationIssue {
    INCOMPLETE_FIELDS,
    DUPLICATE_COLUMNS,
    EMPTY_DIRECTION_TOKENS,
    TOO_MANY_DIRECTION_TOKENS,
    INVALID_DIRECTION_TOKEN,
    OVERLAPPING_DIRECTION_TOKENS,
    INVALID_MAPPING,
}

enum class StatementImportUiError {
    READER_UNAVAILABLE,
    INVALID_DOCUMENT,
    UNSUPPORTED_MEDIA_TYPE,
    CONTENT_TOO_LARGE,
    READ_FAILED,
    DELIMITER_MISMATCH,
    NO_DATA_ROWS,
    MALFORMED_UTF8,
    MALFORMED_DOCUMENT,
    TOO_MANY_RECORDS,
    TOO_MANY_COLUMNS,
    CELL_TOO_LONG,
    RECORD_TOO_LONG,
    CLOSED_SESSION,
    IMPORT_INTERRUPTED,
    BATCH_IDENTITY_COLLISION,
    ROW_IDENTITY_COLLISION,
}

sealed interface StatementImportUiState {
    data object Idle : StatementImportUiState

    data class Reading(
        val delimiter: DelimitedDelimiter,
    ) : StatementImportUiState

    data class Mapping(
        val delimiter: DelimitedDelimiter,
        val columns: List<StatementImportColumnUi>,
        val sampleRows: List<StatementImportSampleRowUi>,
        val totalDataRowCount: Int,
        val input: StatementImportMappingInput,
        val preview: StatementImportMappingPreviewUi?,
        val isPreviewing: Boolean,
        val configurationIssue: StatementImportConfigurationIssue?,
        val operationError: StatementImportUiError? = null,
    ) : StatementImportUiState {
        override fun toString(): String =
            "StatementImportUiState.Mapping(rows=$totalDataRowCount, redacted=true)"
    }

    data class Importing(
        val processedRowCount: Int,
        val totalRowCount: Int,
    ) : StatementImportUiState

    data class Completed(
        val readyForReviewCount: Int,
        val rejectedRowCount: Int,
        val resumedRowCount: Int,
    ) : StatementImportUiState

    data class Failed(
        val error: StatementImportUiError,
    ) : StatementImportUiState
}
