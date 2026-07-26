package dev.bill.data.local

import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "parse_attempts",
    foreignKeys = [
        ForeignKey(
            entity = RawEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawEventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["rawEventId"]),
        Index(
            value = ["rawEventId", "parserId", "parserVersion", "ruleVersion"],
            unique = true,
        ),
    ],
)
data class ParseAttemptEntity(
    @PrimaryKey val id: String,
    val rawEventId: String,
    val parserId: String,
    val providerId: String,
    val sourceFamily: String,
    val connectorId: String,
    val parserVersion: String,
    val ruleVersion: String,
    val attemptedAtEpochMillis: Long,
    val outcome: String,
    val diagnosticCode: String?,
    val diagnosticRecoverable: Boolean?,
    val candidatePayload: ByteArray?,
    val resultFingerprint: String,
)

@Entity(
    tableName = "source_draft_proposals",
    foreignKeys = [
        ForeignKey(
            entity = ParseAttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["parseAttemptId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = RawEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawEventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["completedDraftId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["parseAttemptId"], unique = true),
        Index(value = ["rawEventId"]),
        Index(value = ["state", "createdAtEpochMillis"]),
        Index(value = ["completedDraftId"], unique = true),
    ],
)
data class SourceDraftProposalEntity(
    @PrimaryKey val id: String,
    val parseAttemptId: String,
    val rawEventId: String,
    val state: String,
    val createdAtEpochMillis: Long,
    val completedDraftId: String?,
)

@Entity(
    tableName = "draft_source_evidence",
    foreignKeys = [
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceDraftProposalEntity::class,
            parentColumns = ["id"],
            childColumns = ["proposalId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = RawEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawEventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ParseAttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["parseAttemptId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["proposalId"], unique = true),
        Index(value = ["rawEventId"]),
        Index(value = ["parseAttemptId"]),
    ],
)
data class DraftSourceEvidenceEntity(
    @PrimaryKey val draftId: String,
    val proposalId: String,
    val rawEventId: String,
    val parseAttemptId: String,
)

data class DraftWithSourceEvidence(
    @Embedded val draft: DraftEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "draftId",
    )
    val sourceEvidence: List<DraftSourceEvidenceEntity>,
)

data class SourceProposalRow(
    val proposalId: String,
    val proposalState: String,
    val proposalCreatedAtEpochMillis: Long,
    val rawEventId: String,
    val sourceFamily: String,
    val rawConnectorId: String,
    val captureMethod: String,
    val capturedAtEpochMillis: Long,
    val parseAttemptId: String,
    val attemptRawEventId: String,
    val parserId: String,
    val providerId: String,
    val attemptSourceFamily: String,
    val connectorId: String,
    val parserVersion: String,
    val ruleVersion: String,
    val parseOutcome: String,
    val diagnosticCode: String?,
    val diagnosticRecoverable: Boolean?,
    val candidatePayload: ByteArray?,
    val matchingObservationCount: Long,
)
