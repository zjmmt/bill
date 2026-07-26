package dev.bill.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "source_evidence_payloads",
    foreignKeys = [
        ForeignKey(
            entity = RawEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawEventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["payloadId"], unique = true),
        Index(value = ["state"]),
        Index(value = ["clearCommandId"], unique = true),
    ],
)
data class SourceEvidencePayloadEntity(
    @PrimaryKey val rawEventId: String,
    val payloadId: String,
    val payloadSizeBytes: Long?,
    val state: String,
    val clearCommandId: String?,
    val clearReason: String?,
    val clearRequestedAtEpochMillis: Long?,
    val clearedAtEpochMillis: Long?,
)

@Entity(tableName = "source_evidence_policy")
data class SourceEvidencePolicyEntity(
    @PrimaryKey val id: Int,
    val retentionDays: Int?,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "source_evidence_staging",
    indices = [
        Index(value = ["rawEventId"], unique = true),
        Index(value = ["state", "leaseExpiresAtEpochMillis"]),
    ],
)
data class SourceEvidenceStagingEntity(
    @PrimaryKey val payloadId: String,
    val rawEventId: String?,
    val contentHash: String?,
    val payloadSizeBytes: Long?,
    val state: String,
    val leaseId: String,
    val createdAtEpochMillis: Long,
    val leaseExpiresAtEpochMillis: Long,
)

data class SourceEvidenceStorageRow(
    val storedCount: Long,
    val storedBytes: Long,
    val unknownSizeCount: Long,
    val clearPendingCount: Long,
    val clearedCount: Long,
)

data class SourceEvidenceRow(
    val rawEventId: String,
    val payloadId: String,
    val rawPayloadReference: String,
    val sourceFamily: String,
    val captureMethod: String,
    val capturedAtEpochMillis: Long,
    val payloadSizeBytes: Long?,
    val state: String,
    val clearCommandId: String?,
    val clearReason: String?,
    val clearRequestedAtEpochMillis: Long?,
    val clearedAtEpochMillis: Long?,
    val pendingReviewCount: Long,
)

data class SourceEvidenceStagingStorageRow(
    val stagedCount: Long,
    val stagedBytes: Long,
    val unknownSizeCount: Long,
)
