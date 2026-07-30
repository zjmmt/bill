package dev.bill.source.contract

import java.time.Instant

private val statementImportIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

object GenericDelimitedStatementIdentity {
    const val PARSER_ID = "generic-delimited-statement"
    const val PROVIDER_ID = "user-mapped-statement"
    const val CONNECTOR_ID = "android-saf-delimited-row"
}

/** Opaque local identifier derived from file and mapping hashes, never a filename or URI. */
@JvmInline
value class StatementImportBatchId(val value: String) {
    init {
        require(statementImportIdPattern.matches(value)) {
            "Statement import batch id must be a bounded opaque ASCII token"
        }
    }
}

enum class StatementImportBatchState {
    IN_PROGRESS,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
}

data class StatementImportBatchRecord(
    val id: StatementImportBatchId,
    val fileHash: EvidenceHash,
    val mappingHash: EvidenceHash,
    val totalRowCount: Int,
    val processedRowCount: Int,
    val readyForReviewCount: Int,
    val rejectedRowCount: Int,
    val state: StatementImportBatchState,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(totalRowCount in 1..MAX_STATEMENT_IMPORT_ROWS)
        require(processedRowCount in 0..totalRowCount)
        require(readyForReviewCount >= 0)
        require(rejectedRowCount >= 0)
        require(readyForReviewCount + rejectedRowCount == processedRowCount)
        require(!updatedAt.isBefore(createdAt))
        when (state) {
            StatementImportBatchState.IN_PROGRESS -> require(
                processedRowCount < totalRowCount,
            )

            StatementImportBatchState.COMPLETED -> require(
                processedRowCount == totalRowCount && rejectedRowCount == 0,
            )

            StatementImportBatchState.COMPLETED_WITH_ERRORS -> require(
                processedRowCount == totalRowCount && rejectedRowCount > 0,
            )
        }
    }
}

data class StatementImportBatchRequest(
    val id: StatementImportBatchId,
    val fileHash: EvidenceHash,
    val mappingHash: EvidenceHash,
    val totalRowCount: Int,
    val now: Instant,
) {
    init {
        require(totalRowCount in 1..MAX_STATEMENT_IMPORT_ROWS)
    }
}

sealed interface StatementImportBatchOpenResult {
    data class Created(val batch: StatementImportBatchRecord) :
        StatementImportBatchOpenResult

    data class Existing(val batch: StatementImportBatchRecord) :
        StatementImportBatchOpenResult

    data object IdCollision : StatementImportBatchOpenResult
}

enum class StatementImportRowState {
    READY_FOR_REVIEW,
    REJECTED,
}

/**
 * One durable, content-free row outcome. [errorCode] is a bounded enum-like token and must never
 * contain a cell value, filename, URI, provider identifier, or exception message.
 */
data class StatementImportRowRecord(
    val batchId: StatementImportBatchId,
    /** Zero-based table row including the header at index zero; data rows therefore start at one. */
    val tableRowIndex: Int,
    val rowFingerprint: EvidenceHash,
    val state: StatementImportRowState,
    val rawEventId: RawEventId?,
    val errorCode: String?,
    val updatedAt: Instant,
) {
    init {
        require(tableRowIndex in 1..MAX_STATEMENT_IMPORT_ROWS)
        require(errorCode == null || statementImportIdPattern.matches(errorCode))
        when (state) {
            StatementImportRowState.READY_FOR_REVIEW -> {
                require(rawEventId != null)
                require(errorCode == null)
            }

            StatementImportRowState.REJECTED -> {
                require(rawEventId == null)
                require(errorCode != null)
            }
        }
    }
}

sealed interface StatementImportRowWriteResult {
    data object Inserted : StatementImportRowWriteResult
    data object AlreadyPresent : StatementImportRowWriteResult
    data object IdCollision : StatementImportRowWriteResult
    data object BatchNotFound : StatementImportRowWriteResult
}

sealed interface StatementImportBatchRefreshResult {
    data class Updated(val batch: StatementImportBatchRecord) :
        StatementImportBatchRefreshResult

    data object NotFound : StatementImportBatchRefreshResult
}

interface StatementImportBatchRepository {
    suspend fun open(request: StatementImportBatchRequest): StatementImportBatchOpenResult

    suspend fun find(id: StatementImportBatchId): StatementImportBatchRecord?

    suspend fun listRows(id: StatementImportBatchId): List<StatementImportRowRecord>

    suspend fun recordRow(row: StatementImportRowRecord): StatementImportRowWriteResult

    /**
     * Recomputes counters and terminal state from durable row outcomes in one local transaction.
     * Missing rows keep the batch in progress, so a reselected file can resume them.
     */
    suspend fun refresh(
        id: StatementImportBatchId,
        now: Instant,
    ): StatementImportBatchRefreshResult
}

const val MAX_STATEMENT_IMPORT_ROWS = 5_000
