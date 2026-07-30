package dev.bill.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StatementImportDao {
    @Query("SELECT * FROM statement_import_batches WHERE id = :id LIMIT 1")
    suspend fun findBatch(id: String): StatementImportBatchEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(entity: StatementImportBatchEntity)

    @Query(
        """
        SELECT * FROM statement_import_rows
        WHERE batchId = :batchId
        ORDER BY tableRowIndex ASC
        """,
    )
    suspend fun listRows(batchId: String): List<StatementImportRowEntity>

    @Query(
        """
        SELECT * FROM statement_import_rows
        WHERE batchId = :batchId AND tableRowIndex = :tableRowIndex
        LIMIT 1
        """,
    )
    suspend fun findRow(
        batchId: String,
        tableRowIndex: Int,
    ): StatementImportRowEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRow(entity: StatementImportRowEntity)

    @Query(
        """
        UPDATE statement_import_batches
        SET processedRowCount = processedRowCount + 1,
            readyForReviewCount = readyForReviewCount + :readyForReviewDelta,
            rejectedRowCount = rejectedRowCount + :rejectedDelta,
            state = CASE
                WHEN processedRowCount + 1 < totalRowCount THEN 'IN_PROGRESS'
                WHEN rejectedRowCount + :rejectedDelta = 0 THEN 'COMPLETED'
                ELSE 'COMPLETED_WITH_ERRORS'
            END,
            updatedAtEpochMillis = MAX(updatedAtEpochMillis, :updatedAtEpochMillis)
        WHERE id = :id
          AND processedRowCount < totalRowCount
        """,
    )
    suspend fun incrementBatchSummary(
        id: String,
        readyForReviewDelta: Int,
        rejectedDelta: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT
            COUNT(*) AS processedRowCount,
            COALESCE(SUM(CASE WHEN state = 'READY_FOR_REVIEW' THEN 1 ELSE 0 END), 0)
                AS readyForReviewCount,
            COALESCE(SUM(CASE WHEN state = 'REJECTED' THEN 1 ELSE 0 END), 0)
                AS rejectedRowCount
        FROM statement_import_rows
        WHERE batchId = :batchId
        """,
    )
    suspend fun rowCounts(batchId: String): StatementImportRowCounts

    @Query(
        """
        UPDATE statement_import_batches
        SET processedRowCount = :processedRowCount,
            readyForReviewCount = :readyForReviewCount,
            rejectedRowCount = :rejectedRowCount,
            state = :state,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
        """,
    )
    suspend fun updateBatchSummary(
        id: String,
        processedRowCount: Int,
        readyForReviewCount: Int,
        rejectedRowCount: Int,
        state: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM statement_import_batches
        WHERE LENGTH(fileHash) != 64
           OR fileHash GLOB '*[^0-9a-f]*'
           OR LENGTH(mappingHash) != 64
           OR mappingHash GLOB '*[^0-9a-f]*'
           OR totalRowCount < 1
           OR totalRowCount > 5000
           OR processedRowCount < 0
           OR processedRowCount > totalRowCount
           OR readyForReviewCount < 0
           OR rejectedRowCount < 0
           OR readyForReviewCount + rejectedRowCount != processedRowCount
           OR state NOT IN ('IN_PROGRESS', 'COMPLETED', 'COMPLETED_WITH_ERRORS')
           OR updatedAtEpochMillis < createdAtEpochMillis
           OR (state = 'IN_PROGRESS' AND processedRowCount >= totalRowCount)
           OR (
               state = 'COMPLETED'
               AND (processedRowCount != totalRowCount OR rejectedRowCount != 0)
           )
           OR (
               state = 'COMPLETED_WITH_ERRORS'
               AND (processedRowCount != totalRowCount OR rejectedRowCount = 0)
           )
        """,
    )
    suspend fun batchIntegrityIssueCount(): Long

    @Query(
        """
        SELECT COUNT(*)
        FROM statement_import_rows AS import_row
        LEFT JOIN statement_import_batches AS batch ON batch.id = import_row.batchId
        LEFT JOIN raw_events AS raw_event ON raw_event.id = import_row.rawEventId
        WHERE batch.id IS NULL
           OR import_row.tableRowIndex < 1
           OR import_row.tableRowIndex > batch.totalRowCount
           OR LENGTH(import_row.rowFingerprint) != 64
           OR import_row.rowFingerprint GLOB '*[^0-9a-f]*'
           OR import_row.state NOT IN ('READY_FOR_REVIEW', 'REJECTED')
           OR (
               import_row.state = 'READY_FOR_REVIEW'
               AND (
                   import_row.rawEventId IS NULL
                   OR import_row.errorCode IS NOT NULL
                   OR raw_event.id IS NULL
                   OR raw_event.contentHash != import_row.rowFingerprint
                   OR raw_event.sourceFamily != 'GENERIC'
                   OR raw_event.connectorId != 'android-saf-delimited-row'
                   OR raw_event.captureMethod != 'STATEMENT_IMPORT'
               )
           )
           OR (
               import_row.state = 'REJECTED'
               AND (import_row.rawEventId IS NOT NULL OR import_row.errorCode IS NULL)
           )
           OR (
               import_row.errorCode IS NOT NULL
               AND (
                   TRIM(import_row.errorCode) = ''
                   OR LENGTH(import_row.errorCode) > 128
                   OR import_row.errorCode GLOB '*[^A-Za-z0-9._-]*'
               )
           )
        """,
    )
    suspend fun rowIntegrityIssueCount(): Long

    @Query(
        """
        SELECT COUNT(*)
        FROM statement_import_batches AS batch
        WHERE batch.processedRowCount != (
                SELECT COUNT(*)
                FROM statement_import_rows
                WHERE batchId = batch.id
            )
           OR batch.readyForReviewCount != (
                SELECT COUNT(*)
                FROM statement_import_rows
                WHERE batchId = batch.id AND state = 'READY_FOR_REVIEW'
            )
           OR batch.rejectedRowCount != (
                SELECT COUNT(*)
                FROM statement_import_rows
                WHERE batchId = batch.id AND state = 'REJECTED'
            )
        """,
    )
    suspend fun summaryIntegrityIssueCount(): Long
}
