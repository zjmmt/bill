package dev.bill.application

import dev.bill.core.domain.AuditAction
import dev.bill.core.domain.AuditEventId
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEventId
import dev.bill.source.review.EvidenceArtifactStore
import dev.bill.source.review.EvidenceClearReason
import dev.bill.source.review.EvidenceClearRequestStatus
import dev.bill.source.review.EvidenceClearWork
import dev.bill.source.review.EvidenceDeleteResult
import dev.bill.source.review.EvidenceLifecycleStore
import dev.bill.source.review.EvidenceMeasureResult
import dev.bill.source.review.EvidenceOrphanClaimStatus
import dev.bill.source.review.EvidencePayloadState
import dev.bill.source.review.EvidenceStagingClaimStatus
import dev.bill.source.review.EvidenceStagingLeaseId
import dev.bill.source.review.EvidenceStagingRecoveryWork
import dev.bill.source.review.EvidenceStagingReservation
import dev.bill.source.review.EvidenceStagingReserveStatus
import dev.bill.source.review.EvidenceStagingStorageSummary
import dev.bill.source.review.SourceEvidenceCursor
import dev.bill.source.review.SourceEvidenceItem
import dev.bill.source.review.SourceEvidenceLifecycleRepository
import dev.bill.source.review.SourceEvidenceOverview
import dev.bill.source.review.SourceEvidencePage
import dev.bill.source.review.SourceEvidencePolicy
import dev.bill.source.review.SourceEvidenceStagingRepository
import dev.bill.source.review.SourceEvidenceStorageSummary
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SourceEvidenceOperationError {
    NOT_FOUND,
    CONFLICT,
    INVALID_STATE,
    PAYLOAD_DELETE_FAILED,
}

sealed interface SourceEvidenceOperationResult {
    data class Success(val alreadyApplied: Boolean = false) : SourceEvidenceOperationResult

    data class Failure(
        val error: SourceEvidenceOperationError,
    ) : SourceEvidenceOperationResult
}

data class EvidenceMaintenanceReport(
    val resumedClearCount: Int,
    val measuredPayloadCount: Int,
    val clearedMissingPayloadCount: Int,
    val clearedByRetentionCount: Int,
    val clearedByCapacityCount: Int,
    val failedOperationCount: Int,
    val isWithinCapacity: Boolean,
    val finalizedTrackedStagingCount: Int = 0,
    val discardedExpiredStagingCount: Int = 0,
    val discardedOrphanArtifactCount: Int = 0,
    val stagingScanTruncated: Boolean = false,
)

sealed interface EvidenceAdmissionResult {
    data class Allowed(
        val alreadyTracked: Boolean,
        val stagingReservation: EvidenceStagingReservation? = null,
    ) : EvidenceAdmissionResult

    data object IdCollision : EvidenceAdmissionResult

    data object PayloadAlreadyCleared : EvidenceAdmissionResult

    data object StorageLimitReached : EvidenceAdmissionResult

    data object RecoveryInProgress : EvidenceAdmissionResult
}

interface EvidenceStorageAdmission {
    suspend fun admit(
        rawEventId: RawEventId,
        payloadId: PayloadId,
        contentHash: EvidenceHash,
        payloadSizeBytes: Long,
    ): EvidenceAdmissionResult

    suspend fun discardUncommitted(
        payloadId: PayloadId,
        reservation: EvidenceStagingReservation?,
        deletePayload: Boolean = true,
    ): Boolean

    data object AllowAll : EvidenceStorageAdmission {
        override suspend fun admit(
            rawEventId: RawEventId,
            payloadId: PayloadId,
            contentHash: EvidenceHash,
            payloadSizeBytes: Long,
        ): EvidenceAdmissionResult = EvidenceAdmissionResult.Allowed(alreadyTracked = false)

        override suspend fun discardUncommitted(
            payloadId: PayloadId,
            reservation: EvidenceStagingReservation?,
            deletePayload: Boolean,
        ): Boolean = false
    }
}

interface SourceEvidenceManager {
    fun observeOverview(): Flow<SourceEvidenceOverview>

    suspend fun page(
        cursor: SourceEvidenceCursor? = null,
        limit: Int = SourceEvidenceLifecycleService.DefaultPageSize,
    ): SourceEvidencePage

    suspend fun updateRetentionPolicy(retentionDays: Int?): SourceEvidenceOperationResult

    suspend fun clearEvidence(rawEventId: RawEventId): SourceEvidenceOperationResult

    suspend fun runMaintenance(): EvidenceMaintenanceReport

    data object Empty : SourceEvidenceManager {
        override fun observeOverview(): Flow<SourceEvidenceOverview> = flowOf(
            SourceEvidenceOverview(
                policy = SourceEvidencePolicy(retentionDays = 30, updatedAt = Instant.EPOCH),
                storage = SourceEvidenceStorageSummary(
                    storedCount = 0L,
                    storedBytes = 0L,
                    unknownSizeCount = 0L,
                    clearPendingCount = 0L,
                    clearedCount = 0L,
                ),
            ),
        )

        override suspend fun page(
            cursor: SourceEvidenceCursor?,
            limit: Int,
        ): SourceEvidencePage = SourceEvidencePage(emptyList(), nextCursor = null)

        override suspend fun updateRetentionPolicy(
            retentionDays: Int?,
        ): SourceEvidenceOperationResult =
            operationFailure(SourceEvidenceOperationError.INVALID_STATE)

        override suspend fun clearEvidence(
            rawEventId: RawEventId,
        ): SourceEvidenceOperationResult =
            operationFailure(SourceEvidenceOperationError.NOT_FOUND)

        override suspend fun runMaintenance(): EvidenceMaintenanceReport =
            EvidenceMaintenanceReport(
                resumedClearCount = 0,
                measuredPayloadCount = 0,
                clearedMissingPayloadCount = 0,
                clearedByRetentionCount = 0,
                clearedByCapacityCount = 0,
                failedOperationCount = 0,
                isWithinCapacity = true,
            )
    }
}

class SourceEvidenceLifecycleService(
    private val repository: SourceEvidenceLifecycleRepository,
    private val store: EvidenceLifecycleStore,
    private val stagingRepository: SourceEvidenceStagingRepository =
        SourceEvidenceStagingRepository.Disabled,
    private val artifactStore: EvidenceArtifactStore = EvidenceArtifactStore.Empty,
    private val clock: Clock = Clock.systemUTC(),
    private val commandIdFactory: () -> CommandId = {
        CommandId(UUID.randomUUID().toString())
    },
    private val maxStoredBytes: Long = DefaultMaxStoredBytes,
    private val maxStoredCount: Long = DefaultMaxStoredCount,
    private val unknownPayloadEstimateBytes: Long = DefaultUnknownPayloadEstimateBytes,
    private val stagingLeaseIdFactory: () -> EvidenceStagingLeaseId = {
        EvidenceStagingLeaseId(UUID.randomUUID().toString())
    },
    private val stagingLeaseSeconds: Long = DefaultStagingLeaseSeconds,
    private val orphanGraceSeconds: Long = DefaultOrphanGraceSeconds,
) : EvidenceStorageAdmission, SourceEvidenceManager {
    private val mutex = Mutex()

    init {
        require(maxStoredBytes > 0L)
        require(maxStoredCount > 0L)
        require(unknownPayloadEstimateBytes in 1..maxStoredBytes)
        require(stagingLeaseSeconds > 0L)
        require(orphanGraceSeconds >= stagingLeaseSeconds)
    }

    override fun observeOverview(): Flow<SourceEvidenceOverview> = repository.observeOverview()

    override suspend fun page(
        cursor: SourceEvidenceCursor?,
        limit: Int,
    ): SourceEvidencePage = repository.page(limit = limit, cursor = cursor)

    override suspend fun updateRetentionPolicy(
        retentionDays: Int?,
    ): SourceEvidenceOperationResult =
        mutex.withLock {
            val commandId = commandIdFactory()
            val now = clock.instant()
            val writeResult = repository.updateRetentionPolicy(
                commandId = commandId,
                retentionDays = retentionDays,
                updatedAt = now,
                auditRecord = audit(
                    commandId = commandId,
                    suffix = "retention",
                    action = AuditAction.SOURCE_EVIDENCE_RETENTION_CHANGED,
                    entityType = PolicyEntityType,
                    entityId = PolicyEntityId,
                    occurredAt = now,
                ),
            )
            when (writeResult.status) {
                RepositoryWriteStatus.APPLIED,
                RepositoryWriteStatus.ALREADY_APPLIED,
                -> {
                    runMaintenanceLocked()
                    SourceEvidenceOperationResult.Success(
                        alreadyApplied =
                            writeResult.status == RepositoryWriteStatus.ALREADY_APPLIED,
                    )
                }

                RepositoryWriteStatus.COMMAND_COLLISION ->
                    operationFailure(SourceEvidenceOperationError.CONFLICT)

                else -> operationFailure(SourceEvidenceOperationError.INVALID_STATE)
            }
        }

    override suspend fun clearEvidence(
        rawEventId: RawEventId,
    ): SourceEvidenceOperationResult =
        mutex.withLock {
            when (repository.find(rawEventId)?.state) {
                null -> operationFailure(SourceEvidenceOperationError.NOT_FOUND)
                EvidencePayloadState.CLEARED ->
                    SourceEvidenceOperationResult.Success(alreadyApplied = true)

                EvidencePayloadState.CLEAR_PENDING -> {
                    val work = repository.findPendingClearWork(rawEventId)
                        ?: return@withLock operationFailure(
                            SourceEvidenceOperationError.INVALID_STATE,
                        )
                    completeClear(work)
                }

                EvidencePayloadState.AVAILABLE ->
                    clearWithNewCommand(rawEventId, EvidenceClearReason.USER_REQUEST)
            }
        }

    override suspend fun runMaintenance(): EvidenceMaintenanceReport = mutex.withLock {
        runMaintenanceLocked()
    }

    override suspend fun admit(
        rawEventId: RawEventId,
        payloadId: PayloadId,
        contentHash: EvidenceHash,
        payloadSizeBytes: Long,
    ): EvidenceAdmissionResult {
        require(payloadSizeBytes >= 0L)
        return mutex.withLock {
            val existingByRaw = repository.find(rawEventId)
            val existingByPayload = repository.findByPayloadId(payloadId)
            if (existingByRaw != null || existingByPayload != null) {
                if (
                    existingByRaw == null ||
                    existingByPayload == null ||
                    existingByRaw.rawEventId != existingByPayload.rawEventId ||
                    existingByRaw.payloadId != payloadId ||
                    existingByPayload.rawEventId != rawEventId
                ) {
                    return@withLock EvidenceAdmissionResult.IdCollision
                }
                if (existingByRaw.state != EvidencePayloadState.AVAILABLE) {
                    return@withLock EvidenceAdmissionResult.PayloadAlreadyCleared
                }
                if (
                    existingByRaw.payloadSizeBytes != null &&
                    existingByRaw.payloadSizeBytes != payloadSizeBytes
                ) {
                    return@withLock EvidenceAdmissionResult.IdCollision
                }
                return@withLock EvidenceAdmissionResult.Allowed(alreadyTracked = true)
            }

            val maintenance = runMaintenanceLocked()
            if (maintenance.stagingScanTruncated) {
                return@withLock EvidenceAdmissionResult.StorageLimitReached
            }

            val now = clock.instant()
            val requested = EvidenceStagingReservation(
                rawEventId = rawEventId,
                payloadId = payloadId,
                contentHash = contentHash,
                payloadSizeBytes = payloadSizeBytes,
                leaseId = stagingLeaseIdFactory(),
                createdAt = now,
                expiresAt = now.safePlusSeconds(stagingLeaseSeconds),
            )
            val reservationResult = stagingRepository.reserve(requested, now)
            when (reservationResult.status) {
                    EvidenceStagingReserveStatus.RESERVED -> {
                        val reservation = requireNotNull(reservationResult.reservation)
                        makeCapacityAvailable()
                        if (!isWithinCapacity()) {
                            stagingRepository.release(reservation)
                            EvidenceAdmissionResult.StorageLimitReached
                        } else {
                            EvidenceAdmissionResult.Allowed(
                                alreadyTracked = false,
                                stagingReservation = reservation,
                            )
                        }
                    }

                    EvidenceStagingReserveStatus.ALREADY_RESERVED ->
                        EvidenceAdmissionResult.Allowed(
                            alreadyTracked = false,
                            stagingReservation = requireNotNull(
                                reservationResult.reservation,
                            ),
                        )

                    EvidenceStagingReserveStatus.ALREADY_TRACKED ->
                        EvidenceAdmissionResult.Allowed(alreadyTracked = true)

                    EvidenceStagingReserveStatus.ID_COLLISION ->
                        EvidenceAdmissionResult.IdCollision

                    EvidenceStagingReserveStatus.RECOVERY_IN_PROGRESS ->
                        EvidenceAdmissionResult.RecoveryInProgress

                    EvidenceStagingReserveStatus.DISABLED -> {
                        makeCapacityAvailableForUnjournaled(payloadSizeBytes)
                        val overview = repository.getOverview()
                        val projectedCount = overview.storage.storedCount.saturatingAdd(1L)
                        val projectedBytes = effectiveStoredBytes(overview)
                            .saturatingAdd(payloadSizeBytes)
                        if (
                            projectedCount > maxStoredCount ||
                            projectedBytes > maxStoredBytes
                        ) {
                            EvidenceAdmissionResult.StorageLimitReached
                        } else {
                            EvidenceAdmissionResult.Allowed(alreadyTracked = false)
                        }
                    }
            }
        }
    }

    override suspend fun discardUncommitted(
        payloadId: PayloadId,
        reservation: EvidenceStagingReservation?,
        deletePayload: Boolean,
    ): Boolean =
        mutex.withLock {
            if (repository.findByPayloadId(payloadId) != null) {
                return@withLock false
            }
            if (reservation != null) {
                val releaseStatus = stagingRepository.release(reservation).status
                if (
                    releaseStatus != RepositoryWriteStatus.APPLIED &&
                    releaseStatus != RepositoryWriteStatus.ALREADY_APPLIED
                ) {
                    return@withLock false
                }
                if (repository.findByPayloadId(payloadId) != null) {
                    return@withLock false
                }
            }
            if (!deletePayload) {
                return@withLock true
            }
            store.delete(payloadId) == EvidenceDeleteResult.DeletedOrAbsent
        }

    private suspend fun recoverStagingLocked(): StagingRecoverySummary {
        val now = clock.instant()
        val recoveryExpiresAt = now.safePlusSeconds(stagingLeaseSeconds)
        var finalizedTrackedCount = 0
        var discardedExpiredCount = 0
        var discardedOrphanCount = 0
        var failedCount = 0

        stagingRepository.expiredLeases(now, MaxStagingRecoveryBatchSize)
            .forEach { expired ->
                val claim = stagingRepository.claimExpired(
                    expected = expired,
                    recoveryLeaseId = stagingLeaseIdFactory(),
                    claimedAt = now,
                    expiresAt = recoveryExpiresAt,
                )
                when (claim.status) {
                        EvidenceStagingClaimStatus.CLAIMED -> {
                            if (completeStagingRecovery(requireNotNull(claim.work))) {
                                discardedExpiredCount += 1
                            } else {
                                failedCount += 1
                            }
                        }

                        EvidenceStagingClaimStatus.TRACKED_FINALIZED ->
                            finalizedTrackedCount += 1

                        EvidenceStagingClaimStatus.STALE -> Unit
                        EvidenceStagingClaimStatus.INVALID_STATE -> failedCount += 1
                }
            }

        val knownPayloadIds = stagingRepository.knownPayloadIds(MaxKnownPayloadIds)
        var scanTruncated = knownPayloadIds.truncated
        if (!knownPayloadIds.truncated) {
            val scan = artifactStore.scanOrphanCandidates(
                olderThanOrEqualTo = now.safeMinusSeconds(orphanGraceSeconds),
                excludedPayloadIds = knownPayloadIds.payloadIds,
                limit = MaxOrphanArtifactBatchSize,
                inspectionLimit = MaxArtifactInspectionCount,
            )
            scanTruncated = scan.truncated
            scan.payloadIds.forEach { payloadId ->
                val claim = stagingRepository.claimOrphan(
                    payloadId = payloadId,
                    recoveryLeaseId = stagingLeaseIdFactory(),
                    claimedAt = now,
                    expiresAt = recoveryExpiresAt,
                )
                when (claim.status) {
                        EvidenceOrphanClaimStatus.CLAIMED -> {
                            if (completeStagingRecovery(requireNotNull(claim.work))) {
                                discardedOrphanCount += 1
                            } else {
                                failedCount += 1
                            }
                        }

                        EvidenceOrphanClaimStatus.TRACKED,
                        EvidenceOrphanClaimStatus.RESERVED,
                        -> Unit

                        EvidenceOrphanClaimStatus.INVALID_STATE -> failedCount += 1
                }
            }
        }

        return StagingRecoverySummary(
            finalizedTrackedCount = finalizedTrackedCount,
            discardedExpiredCount = discardedExpiredCount,
            discardedOrphanCount = discardedOrphanCount,
            failedCount = failedCount,
            scanTruncated = scanTruncated,
        )
    }

    private suspend fun completeStagingRecovery(
        work: EvidenceStagingRecoveryWork,
    ): Boolean {
        if (store.delete(work.payloadId) != EvidenceDeleteResult.DeletedOrAbsent) {
            return false
        }
        return when (stagingRepository.completeRecovery(work).status) {
            RepositoryWriteStatus.APPLIED,
            RepositoryWriteStatus.ALREADY_APPLIED,
            -> true

            else -> false
        }
    }

    private suspend fun runMaintenanceLocked(): EvidenceMaintenanceReport {
        var resumedCount = 0
        var measuredCount = 0
        var missingPayloadCount = 0
        var retentionCount = 0
        var capacityCount = 0
        var failureCount = 0
        val stagingRecovery = recoverStagingLocked()
        failureCount += stagingRecovery.failedCount

        repository.pendingClearWork(MaxLifecycleBatchSize).forEach { work ->
            when (completeClear(work)) {
                is SourceEvidenceOperationResult.Success -> resumedCount += 1
                is SourceEvidenceOperationResult.Failure -> failureCount += 1
            }
        }

        repository.unknownSizeItems(MaxLifecycleBatchSize)
            .filter { it.state == EvidencePayloadState.AVAILABLE }
            .forEach { item ->
                when (
                    val measureResult = store.measure(
                        payloadId = item.payloadId,
                        maxBytes = maxStoredBytes,
                    )
                ) {
                    is EvidenceMeasureResult.Found -> {
                        val writeResult = repository.recordMeasuredSize(
                            rawEventId = item.rawEventId,
                            payloadSizeBytes = measureResult.payloadSizeBytes,
                        )
                        if (
                            writeResult.status == RepositoryWriteStatus.APPLIED ||
                            writeResult.status == RepositoryWriteStatus.ALREADY_APPLIED
                        ) {
                            measuredCount += 1
                        } else {
                            failureCount += 1
                        }
                    }

                    EvidenceMeasureResult.NotFound -> {
                        when (
                            clearWithNewCommand(
                                rawEventId = item.rawEventId,
                                reason = EvidenceClearReason.MISSING_PAYLOAD,
                            )
                        ) {
                            is SourceEvidenceOperationResult.Success -> missingPayloadCount += 1
                            is SourceEvidenceOperationResult.Failure -> failureCount += 1
                        }
                    }

                    EvidenceMeasureResult.Failed -> failureCount += 1
                }
            }

        val overviewAfterMeasure = repository.getOverview()
        val retentionDays = overviewAfterMeasure.policy.retentionDays
        if (retentionDays != null) {
            val cutoff = retentionCutoff(clock.instant(), retentionDays)
            for (pass in 0 until MaxMaintenancePasses) {
                val candidates = repository.retentionCandidates(
                    capturedBefore = cutoff,
                    limit = MaxLifecycleBatchSize,
                )
                if (candidates.isEmpty()) break
                candidates.forEach { item ->
                    when (
                        clearWithNewCommand(
                            rawEventId = item.rawEventId,
                            reason = EvidenceClearReason.RETENTION,
                        )
                    ) {
                        is SourceEvidenceOperationResult.Success -> retentionCount += 1
                        is SourceEvidenceOperationResult.Failure -> failureCount += 1
                    }
                }
                if (candidates.size < MaxLifecycleBatchSize) break
            }
        }

        for (pass in 0 until MaxMaintenancePasses) {
            var overview = repository.getOverview()
            if (isWithinCapacity(overview, stagingRepository.storageSummary())) break
            val candidates = repository.capacityCandidates(MaxLifecycleBatchSize)
            if (candidates.isEmpty()) break
            for (item in candidates) {
                when (
                    clearWithNewCommand(
                        rawEventId = item.rawEventId,
                        reason = EvidenceClearReason.CAPACITY,
                    )
                ) {
                    is SourceEvidenceOperationResult.Success -> capacityCount += 1
                    is SourceEvidenceOperationResult.Failure -> failureCount += 1
                }
                overview = repository.getOverview()
                if (isWithinCapacity(overview, stagingRepository.storageSummary())) break
            }
        }

        return EvidenceMaintenanceReport(
            resumedClearCount = resumedCount,
            measuredPayloadCount = measuredCount,
            clearedMissingPayloadCount = missingPayloadCount,
            clearedByRetentionCount = retentionCount,
            clearedByCapacityCount = capacityCount,
            failedOperationCount = failureCount,
            isWithinCapacity = isWithinCapacity(),
            finalizedTrackedStagingCount = stagingRecovery.finalizedTrackedCount,
            discardedExpiredStagingCount = stagingRecovery.discardedExpiredCount,
            discardedOrphanArtifactCount = stagingRecovery.discardedOrphanCount,
            stagingScanTruncated = stagingRecovery.scanTruncated,
        )
    }

    private suspend fun makeCapacityAvailable() {
        for (pass in 0 until MaxMaintenancePasses) {
            val overview = repository.getOverview()
            val staging = stagingRepository.storageSummary()
            if (isWithinCapacity(overview, staging)) return
            val candidates = repository.capacityCandidates(MaxLifecycleBatchSize)
            if (candidates.isEmpty()) return
            for (item in candidates) {
                clearWithNewCommand(
                    rawEventId = item.rawEventId,
                    reason = EvidenceClearReason.CAPACITY,
                )
                if (isWithinCapacity()) return
            }
        }
    }

    private suspend fun makeCapacityAvailableForUnjournaled(payloadSizeBytes: Long) {
        if (payloadSizeBytes > maxStoredBytes) return
        for (pass in 0 until MaxMaintenancePasses) {
            var overview = repository.getOverview()
            if (
                overview.storage.storedCount.saturatingAdd(1L) <= maxStoredCount &&
                effectiveStoredBytes(overview).saturatingAdd(payloadSizeBytes) <= maxStoredBytes
            ) {
                return
            }
            val candidates = repository.capacityCandidates(MaxLifecycleBatchSize)
            if (candidates.isEmpty()) return
            for (item in candidates) {
                clearWithNewCommand(
                    rawEventId = item.rawEventId,
                    reason = EvidenceClearReason.CAPACITY,
                )
                overview = repository.getOverview()
                if (
                    overview.storage.storedCount.saturatingAdd(1L) <= maxStoredCount &&
                    effectiveStoredBytes(overview).saturatingAdd(payloadSizeBytes) <= maxStoredBytes
                ) {
                    return
                }
            }
        }
    }

    private suspend fun clearWithNewCommand(
        rawEventId: RawEventId,
        reason: EvidenceClearReason,
    ): SourceEvidenceOperationResult {
        val commandId = commandIdFactory()
        val requestedAt = clock.instant()
        val requestResult = repository.requestClear(
            commandId = commandId,
            rawEventId = rawEventId,
            reason = reason,
            requestedAt = requestedAt,
            auditRecord = audit(
                commandId = commandId,
                suffix = "clear-request",
                action = AuditAction.SOURCE_EVIDENCE_CLEAR_REQUESTED,
                entityType = EvidenceEntityType,
                entityId = rawEventId.value,
                occurredAt = requestedAt,
            ),
        )
        return when (requestResult.status) {
            EvidenceClearRequestStatus.REQUESTED,
            EvidenceClearRequestStatus.ALREADY_PENDING,
            -> requestResult.work?.let { completeClear(it) }
                ?: operationFailure(SourceEvidenceOperationError.INVALID_STATE)

            EvidenceClearRequestStatus.ALREADY_CLEARED ->
                SourceEvidenceOperationResult.Success(alreadyApplied = true)

            EvidenceClearRequestStatus.NOT_FOUND ->
                operationFailure(SourceEvidenceOperationError.NOT_FOUND)

            EvidenceClearRequestStatus.COMMAND_COLLISION ->
                operationFailure(SourceEvidenceOperationError.CONFLICT)

            EvidenceClearRequestStatus.INVALID_STATE ->
                operationFailure(SourceEvidenceOperationError.INVALID_STATE)
        }
    }

    private suspend fun completeClear(
        work: EvidenceClearWork,
    ): SourceEvidenceOperationResult {
        if (store.delete(work.payloadId) != EvidenceDeleteResult.DeletedOrAbsent) {
            return operationFailure(SourceEvidenceOperationError.PAYLOAD_DELETE_FAILED)
        }
        val clearedAt = clock.instant().coerceAtLeast(work.requestedAt)
        val writeResult = repository.markCleared(
            work = work,
            clearedAt = clearedAt,
            auditRecord = audit(
                commandId = work.commandId,
                suffix = "cleared",
                action = AuditAction.SOURCE_EVIDENCE_CLEARED,
                entityType = EvidenceEntityType,
                entityId = work.rawEventId.value,
                occurredAt = clearedAt,
            ),
        )
        return when (writeResult.status) {
            RepositoryWriteStatus.APPLIED ->
                SourceEvidenceOperationResult.Success()

            RepositoryWriteStatus.ALREADY_APPLIED ->
                SourceEvidenceOperationResult.Success(alreadyApplied = true)

            RepositoryWriteStatus.NOT_FOUND ->
                operationFailure(SourceEvidenceOperationError.NOT_FOUND)

            RepositoryWriteStatus.COMMAND_COLLISION ->
                operationFailure(SourceEvidenceOperationError.CONFLICT)

            else -> operationFailure(SourceEvidenceOperationError.INVALID_STATE)
        }
    }

    private fun effectiveStoredBytes(overview: SourceEvidenceOverview): Long =
        overview.storage.storedBytes.saturatingAdd(
            overview.storage.unknownSizeCount.saturatingMultiply(
                unknownPayloadEstimateBytes,
            ),
        )

    private fun effectiveStagedBytes(
        staging: EvidenceStagingStorageSummary,
    ): Long = staging.stagedBytes.saturatingAdd(
        staging.unknownSizeCount.saturatingMultiply(unknownPayloadEstimateBytes),
    )

    private fun isWithinCapacity(
        overview: SourceEvidenceOverview,
        staging: EvidenceStagingStorageSummary,
    ): Boolean =
        overview.storage.storedCount
            .saturatingAdd(staging.stagedCount) <= maxStoredCount &&
            effectiveStoredBytes(overview)
                .saturatingAdd(effectiveStagedBytes(staging)) <= maxStoredBytes

    private suspend fun isWithinCapacity(): Boolean = isWithinCapacity(
        overview = repository.getOverview(),
        staging = stagingRepository.storageSummary(),
    )

    private fun retentionCutoff(now: Instant, retentionDays: Int): Instant = try {
        now.minus(retentionDays.toLong(), ChronoUnit.DAYS)
    } catch (_: DateTimeException) {
        Instant.MIN
    } catch (_: ArithmeticException) {
        Instant.MIN
    }

    private fun audit(
        commandId: CommandId,
        suffix: String,
        action: AuditAction,
        entityType: String,
        entityId: String,
        occurredAt: Instant,
    ) = AuditRecord(
        id = AuditEventId("audit:evidence:$suffix:${commandId.value}"),
        commandId = commandId,
        action = action,
        entityType = entityType,
        entityId = entityId,
        occurredAt = occurredAt,
    )

    companion object {
        const val DefaultPageSize = 20
        const val DefaultMaxStoredBytes = 16L * 1024L * 1024L
        const val DefaultMaxStoredCount = 512L
        const val DefaultUnknownPayloadEstimateBytes = 64L * 1024L
        const val DefaultStagingLeaseSeconds = 5L * 60L
        const val DefaultOrphanGraceSeconds = 10L * 60L
        private const val MaxLifecycleBatchSize = 512
        private const val MaxStagingRecoveryBatchSize = 128
        private const val MaxOrphanArtifactBatchSize = 512
        private const val MaxArtifactInspectionCount = 4_096
        private const val MaxKnownPayloadIds = 2_048
        private const val MaxMaintenancePasses = 8
        private const val PolicyEntityType = "source_evidence_policy"
        private const val PolicyEntityId = "default"
        private const val EvidenceEntityType = "source_evidence"
    }
}

private data class StagingRecoverySummary(
    val finalizedTrackedCount: Int,
    val discardedExpiredCount: Int,
    val discardedOrphanCount: Int,
    val failedCount: Int,
    val scanTruncated: Boolean,
)

private fun operationFailure(error: SourceEvidenceOperationError) =
    SourceEvidenceOperationResult.Failure(error)

private fun Long.saturatingAdd(other: Long): Long =
    if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun Long.saturatingMultiply(other: Long): Long =
    if (this == 0L || other == 0L) {
        0L
    } else if (this > Long.MAX_VALUE / other) {
        Long.MAX_VALUE
    } else {
        this * other
    }

private fun Instant.coerceAtLeast(minimum: Instant): Instant =
    if (isBefore(minimum)) minimum else this

private fun Instant.safePlusSeconds(seconds: Long): Instant = try {
    plusSeconds(seconds)
} catch (_: DateTimeException) {
    Instant.MAX
} catch (_: ArithmeticException) {
    Instant.MAX
}

private fun Instant.safeMinusSeconds(seconds: Long): Instant = try {
    minusSeconds(seconds)
} catch (_: DateTimeException) {
    Instant.MIN
} catch (_: ArithmeticException) {
    Instant.MIN
}
