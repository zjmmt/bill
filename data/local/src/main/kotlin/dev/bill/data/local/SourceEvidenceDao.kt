package dev.bill.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPayload(payload: SourceEvidencePayloadEntity)

    @Query("SELECT * FROM source_evidence_payloads WHERE rawEventId = :rawEventId")
    suspend fun findPayload(rawEventId: String): SourceEvidencePayloadEntity?

    @Query("SELECT * FROM source_evidence_payloads WHERE payloadId = :payloadId")
    suspend fun findPayloadByPayloadId(payloadId: String): SourceEvidencePayloadEntity?

    @Query(
        """
        SELECT payloadId
        FROM source_evidence_payloads
        WHERE state != 'CLEARED'
        ORDER BY payloadId ASC
        LIMIT :limit
        """,
    )
    suspend fun storedPayloadIds(limit: Int): List<String>

    @Query("SELECT * FROM source_evidence_policy WHERE id = 1")
    fun observePolicy(): Flow<SourceEvidencePolicyEntity?>

    @Query("SELECT * FROM source_evidence_policy WHERE id = 1")
    suspend fun findPolicy(): SourceEvidencePolicyEntity?

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN state != 'CLEARED' THEN 1 ELSE 0 END), 0)
                AS storedCount,
            COALESCE(SUM(
                CASE WHEN state != 'CLEARED' THEN COALESCE(payloadSizeBytes, 0) ELSE 0 END
            ), 0) AS storedBytes,
            COALESCE(SUM(
                CASE WHEN state != 'CLEARED' AND payloadSizeBytes IS NULL THEN 1 ELSE 0 END
            ), 0) AS unknownSizeCount,
            COALESCE(SUM(CASE WHEN state = 'CLEAR_PENDING' THEN 1 ELSE 0 END), 0)
                AS clearPendingCount,
            COALESCE(SUM(CASE WHEN state = 'CLEARED' THEN 1 ELSE 0 END), 0)
                AS clearedCount
        FROM source_evidence_payloads
        """,
    )
    fun observeStorageSummary(): Flow<SourceEvidenceStorageRow>

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN state != 'CLEARED' THEN 1 ELSE 0 END), 0)
                AS storedCount,
            COALESCE(SUM(
                CASE WHEN state != 'CLEARED' THEN COALESCE(payloadSizeBytes, 0) ELSE 0 END
            ), 0) AS storedBytes,
            COALESCE(SUM(
                CASE WHEN state != 'CLEARED' AND payloadSizeBytes IS NULL THEN 1 ELSE 0 END
            ), 0) AS unknownSizeCount,
            COALESCE(SUM(CASE WHEN state = 'CLEAR_PENDING' THEN 1 ELSE 0 END), 0)
                AS clearPendingCount,
            COALESCE(SUM(CASE WHEN state = 'CLEARED' THEN 1 ELSE 0 END), 0)
                AS clearedCount
        FROM source_evidence_payloads
        """,
    )
    suspend fun storageSummary(): SourceEvidenceStorageRow

    @Query(
        """
        SELECT
            r.id AS rawEventId,
            e.payloadId AS payloadId,
            r.payloadReference AS rawPayloadReference,
            r.sourceFamily AS sourceFamily,
            r.captureMethod AS captureMethod,
            r.capturedAtEpochMillis AS capturedAtEpochMillis,
            e.payloadSizeBytes AS payloadSizeBytes,
            e.state AS state,
            e.clearCommandId AS clearCommandId,
            e.clearReason AS clearReason,
            e.clearRequestedAtEpochMillis AS clearRequestedAtEpochMillis,
            e.clearedAtEpochMillis AS clearedAtEpochMillis,
            (SELECT COUNT(*)
             FROM source_draft_proposals AS p
             WHERE p.rawEventId = r.id
               AND p.state = 'WAITING_USER') AS pendingReviewCount
        FROM source_evidence_payloads AS e
        INNER JOIN raw_events AS r ON r.id = e.rawEventId
        WHERE :beforeCapturedAtEpochMillis IS NULL
           OR r.capturedAtEpochMillis < :beforeCapturedAtEpochMillis
           OR (
               r.capturedAtEpochMillis = :beforeCapturedAtEpochMillis
               AND r.id < :beforeRawEventId
           )
        ORDER BY r.capturedAtEpochMillis DESC, r.id DESC
        LIMIT :limit
        """,
    )
    suspend fun pageRows(
        limit: Int,
        beforeCapturedAtEpochMillis: Long?,
        beforeRawEventId: String?,
    ): List<SourceEvidenceRow>

    @Query(
        """
        SELECT
            r.id AS rawEventId,
            e.payloadId AS payloadId,
            r.payloadReference AS rawPayloadReference,
            r.sourceFamily AS sourceFamily,
            r.captureMethod AS captureMethod,
            r.capturedAtEpochMillis AS capturedAtEpochMillis,
            e.payloadSizeBytes AS payloadSizeBytes,
            e.state AS state,
            e.clearCommandId AS clearCommandId,
            e.clearReason AS clearReason,
            e.clearRequestedAtEpochMillis AS clearRequestedAtEpochMillis,
            e.clearedAtEpochMillis AS clearedAtEpochMillis,
            (SELECT COUNT(*)
             FROM source_draft_proposals AS p
             WHERE p.rawEventId = r.id
               AND p.state = 'WAITING_USER') AS pendingReviewCount
        FROM source_evidence_payloads AS e
        INNER JOIN raw_events AS r ON r.id = e.rawEventId
        WHERE e.rawEventId = :rawEventId
        LIMIT 1
        """,
    )
    suspend fun findRow(rawEventId: String): SourceEvidenceRow?

    @Query(
        """
        SELECT
            r.id AS rawEventId,
            e.payloadId AS payloadId,
            r.payloadReference AS rawPayloadReference,
            r.sourceFamily AS sourceFamily,
            r.captureMethod AS captureMethod,
            r.capturedAtEpochMillis AS capturedAtEpochMillis,
            e.payloadSizeBytes AS payloadSizeBytes,
            e.state AS state,
            e.clearCommandId AS clearCommandId,
            e.clearReason AS clearReason,
            e.clearRequestedAtEpochMillis AS clearRequestedAtEpochMillis,
            e.clearedAtEpochMillis AS clearedAtEpochMillis,
            0 AS pendingReviewCount
        FROM source_evidence_payloads AS e
        INNER JOIN raw_events AS r ON r.id = e.rawEventId
        WHERE e.state = 'AVAILABLE'
          AND r.capturedAtEpochMillis < :capturedBeforeEpochMillis
          AND NOT EXISTS (
              SELECT 1
              FROM source_draft_proposals AS p
              WHERE p.rawEventId = r.id
                AND p.state = 'WAITING_USER'
          )
        ORDER BY r.capturedAtEpochMillis ASC, r.id ASC
        LIMIT :limit
        """,
    )
    suspend fun retentionCandidateRows(
        capturedBeforeEpochMillis: Long,
        limit: Int,
    ): List<SourceEvidenceRow>

    @Query(
        """
        SELECT
            r.id AS rawEventId,
            e.payloadId AS payloadId,
            r.payloadReference AS rawPayloadReference,
            r.sourceFamily AS sourceFamily,
            r.captureMethod AS captureMethod,
            r.capturedAtEpochMillis AS capturedAtEpochMillis,
            e.payloadSizeBytes AS payloadSizeBytes,
            e.state AS state,
            e.clearCommandId AS clearCommandId,
            e.clearReason AS clearReason,
            e.clearRequestedAtEpochMillis AS clearRequestedAtEpochMillis,
            e.clearedAtEpochMillis AS clearedAtEpochMillis,
            0 AS pendingReviewCount
        FROM source_evidence_payloads AS e
        INNER JOIN raw_events AS r ON r.id = e.rawEventId
        WHERE e.state = 'AVAILABLE'
          AND NOT EXISTS (
              SELECT 1
              FROM source_draft_proposals AS p
              WHERE p.rawEventId = r.id
                AND p.state = 'WAITING_USER'
          )
        ORDER BY r.capturedAtEpochMillis ASC, r.id ASC
        LIMIT :limit
        """,
    )
    suspend fun capacityCandidateRows(limit: Int): List<SourceEvidenceRow>

    @Query(
        """
        SELECT
            r.id AS rawEventId,
            e.payloadId AS payloadId,
            r.payloadReference AS rawPayloadReference,
            r.sourceFamily AS sourceFamily,
            r.captureMethod AS captureMethod,
            r.capturedAtEpochMillis AS capturedAtEpochMillis,
            e.payloadSizeBytes AS payloadSizeBytes,
            e.state AS state,
            e.clearCommandId AS clearCommandId,
            e.clearReason AS clearReason,
            e.clearRequestedAtEpochMillis AS clearRequestedAtEpochMillis,
            e.clearedAtEpochMillis AS clearedAtEpochMillis,
            (SELECT COUNT(*)
             FROM source_draft_proposals AS p
             WHERE p.rawEventId = r.id
               AND p.state = 'WAITING_USER') AS pendingReviewCount
        FROM source_evidence_payloads AS e
        INNER JOIN raw_events AS r ON r.id = e.rawEventId
        WHERE e.state != 'CLEARED'
          AND e.payloadSizeBytes IS NULL
        ORDER BY r.capturedAtEpochMillis ASC, r.id ASC
        LIMIT :limit
        """,
    )
    suspend fun unknownSizeRows(limit: Int): List<SourceEvidenceRow>

    @Query(
        """
        UPDATE source_evidence_payloads
        SET state = 'CLEAR_PENDING',
            clearCommandId = :commandId,
            clearReason = :reason,
            clearRequestedAtEpochMillis = :requestedAtEpochMillis,
            clearedAtEpochMillis = NULL
        WHERE rawEventId = :rawEventId
          AND state = 'AVAILABLE'
          AND clearCommandId IS NULL
          AND clearReason IS NULL
          AND clearRequestedAtEpochMillis IS NULL
          AND clearedAtEpochMillis IS NULL
        """,
    )
    suspend fun requestClear(
        rawEventId: String,
        commandId: String,
        reason: String,
        requestedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE source_draft_proposals
        SET state = 'DISMISSED'
        WHERE rawEventId = :rawEventId
          AND state = 'WAITING_USER'
          AND completedDraftId IS NULL
        """,
    )
    suspend fun dismissPendingProposals(rawEventId: String): Int

    @Query(
        """
        UPDATE source_evidence_payloads
        SET state = 'CLEARED',
            clearedAtEpochMillis = :clearedAtEpochMillis
        WHERE rawEventId = :rawEventId
          AND state = 'CLEAR_PENDING'
          AND clearCommandId = :commandId
          AND clearReason IS NOT NULL
          AND clearRequestedAtEpochMillis IS NOT NULL
          AND clearedAtEpochMillis IS NULL
        """,
    )
    suspend fun markCleared(
        rawEventId: String,
        commandId: String,
        clearedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT
            r.id AS rawEventId,
            e.payloadId AS payloadId,
            r.payloadReference AS rawPayloadReference,
            r.sourceFamily AS sourceFamily,
            r.captureMethod AS captureMethod,
            r.capturedAtEpochMillis AS capturedAtEpochMillis,
            e.payloadSizeBytes AS payloadSizeBytes,
            e.state AS state,
            e.clearCommandId AS clearCommandId,
            e.clearReason AS clearReason,
            e.clearRequestedAtEpochMillis AS clearRequestedAtEpochMillis,
            e.clearedAtEpochMillis AS clearedAtEpochMillis,
            0 AS pendingReviewCount
        FROM source_evidence_payloads AS e
        INNER JOIN raw_events AS r ON r.id = e.rawEventId
        WHERE e.state = 'CLEAR_PENDING'
        ORDER BY e.clearRequestedAtEpochMillis ASC, e.rawEventId ASC
        LIMIT :limit
        """,
    )
    suspend fun pendingClearRows(limit: Int): List<SourceEvidenceRow>

    @Query(
        """
        UPDATE source_evidence_payloads
        SET payloadSizeBytes = :payloadSizeBytes
        WHERE rawEventId = :rawEventId
          AND state = 'AVAILABLE'
          AND payloadSizeBytes IS NULL
        """,
    )
    suspend fun recordMeasuredSize(rawEventId: String, payloadSizeBytes: Long): Int

    @Query(
        """
        UPDATE source_evidence_policy
        SET retentionDays = :retentionDays,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = 1
        """,
    )
    suspend fun updateRetentionPolicy(
        retentionDays: Int?,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT
            (SELECT COUNT(*)
             FROM raw_events AS r
             WHERE (SELECT COUNT(*)
                    FROM source_evidence_payloads AS e
                    WHERE e.rawEventId = r.id) != 1)
            +
            (SELECT COUNT(*)
             FROM source_evidence_payloads AS e
             WHERE NOT EXISTS (
                 SELECT 1
                 FROM raw_events AS r
                 WHERE r.id = e.rawEventId
             ))
            +
            (SELECT COUNT(*)
             FROM source_evidence_payloads AS e
             INNER JOIN raw_events AS r ON r.id = e.rawEventId
             WHERE TRIM(e.payloadId) = ''
                OR e.payloadId != r.payloadReference
                OR (e.payloadSizeBytes IS NOT NULL AND e.payloadSizeBytes < 0)
                OR e.state NOT IN ('AVAILABLE', 'CLEAR_PENDING', 'CLEARED')
                OR (
                    e.state = 'AVAILABLE'
                    AND (
                        e.clearCommandId IS NOT NULL
                        OR e.clearReason IS NOT NULL
                        OR e.clearRequestedAtEpochMillis IS NOT NULL
                        OR e.clearedAtEpochMillis IS NOT NULL
                    )
                )
                OR (
                    e.state = 'CLEAR_PENDING'
                    AND (
                        e.clearCommandId IS NULL
                        OR TRIM(e.clearCommandId) = ''
                        OR e.clearReason NOT IN (
                            'USER_REQUEST',
                            'RETENTION',
                            'CAPACITY',
                            'MISSING_PAYLOAD'
                        )
                        OR e.clearRequestedAtEpochMillis IS NULL
                        OR e.clearedAtEpochMillis IS NOT NULL
                    )
                )
                OR (
                    e.state = 'CLEARED'
                    AND (
                        e.clearCommandId IS NULL
                        OR TRIM(e.clearCommandId) = ''
                        OR e.clearReason NOT IN (
                            'USER_REQUEST',
                            'RETENTION',
                            'CAPACITY',
                            'MISSING_PAYLOAD'
                        )
                        OR e.clearRequestedAtEpochMillis IS NULL
                        OR e.clearedAtEpochMillis IS NULL
                        OR e.clearedAtEpochMillis < e.clearRequestedAtEpochMillis
                    )
                )
                OR (
                    e.state != 'AVAILABLE'
                    AND EXISTS (
                        SELECT 1
                        FROM source_draft_proposals AS p
                        WHERE p.rawEventId = e.rawEventId
                          AND p.state = 'WAITING_USER'
                    )
                )
                OR (
                    e.state != 'AVAILABLE'
                    AND (
                        SELECT COUNT(*)
                        FROM command_receipts AS c
                        WHERE c.commandId = e.clearCommandId
                          AND c.operation = 'REQUEST_SOURCE_EVIDENCE_CLEAR'
                          AND c.targetId = e.rawEventId
                          AND c.resultEntityId = e.rawEventId
                    ) != 1
                )
                OR (
                    e.state != 'AVAILABLE'
                    AND (
                        SELECT COUNT(*)
                        FROM audit_events AS a
                        WHERE a.commandId = e.clearCommandId
                          AND a.action = 'SOURCE_EVIDENCE_CLEAR_REQUESTED'
                          AND a.entityType = 'source_evidence'
                          AND a.entityId = e.rawEventId
                    ) != 1
                )
                OR (
                    e.state = 'CLEAR_PENDING'
                    AND EXISTS (
                        SELECT 1
                        FROM audit_events AS a
                        WHERE a.commandId = e.clearCommandId
                          AND a.action = 'SOURCE_EVIDENCE_CLEARED'
                          AND a.entityType = 'source_evidence'
                          AND a.entityId = e.rawEventId
                    )
                )
                OR (
                    e.state = 'CLEARED'
                    AND (
                        SELECT COUNT(*)
                        FROM audit_events AS a
                        WHERE a.commandId = e.clearCommandId
                          AND a.action = 'SOURCE_EVIDENCE_CLEARED'
                          AND a.entityType = 'source_evidence'
                          AND a.entityId = e.rawEventId
                    ) != 1
                ))
            +
            (SELECT
                CASE
                    WHEN COUNT(*) != 1 THEN 1
                    ELSE SUM(
                        CASE
                            WHEN id != 1
                              OR (retentionDays IS NOT NULL
                                  AND retentionDays NOT IN (7, 30, 90))
                            THEN 1
                            ELSE 0
                        END
                    )
                END
             FROM source_evidence_policy)
        """,
    )
    fun observeEvidenceIntegrityIssueCount(): Flow<Long>

    @Query(
        """
        SELECT
            (SELECT COUNT(*)
             FROM raw_events AS r
             WHERE (SELECT COUNT(*)
                    FROM source_evidence_payloads AS e
                    WHERE e.rawEventId = r.id) != 1)
            +
            (SELECT COUNT(*)
             FROM source_evidence_payloads AS e
             WHERE NOT EXISTS (
                 SELECT 1
                 FROM raw_events AS r
                 WHERE r.id = e.rawEventId
             ))
            +
            (SELECT COUNT(*)
             FROM source_evidence_payloads AS e
             INNER JOIN raw_events AS r ON r.id = e.rawEventId
             WHERE TRIM(e.payloadId) = ''
                OR e.payloadId != r.payloadReference
                OR (e.payloadSizeBytes IS NOT NULL AND e.payloadSizeBytes < 0)
                OR e.state NOT IN ('AVAILABLE', 'CLEAR_PENDING', 'CLEARED')
                OR (
                    e.state = 'AVAILABLE'
                    AND (
                        e.clearCommandId IS NOT NULL
                        OR e.clearReason IS NOT NULL
                        OR e.clearRequestedAtEpochMillis IS NOT NULL
                        OR e.clearedAtEpochMillis IS NOT NULL
                    )
                )
                OR (
                    e.state = 'CLEAR_PENDING'
                    AND (
                        e.clearCommandId IS NULL
                        OR TRIM(e.clearCommandId) = ''
                        OR e.clearReason NOT IN (
                            'USER_REQUEST',
                            'RETENTION',
                            'CAPACITY',
                            'MISSING_PAYLOAD'
                        )
                        OR e.clearRequestedAtEpochMillis IS NULL
                        OR e.clearedAtEpochMillis IS NOT NULL
                    )
                )
                OR (
                    e.state = 'CLEARED'
                    AND (
                        e.clearCommandId IS NULL
                        OR TRIM(e.clearCommandId) = ''
                        OR e.clearReason NOT IN (
                            'USER_REQUEST',
                            'RETENTION',
                            'CAPACITY',
                            'MISSING_PAYLOAD'
                        )
                        OR e.clearRequestedAtEpochMillis IS NULL
                        OR e.clearedAtEpochMillis IS NULL
                        OR e.clearedAtEpochMillis < e.clearRequestedAtEpochMillis
                    )
                )
                OR (
                    e.state != 'AVAILABLE'
                    AND EXISTS (
                        SELECT 1
                        FROM source_draft_proposals AS p
                        WHERE p.rawEventId = e.rawEventId
                          AND p.state = 'WAITING_USER'
                    )
                )
                OR (
                    e.state != 'AVAILABLE'
                    AND (
                        SELECT COUNT(*)
                        FROM command_receipts AS c
                        WHERE c.commandId = e.clearCommandId
                          AND c.operation = 'REQUEST_SOURCE_EVIDENCE_CLEAR'
                          AND c.targetId = e.rawEventId
                          AND c.resultEntityId = e.rawEventId
                    ) != 1
                )
                OR (
                    e.state != 'AVAILABLE'
                    AND (
                        SELECT COUNT(*)
                        FROM audit_events AS a
                        WHERE a.commandId = e.clearCommandId
                          AND a.action = 'SOURCE_EVIDENCE_CLEAR_REQUESTED'
                          AND a.entityType = 'source_evidence'
                          AND a.entityId = e.rawEventId
                    ) != 1
                )
                OR (
                    e.state = 'CLEAR_PENDING'
                    AND EXISTS (
                        SELECT 1
                        FROM audit_events AS a
                        WHERE a.commandId = e.clearCommandId
                          AND a.action = 'SOURCE_EVIDENCE_CLEARED'
                          AND a.entityType = 'source_evidence'
                          AND a.entityId = e.rawEventId
                    )
                )
                OR (
                    e.state = 'CLEARED'
                    AND (
                        SELECT COUNT(*)
                        FROM audit_events AS a
                        WHERE a.commandId = e.clearCommandId
                          AND a.action = 'SOURCE_EVIDENCE_CLEARED'
                          AND a.entityType = 'source_evidence'
                          AND a.entityId = e.rawEventId
                    ) != 1
                ))
            +
            (SELECT
                CASE
                    WHEN COUNT(*) != 1 THEN 1
                    ELSE SUM(
                        CASE
                            WHEN id != 1
                              OR (retentionDays IS NOT NULL
                                  AND retentionDays NOT IN (7, 30, 90))
                            THEN 1
                            ELSE 0
                        END
                    )
                END
             FROM source_evidence_policy)
        """,
    )
    suspend fun evidenceIntegrityIssueCount(): Long
}
