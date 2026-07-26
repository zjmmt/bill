package dev.bill.source.review

import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.RepositoryWriteResult
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import java.time.Instant
import kotlinx.coroutines.flow.Flow

enum class EvidencePayloadState {
    AVAILABLE,
    CLEAR_PENDING,
    CLEARED,
}

enum class EvidenceClearReason {
    USER_REQUEST,
    RETENTION,
    CAPACITY,
    MISSING_PAYLOAD,
}

data class SourceEvidencePolicy(
    val retentionDays: Int?,
    val updatedAt: Instant,
) {
    init {
        require(retentionDays == null || retentionDays in AllowedRetentionDays) {
            "Retention must be one of the supported day values or forever"
        }
    }

    companion object {
        val AllowedRetentionDays = setOf(7, 30, 90)
    }
}

data class SourceEvidenceStorageSummary(
    val storedCount: Long,
    val storedBytes: Long,
    val unknownSizeCount: Long,
    val clearPendingCount: Long,
    val clearedCount: Long,
) {
    init {
        require(
            storedCount >= 0L &&
                storedBytes >= 0L &&
                unknownSizeCount >= 0L &&
                clearPendingCount >= 0L &&
                clearedCount >= 0L,
        ) {
            "Evidence storage counts cannot be negative"
        }
        require(unknownSizeCount <= storedCount) {
            "Unknown-size evidence must still occupy storage"
        }
        require(clearPendingCount <= storedCount) {
            "Pending-clear evidence must still occupy storage"
        }
    }
}

data class SourceEvidenceOverview(
    val policy: SourceEvidencePolicy,
    val storage: SourceEvidenceStorageSummary,
)

data class SourceEvidenceItem(
    val rawEventId: RawEventId,
    val payloadId: PayloadId,
    val sourceFamily: SourceFamily,
    val captureMethod: CaptureMethod,
    val capturedAt: Instant,
    val payloadSizeBytes: Long?,
    val state: EvidencePayloadState,
    val hasPendingReview: Boolean,
    val clearReason: EvidenceClearReason?,
    val clearedAt: Instant?,
) {
    init {
        require(payloadSizeBytes == null || payloadSizeBytes >= 0L) {
            "Evidence payload size cannot be negative"
        }
        require((state == EvidencePayloadState.CLEARED) == (clearedAt != null)) {
            "Only cleared evidence has a cleared timestamp"
        }
        require(
            (state == EvidencePayloadState.AVAILABLE && clearReason == null) ||
                (state != EvidencePayloadState.AVAILABLE && clearReason != null),
        ) {
            "Evidence clear reason must match lifecycle state"
        }
        require(state == EvidencePayloadState.AVAILABLE || !hasPendingReview) {
            "Unavailable evidence cannot remain pending review"
        }
    }
}

data class SourceEvidenceCursor(
    val capturedAt: Instant,
    val rawEventId: RawEventId,
)

data class SourceEvidencePage(
    val items: List<SourceEvidenceItem>,
    val nextCursor: SourceEvidenceCursor?,
)

data class EvidenceClearWork(
    val rawEventId: RawEventId,
    val payloadId: PayloadId,
    val commandId: CommandId,
    val reason: EvidenceClearReason,
    val requestedAt: Instant,
)

enum class EvidenceClearRequestStatus {
    REQUESTED,
    ALREADY_PENDING,
    ALREADY_CLEARED,
    NOT_FOUND,
    COMMAND_COLLISION,
    INVALID_STATE,
}

data class EvidenceClearRequestResult(
    val status: EvidenceClearRequestStatus,
    val work: EvidenceClearWork? = null,
)

sealed interface EvidenceDeleteResult {
    data object DeletedOrAbsent : EvidenceDeleteResult
    data object Failed : EvidenceDeleteResult
}

sealed interface EvidenceMeasureResult {
    data class Found(val payloadSizeBytes: Long) : EvidenceMeasureResult {
        init {
            require(payloadSizeBytes >= 0L)
        }
    }

    data object NotFound : EvidenceMeasureResult
    data object Failed : EvidenceMeasureResult
}

@JvmInline
value class EvidenceStagingLeaseId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            "Evidence staging lease id must be an opaque ASCII token"
        }
    }
}

data class EvidenceStagingReservation(
    val rawEventId: RawEventId,
    val payloadId: PayloadId,
    val contentHash: EvidenceHash,
    val payloadSizeBytes: Long,
    val leaseId: EvidenceStagingLeaseId,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(payloadSizeBytes >= 0L) {
            "Staged evidence size cannot be negative"
        }
        require(expiresAt.isAfter(createdAt)) {
            "Evidence staging lease must expire after it is created"
        }
    }
}

data class EvidenceStagingStorageSummary(
    val stagedCount: Long,
    val stagedBytes: Long,
    val unknownSizeCount: Long,
) {
    init {
        require(stagedCount >= 0L && stagedBytes >= 0L && unknownSizeCount >= 0L) {
            "Evidence staging storage counts cannot be negative"
        }
        require(unknownSizeCount <= stagedCount) {
            "Unknown staging entries must still occupy storage"
        }
    }
}

enum class EvidenceStagingReserveStatus {
    RESERVED,
    ALREADY_RESERVED,
    ALREADY_TRACKED,
    ID_COLLISION,
    RECOVERY_IN_PROGRESS,
    DISABLED,
}

data class EvidenceStagingReserveResult(
    val status: EvidenceStagingReserveStatus,
    val reservation: EvidenceStagingReservation? = null,
) {
    init {
        require(
            (status == EvidenceStagingReserveStatus.RESERVED ||
                status == EvidenceStagingReserveStatus.ALREADY_RESERVED) ==
                (reservation != null),
        ) {
            "Only a successful staging reservation returns a lease"
        }
    }
}

data class EvidenceStagingExpiredLease(
    val payloadId: PayloadId,
    val leaseId: EvidenceStagingLeaseId,
    val expiresAt: Instant,
)

data class EvidenceStagingRecoveryWork(
    val payloadId: PayloadId,
    val leaseId: EvidenceStagingLeaseId,
)

enum class EvidenceStagingClaimStatus {
    CLAIMED,
    TRACKED_FINALIZED,
    STALE,
    INVALID_STATE,
}

data class EvidenceStagingClaimResult(
    val status: EvidenceStagingClaimStatus,
    val work: EvidenceStagingRecoveryWork? = null,
) {
    init {
        require(
            (status == EvidenceStagingClaimStatus.CLAIMED) == (work != null),
        ) {
            "Only a claimed staging entry returns recovery work"
        }
    }
}

enum class EvidenceOrphanClaimStatus {
    CLAIMED,
    TRACKED,
    RESERVED,
    INVALID_STATE,
}

data class EvidenceOrphanClaimResult(
    val status: EvidenceOrphanClaimStatus,
    val work: EvidenceStagingRecoveryWork? = null,
) {
    init {
        require(
            (status == EvidenceOrphanClaimStatus.CLAIMED) == (work != null),
        ) {
            "Only a claimed orphan returns recovery work"
        }
    }
}

data class EvidenceArtifactScanResult(
    val payloadIds: List<PayloadId>,
    val inspectedEntryCount: Int,
    val truncated: Boolean,
) {
    init {
        require(inspectedEntryCount >= 0)
        require(payloadIds.distinct().size == payloadIds.size)
    }
}

data class EvidenceKnownPayloadIds(
    val payloadIds: Set<PayloadId>,
    val truncated: Boolean,
)

interface EvidenceLifecycleStore {
    suspend fun delete(payloadId: PayloadId): EvidenceDeleteResult

    suspend fun measure(
        payloadId: PayloadId,
        maxBytes: Long,
    ): EvidenceMeasureResult
}

interface EvidenceArtifactStore {
    suspend fun scanOrphanCandidates(
        olderThanOrEqualTo: Instant,
        excludedPayloadIds: Set<PayloadId>,
        limit: Int,
        inspectionLimit: Int,
    ): EvidenceArtifactScanResult

    data object Empty : EvidenceArtifactStore {
        override suspend fun scanOrphanCandidates(
            olderThanOrEqualTo: Instant,
            excludedPayloadIds: Set<PayloadId>,
            limit: Int,
            inspectionLimit: Int,
        ): EvidenceArtifactScanResult = EvidenceArtifactScanResult(
            payloadIds = emptyList(),
            inspectedEntryCount = 0,
            truncated = false,
        )
    }
}

interface SourceEvidenceStagingRepository {
    suspend fun reserve(
        requested: EvidenceStagingReservation,
        now: Instant,
    ): EvidenceStagingReserveResult

    suspend fun storageSummary(): EvidenceStagingStorageSummary

    suspend fun knownPayloadIds(limit: Int): EvidenceKnownPayloadIds

    suspend fun expiredLeases(
        now: Instant,
        limit: Int,
    ): List<EvidenceStagingExpiredLease>

    suspend fun claimExpired(
        expected: EvidenceStagingExpiredLease,
        recoveryLeaseId: EvidenceStagingLeaseId,
        claimedAt: Instant,
        expiresAt: Instant,
    ): EvidenceStagingClaimResult

    suspend fun claimOrphan(
        payloadId: PayloadId,
        recoveryLeaseId: EvidenceStagingLeaseId,
        claimedAt: Instant,
        expiresAt: Instant,
    ): EvidenceOrphanClaimResult

    suspend fun completeRecovery(
        work: EvidenceStagingRecoveryWork,
    ): RepositoryWriteResult

    suspend fun release(
        reservation: EvidenceStagingReservation,
    ): RepositoryWriteResult

    data object Disabled : SourceEvidenceStagingRepository {
        override suspend fun reserve(
            requested: EvidenceStagingReservation,
            now: Instant,
        ): EvidenceStagingReserveResult = EvidenceStagingReserveResult(
            status = EvidenceStagingReserveStatus.DISABLED,
        )

        override suspend fun storageSummary(): EvidenceStagingStorageSummary =
            EvidenceStagingStorageSummary(0L, 0L, 0L)

        override suspend fun knownPayloadIds(limit: Int): EvidenceKnownPayloadIds =
            EvidenceKnownPayloadIds(emptySet(), truncated = false)

        override suspend fun expiredLeases(
            now: Instant,
            limit: Int,
        ): List<EvidenceStagingExpiredLease> = emptyList()

        override suspend fun claimExpired(
            expected: EvidenceStagingExpiredLease,
            recoveryLeaseId: EvidenceStagingLeaseId,
            claimedAt: Instant,
            expiresAt: Instant,
        ): EvidenceStagingClaimResult = EvidenceStagingClaimResult(
            status = EvidenceStagingClaimStatus.STALE,
        )

        override suspend fun claimOrphan(
            payloadId: PayloadId,
            recoveryLeaseId: EvidenceStagingLeaseId,
            claimedAt: Instant,
            expiresAt: Instant,
        ): EvidenceOrphanClaimResult = EvidenceOrphanClaimResult(
            status = EvidenceOrphanClaimStatus.RESERVED,
        )

        override suspend fun completeRecovery(
            work: EvidenceStagingRecoveryWork,
        ): RepositoryWriteResult = RepositoryWriteResult(
            status = RepositoryWriteStatus.INVALID_STATE,
        )

        override suspend fun release(
            reservation: EvidenceStagingReservation,
        ): RepositoryWriteResult = RepositoryWriteResult(
            status = RepositoryWriteStatus.INVALID_STATE,
        )
    }
}

interface SourceEvidenceLifecycleRepository {
    fun observeOverview(): Flow<SourceEvidenceOverview>

    suspend fun getOverview(): SourceEvidenceOverview

    suspend fun page(
        limit: Int,
        cursor: SourceEvidenceCursor?,
    ): SourceEvidencePage

    suspend fun find(rawEventId: RawEventId): SourceEvidenceItem?

    suspend fun findByPayloadId(payloadId: PayloadId): SourceEvidenceItem?

    suspend fun updateRetentionPolicy(
        commandId: CommandId,
        retentionDays: Int?,
        updatedAt: Instant,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult

    suspend fun requestClear(
        commandId: CommandId,
        rawEventId: RawEventId,
        reason: EvidenceClearReason,
        requestedAt: Instant,
        auditRecord: AuditRecord,
    ): EvidenceClearRequestResult

    suspend fun markCleared(
        work: EvidenceClearWork,
        clearedAt: Instant,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult

    suspend fun findPendingClearWork(rawEventId: RawEventId): EvidenceClearWork?

    suspend fun pendingClearWork(limit: Int): List<EvidenceClearWork>

    suspend fun retentionCandidates(
        capturedBefore: Instant,
        limit: Int,
    ): List<SourceEvidenceItem>

    suspend fun capacityCandidates(limit: Int): List<SourceEvidenceItem>

    suspend fun unknownSizeItems(limit: Int): List<SourceEvidenceItem>

    suspend fun recordMeasuredSize(
        rawEventId: RawEventId,
        payloadSizeBytes: Long,
    ): RepositoryWriteResult
}
