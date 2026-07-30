package dev.bill.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.GenericDelimitedStatementIdentity
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.StatementImportBatchId
import dev.bill.source.contract.StatementImportBatchOpenResult
import dev.bill.source.contract.StatementImportBatchRecord
import dev.bill.source.contract.StatementImportBatchRefreshResult
import dev.bill.source.contract.StatementImportBatchRepository
import dev.bill.source.contract.StatementImportBatchRequest
import dev.bill.source.contract.StatementImportBatchState
import dev.bill.source.contract.StatementImportRowRecord
import dev.bill.source.contract.StatementImportRowState
import dev.bill.source.contract.StatementImportRowWriteResult
import java.time.Instant

class RoomStatementImportBatchRepository(
    private val database: BillDatabase,
) : StatementImportBatchRepository {
    private val dao = database.statementImportDao()
    private val rawEventDao = database.rawEventDao()

    override suspend fun open(
        request: StatementImportBatchRequest,
    ): StatementImportBatchOpenResult = try {
        database.withTransaction {
            ensureIntegrity()
            val existing = dao.findBatch(request.id.value)
            if (existing != null) {
                val record = existing.toRecord()
                return@withTransaction if (
                    record.fileHash == request.fileHash &&
                    record.mappingHash == request.mappingHash &&
                    record.totalRowCount == request.totalRowCount
                ) {
                    StatementImportBatchOpenResult.Existing(record)
                } else {
                    StatementImportBatchOpenResult.IdCollision
                }
            }

            val created = StatementImportBatchRecord(
                id = request.id,
                fileHash = request.fileHash,
                mappingHash = request.mappingHash,
                totalRowCount = request.totalRowCount,
                processedRowCount = 0,
                readyForReviewCount = 0,
                rejectedRowCount = 0,
                state = StatementImportBatchState.IN_PROGRESS,
                createdAt = request.now,
                updatedAt = request.now,
            )
            dao.insertBatch(created.toEntity())
            StatementImportBatchOpenResult.Created(created)
        }
    } catch (_: SQLiteConstraintException) {
        StatementImportBatchOpenResult.IdCollision
    }

    override suspend fun find(id: StatementImportBatchId): StatementImportBatchRecord? =
        database.withTransaction {
            ensureIntegrity()
            dao.findBatch(id.value)?.toRecord()
        }

    override suspend fun listRows(
        id: StatementImportBatchId,
    ): List<StatementImportRowRecord> = database.withTransaction {
        ensureIntegrity()
        dao.listRows(id.value).map(StatementImportRowEntity::toRecord)
    }

    override suspend fun recordRow(
        row: StatementImportRowRecord,
    ): StatementImportRowWriteResult = try {
        database.withTransaction {
            val batch = dao.findBatch(row.batchId.value)
                ?: return@withTransaction StatementImportRowWriteResult.BatchNotFound
            val batchRecord = batch.toRecord()
            if (row.tableRowIndex > batch.totalRowCount) {
                return@withTransaction StatementImportRowWriteResult.IdCollision
            }
            if (row.updatedAt.isBefore(batchRecord.createdAt)) {
                return@withTransaction StatementImportRowWriteResult.IdCollision
            }
            if (!hasMatchingRawEvidence(row)) {
                return@withTransaction StatementImportRowWriteResult.IdCollision
            }

            val existing = dao.findRow(row.batchId.value, row.tableRowIndex)
            if (existing != null) {
                return@withTransaction if (existing.sameOutcome(row)) {
                    StatementImportRowWriteResult.AlreadyPresent
                } else {
                    StatementImportRowWriteResult.IdCollision
                }
            }
            if (batchRecord.processedRowCount >= batchRecord.totalRowCount) {
                return@withTransaction StatementImportRowWriteResult.IdCollision
            }
            dao.insertRow(row.toEntity())
            val readyForReviewDelta =
                if (row.state == StatementImportRowState.READY_FOR_REVIEW) 1 else 0
            val rejectedDelta =
                if (row.state == StatementImportRowState.REJECTED) 1 else 0
            check(
                dao.incrementBatchSummary(
                    id = batchRecord.id.value,
                    readyForReviewDelta = readyForReviewDelta,
                    rejectedDelta = rejectedDelta,
                    updatedAtEpochMillis = maxOf(
                        row.updatedAt,
                        batchRecord.updatedAt,
                    ).toEpochMilli(),
                ) == 1,
            ) { "Statement import batch disappeared during row insertion" }
            StatementImportRowWriteResult.Inserted
        }
    } catch (_: SQLiteConstraintException) {
        StatementImportRowWriteResult.IdCollision
    }

    override suspend fun refresh(
        id: StatementImportBatchId,
        now: Instant,
    ): StatementImportBatchRefreshResult = database.withTransaction {
        ensureIntegrity()
        val current = dao.findBatch(id.value)
            ?: return@withTransaction StatementImportBatchRefreshResult.NotFound
        val currentRecord = current.toRecord()
        StatementImportBatchRefreshResult.Updated(
            refreshLocked(currentRecord, now),
        )
    }

    private suspend fun refreshLocked(
        current: StatementImportBatchRecord,
        now: Instant,
    ): StatementImportBatchRecord {
        val counts = dao.rowCounts(current.id.value)
        check(counts.processedRowCount <= current.totalRowCount) {
            "Statement import rows exceed their batch"
        }
        val state = when {
            counts.processedRowCount < current.totalRowCount ->
                StatementImportBatchState.IN_PROGRESS

            counts.rejectedRowCount == 0 ->
                StatementImportBatchState.COMPLETED

            else -> StatementImportBatchState.COMPLETED_WITH_ERRORS
        }
        val updatedAt = maxOf(now, current.updatedAt)
        check(
            dao.updateBatchSummary(
                id = current.id.value,
                processedRowCount = counts.processedRowCount,
                readyForReviewCount = counts.readyForReviewCount,
                rejectedRowCount = counts.rejectedRowCount,
                state = state.name,
                updatedAtEpochMillis = updatedAt.toEpochMilli(),
            ) == 1,
        ) { "Statement import batch disappeared during refresh" }
        return StatementImportBatchRecord(
            id = current.id,
            fileHash = current.fileHash,
            mappingHash = current.mappingHash,
            totalRowCount = current.totalRowCount,
            processedRowCount = counts.processedRowCount,
            readyForReviewCount = counts.readyForReviewCount,
            rejectedRowCount = counts.rejectedRowCount,
            state = state,
            createdAt = current.createdAt,
            updatedAt = updatedAt,
        )
    }

    private suspend fun ensureIntegrity() {
        if (
            dao.batchIntegrityIssueCount() != 0L ||
            dao.rowIntegrityIssueCount() != 0L ||
            dao.summaryIntegrityIssueCount() != 0L
        ) {
            throw LocalDataIntegrityException("statement import history")
        }
    }

    private suspend fun hasMatchingRawEvidence(row: StatementImportRowRecord): Boolean {
        if (row.state == StatementImportRowState.REJECTED) {
            return row.rawEventId == null
        }
        val rawEventId = row.rawEventId ?: return false
        val rawEvent = rawEventDao.findById(rawEventId.value) ?: return false
        return rawEvent.contentHash == row.rowFingerprint.value &&
            rawEvent.sourceFamily == SourceFamily.GENERIC.name &&
            rawEvent.connectorId == GenericDelimitedStatementIdentity.CONNECTOR_ID &&
            rawEvent.captureMethod == CaptureMethod.STATEMENT_IMPORT.name
    }
}

private fun StatementImportBatchRecord.toEntity() = StatementImportBatchEntity(
    id = id.value,
    fileHash = fileHash.value,
    mappingHash = mappingHash.value,
    totalRowCount = totalRowCount,
    processedRowCount = processedRowCount,
    readyForReviewCount = readyForReviewCount,
    rejectedRowCount = rejectedRowCount,
    state = state.name,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)

private fun StatementImportBatchEntity.toRecord(): StatementImportBatchRecord = try {
    StatementImportBatchRecord(
        id = StatementImportBatchId(id),
        fileHash = EvidenceHash(fileHash),
        mappingHash = EvidenceHash(mappingHash),
        totalRowCount = totalRowCount,
        processedRowCount = processedRowCount,
        readyForReviewCount = readyForReviewCount,
        rejectedRowCount = rejectedRowCount,
        state = StatementImportBatchState.valueOf(state),
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )
} catch (_: RuntimeException) {
    throw LocalDataIntegrityException("statement import batches")
}

private fun StatementImportRowRecord.toEntity() = StatementImportRowEntity(
    batchId = batchId.value,
    tableRowIndex = tableRowIndex,
    rowFingerprint = rowFingerprint.value,
    state = state.name,
    rawEventId = rawEventId?.value,
    errorCode = errorCode,
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)

private fun StatementImportRowEntity.toRecord(): StatementImportRowRecord = try {
    StatementImportRowRecord(
        batchId = StatementImportBatchId(batchId),
        tableRowIndex = tableRowIndex,
        rowFingerprint = EvidenceHash(rowFingerprint),
        state = StatementImportRowState.valueOf(state),
        rawEventId = rawEventId?.let(::RawEventId),
        errorCode = errorCode,
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )
} catch (_: RuntimeException) {
    throw LocalDataIntegrityException("statement import rows")
}

private fun StatementImportRowEntity.sameOutcome(record: StatementImportRowRecord): Boolean =
    batchId == record.batchId.value &&
        tableRowIndex == record.tableRowIndex &&
        rowFingerprint == record.rowFingerprint.value &&
        state == record.state.name &&
        rawEventId == record.rawEventId?.value &&
        errorCode == record.errorCode
