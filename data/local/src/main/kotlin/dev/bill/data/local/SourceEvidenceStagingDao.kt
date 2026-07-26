package dev.bill.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SourceEvidenceStagingDao {
    @Query(
        "SELECT * FROM source_evidence_staging WHERE payloadId = :payloadId LIMIT 1",
    )
    suspend fun findByPayloadId(payloadId: String): SourceEvidenceStagingEntity?

    @Query(
        "SELECT * FROM source_evidence_staging WHERE rawEventId = :rawEventId LIMIT 1",
    )
    suspend fun findByRawEventId(rawEventId: String): SourceEvidenceStagingEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SourceEvidenceStagingEntity)

    @Query(
        """
        UPDATE source_evidence_staging
        SET leaseId = :newLeaseId,
            leaseExpiresAtEpochMillis = :newExpiresAtEpochMillis
        WHERE payloadId = :payloadId
          AND state = 'ACTIVE'
          AND leaseId = :expectedLeaseId
        """,
    )
    suspend fun renewActive(
        payloadId: String,
        expectedLeaseId: String,
        newLeaseId: String,
        newExpiresAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE source_evidence_staging
        SET rawEventId = :rawEventId,
            contentHash = :contentHash,
            payloadSizeBytes = :payloadSizeBytes,
            state = 'ACTIVE',
            leaseId = :newLeaseId,
            createdAtEpochMillis = :createdAtEpochMillis,
            leaseExpiresAtEpochMillis = :newExpiresAtEpochMillis
        WHERE payloadId = :payloadId
          AND state = 'RECOVERY'
          AND leaseId = :expectedLeaseId
          AND leaseExpiresAtEpochMillis <= :nowEpochMillis
        """,
    )
    suspend fun takeOverExpiredRecovery(
        payloadId: String,
        expectedLeaseId: String,
        rawEventId: String,
        contentHash: String,
        payloadSizeBytes: Long,
        newLeaseId: String,
        createdAtEpochMillis: Long,
        newExpiresAtEpochMillis: Long,
        nowEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT * FROM source_evidence_staging
        WHERE leaseExpiresAtEpochMillis <= :nowEpochMillis
        ORDER BY leaseExpiresAtEpochMillis ASC, payloadId ASC
        LIMIT :limit
        """,
    )
    suspend fun expired(
        nowEpochMillis: Long,
        limit: Int,
    ): List<SourceEvidenceStagingEntity>

    @Query(
        """
        UPDATE source_evidence_staging
        SET state = 'RECOVERY',
            leaseId = :recoveryLeaseId,
            leaseExpiresAtEpochMillis = :recoveryExpiresAtEpochMillis
        WHERE payloadId = :payloadId
          AND leaseId = :expectedLeaseId
          AND leaseExpiresAtEpochMillis <= :nowEpochMillis
        """,
    )
    suspend fun claimExpired(
        payloadId: String,
        expectedLeaseId: String,
        recoveryLeaseId: String,
        recoveryExpiresAtEpochMillis: Long,
        nowEpochMillis: Long,
    ): Int

    @Query(
        """
        DELETE FROM source_evidence_staging
        WHERE payloadId = :payloadId
          AND rawEventId = :rawEventId
          AND contentHash = :contentHash
          AND payloadSizeBytes = :payloadSizeBytes
          AND state = 'ACTIVE'
          AND leaseId = :leaseId
        """,
    )
    suspend fun deleteActive(
        payloadId: String,
        rawEventId: String,
        contentHash: String,
        payloadSizeBytes: Long,
        leaseId: String,
    ): Int

    @Query(
        """
        DELETE FROM source_evidence_staging
        WHERE payloadId = :payloadId
          AND state = 'RECOVERY'
          AND leaseId = :leaseId
        """,
    )
    suspend fun deleteRecovery(
        payloadId: String,
        leaseId: String,
    ): Int

    @Query(
        """
        SELECT
            COUNT(*) AS stagedCount,
            COALESCE(SUM(payloadSizeBytes), 0) AS stagedBytes,
            COALESCE(SUM(CASE WHEN payloadSizeBytes IS NULL THEN 1 ELSE 0 END), 0)
                AS unknownSizeCount
        FROM source_evidence_staging
        """,
    )
    suspend fun storageSummary(): SourceEvidenceStagingStorageRow

    @Query(
        """
        SELECT payloadId
        FROM source_evidence_staging
        ORDER BY payloadId ASC
        LIMIT :limit
        """,
    )
    suspend fun payloadIds(limit: Int): List<String>

    @Query(
        """
        SELECT COUNT(*)
        FROM source_evidence_staging AS s
        WHERE TRIM(s.payloadId) = ''
           OR TRIM(s.leaseId) = ''
           OR s.state NOT IN ('ACTIVE', 'RECOVERY')
           OR s.leaseExpiresAtEpochMillis <= s.createdAtEpochMillis
           OR (
               s.state = 'ACTIVE'
               AND (
                   s.rawEventId IS NULL
                   OR TRIM(s.rawEventId) = ''
                   OR s.contentHash IS NULL
                   OR LENGTH(s.contentHash) != 64
                   OR s.contentHash GLOB '*[^0-9a-f]*'
                   OR s.payloadSizeBytes IS NULL
                   OR s.payloadSizeBytes < 0
               )
           )
           OR (
               s.state = 'RECOVERY'
               AND NOT (
                   (
                       s.rawEventId IS NULL
                       AND s.contentHash IS NULL
                       AND s.payloadSizeBytes IS NULL
                   )
                   OR
                   (
                       s.rawEventId IS NOT NULL
                       AND TRIM(s.rawEventId) != ''
                       AND s.contentHash IS NOT NULL
                       AND LENGTH(s.contentHash) = 64
                       AND s.contentHash NOT GLOB '*[^0-9a-f]*'
                       AND s.payloadSizeBytes IS NOT NULL
                       AND s.payloadSizeBytes >= 0
                   )
               )
           )
        """,
    )
    suspend fun integrityIssueCount(): Long
}
