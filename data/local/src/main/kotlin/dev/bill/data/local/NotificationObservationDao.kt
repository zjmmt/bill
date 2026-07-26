package dev.bill.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NotificationObservationDao {
    @Query(
        "SELECT * FROM notification_observations WHERE observationId = :observationId LIMIT 1",
    )
    suspend fun findByObservationId(observationId: String): NotificationObservationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: NotificationObservationEntity)

    @Query(
        """
        UPDATE notification_observations
        SET leaseId = :newLeaseId,
            leaseExpiresAtEpochMillis = :newExpiresAtEpochMillis
        WHERE observationId = :observationId
          AND state = 'ACTIVE'
          AND leaseId = :expectedLeaseId
          AND leaseExpiresAtEpochMillis <= :nowEpochMillis
        """,
    )
    suspend fun takeOverExpiredActive(
        observationId: String,
        expectedLeaseId: String,
        newLeaseId: String,
        newExpiresAtEpochMillis: Long,
        nowEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE notification_observations
        SET state = 'CAPTURED',
            capturedAtEpochMillis = :capturedAtEpochMillis
        WHERE observationId = :observationId
          AND state = 'ACTIVE'
          AND commandId = :commandId
          AND leaseId = :leaseId
        """,
    )
    suspend fun markCaptured(
        observationId: String,
        commandId: String,
        leaseId: String,
        capturedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        DELETE FROM notification_observations
        WHERE observationId = :observationId
          AND state = 'ACTIVE'
          AND commandId = :commandId
          AND leaseId = :leaseId
        """,
    )
    suspend fun deleteActive(
        observationId: String,
        commandId: String,
        leaseId: String,
    ): Int

    @Query(
        """
        DELETE FROM notification_observations
        WHERE observationId IN (
            SELECT observationId
            FROM notification_observations
            WHERE state = 'ACTIVE'
              AND leaseExpiresAtEpochMillis <= :leaseExpiresBeforeEpochMillis
            ORDER BY leaseExpiresAtEpochMillis ASC, observationId ASC
            LIMIT :limit
        )
        """,
    )
    suspend fun deleteExpiredActiveBefore(
        leaseExpiresBeforeEpochMillis: Long,
        limit: Int,
    ): Int

    @Query(
        """
        DELETE FROM notification_observations
        WHERE observationId IN (
            SELECT observationId
            FROM notification_observations
            WHERE state = 'CAPTURED'
              AND capturedAtEpochMillis <= :capturedBeforeEpochMillis
            ORDER BY capturedAtEpochMillis ASC, observationId ASC
            LIMIT :limit
        )
        """,
    )
    suspend fun deleteCapturedBefore(
        capturedBeforeEpochMillis: Long,
        limit: Int,
    ): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM notification_observations
        WHERE LENGTH(observationId) != 64
           OR observationId GLOB '*[^0-9a-f]*'
           OR TRIM(commandId) = ''
           OR TRIM(leaseId) = ''
           OR state NOT IN ('ACTIVE', 'CAPTURED')
           OR leaseExpiresAtEpochMillis <= createdAtEpochMillis
           OR (state = 'ACTIVE' AND capturedAtEpochMillis IS NOT NULL)
           OR (
               state = 'CAPTURED'
               AND (
                   capturedAtEpochMillis IS NULL
                   OR capturedAtEpochMillis < createdAtEpochMillis
               )
           )
        """,
    )
    suspend fun integrityIssueCount(): Long
}
