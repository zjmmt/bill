package dev.bill.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.bill.core.domain.RepositoryWriteResult
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEventId
import dev.bill.source.review.EvidenceOrphanClaimResult
import dev.bill.source.review.EvidenceOrphanClaimStatus
import dev.bill.source.review.EvidenceKnownPayloadIds
import dev.bill.source.review.EvidenceStagingClaimResult
import dev.bill.source.review.EvidenceStagingClaimStatus
import dev.bill.source.review.EvidenceStagingExpiredLease
import dev.bill.source.review.EvidenceStagingLeaseId
import dev.bill.source.review.EvidenceStagingRecoveryWork
import dev.bill.source.review.EvidenceStagingReservation
import dev.bill.source.review.EvidenceStagingReserveResult
import dev.bill.source.review.EvidenceStagingReserveStatus
import dev.bill.source.review.EvidenceStagingStorageSummary
import dev.bill.source.review.SourceEvidenceStagingRepository
import java.time.Instant

class RoomSourceEvidenceStagingRepository(
    private val database: BillDatabase,
) : SourceEvidenceStagingRepository {
    private val stagingDao = database.sourceEvidenceStagingDao()
    private val evidenceDao = database.sourceEvidenceDao()
    private val rawEventDao = database.rawEventDao()

    override suspend fun reserve(
        requested: EvidenceStagingReservation,
        now: Instant,
    ): EvidenceStagingReserveResult {
        require(!requested.createdAt.isAfter(now))
        require(requested.expiresAt.isAfter(now))
        return try {
            database.withTransaction {
                ensureIntegrity()
                trackedDisposition(requested)?.let {
                    return@withTransaction reserveResult(it)
                }

                val byPayload = stagingDao.findByPayloadId(requested.payloadId.value)
                val byRaw = stagingDao.findByRawEventId(requested.rawEventId.value)
                val existing = when {
                    byPayload == null && byRaw == null -> null
                    byPayload != null && byRaw != null && byPayload == byRaw -> byPayload
                    else -> return@withTransaction reserveResult(
                        EvidenceStagingReserveStatus.ID_COLLISION,
                    )
                }

                if (existing == null) {
                    stagingDao.insert(requested.toEntity())
                    return@withTransaction reserveResult(
                        EvidenceStagingReserveStatus.RESERVED,
                        requested,
                    )
                }

                validate(existing)
                if (!existing.matchesRequest(requested)) {
                    return@withTransaction reserveResult(
                        EvidenceStagingReserveStatus.ID_COLLISION,
                    )
                }

                when (existing.state) {
                    ActiveState -> {
                        if (
                            stagingDao.renewActive(
                                payloadId = existing.payloadId,
                                expectedLeaseId = existing.leaseId,
                                newLeaseId = requested.leaseId.value,
                                newExpiresAtEpochMillis = requested.expiresAt.toEpochMilli(),
                            ) != 1
                        ) {
                            return@withTransaction reserveResult(
                                EvidenceStagingReserveStatus.RECOVERY_IN_PROGRESS,
                            )
                        }
                        reserveResult(
                            EvidenceStagingReserveStatus.ALREADY_RESERVED,
                            requested.copy(
                                createdAt = Instant.ofEpochMilli(
                                    existing.createdAtEpochMillis,
                                ),
                            ),
                        )
                    }

                    RecoveryState -> {
                        if (existing.leaseExpiresAtEpochMillis > now.toEpochMilli()) {
                            return@withTransaction reserveResult(
                                EvidenceStagingReserveStatus.RECOVERY_IN_PROGRESS,
                            )
                        }
                        if (
                            stagingDao.takeOverExpiredRecovery(
                                payloadId = existing.payloadId,
                                expectedLeaseId = existing.leaseId,
                                rawEventId = requested.rawEventId.value,
                                contentHash = requested.contentHash.value,
                                payloadSizeBytes = requested.payloadSizeBytes,
                                newLeaseId = requested.leaseId.value,
                                createdAtEpochMillis = requested.createdAt.toEpochMilli(),
                                newExpiresAtEpochMillis = requested.expiresAt.toEpochMilli(),
                                nowEpochMillis = now.toEpochMilli(),
                            ) != 1
                        ) {
                            return@withTransaction reserveResult(
                                EvidenceStagingReserveStatus.RECOVERY_IN_PROGRESS,
                            )
                        }
                        reserveResult(
                            EvidenceStagingReserveStatus.ALREADY_RESERVED,
                            requested,
                        )
                    }

                    else -> throw LocalDataIntegrityException("source evidence staging")
                }
            }
        } catch (_: SQLiteConstraintException) {
            reserveResult(EvidenceStagingReserveStatus.ID_COLLISION)
        }
    }

    override suspend fun storageSummary(): EvidenceStagingStorageSummary =
        database.withTransaction {
            ensureIntegrity()
            val row = stagingDao.storageSummary()
            try {
                EvidenceStagingStorageSummary(
                    stagedCount = row.stagedCount,
                    stagedBytes = row.stagedBytes,
                    unknownSizeCount = row.unknownSizeCount,
                )
            } catch (_: RuntimeException) {
                throw LocalDataIntegrityException("source evidence staging")
            }
        }

    override suspend fun knownPayloadIds(limit: Int): EvidenceKnownPayloadIds {
        require(limit in 1..MaxKnownPayloadIds)
        return database.withTransaction {
            ensureIntegrity()
            val queryLimit = limit + 1
            val ids = linkedSetOf<String>()
            evidenceDao.storedPayloadIds(queryLimit).forEach(ids::add)
            if (ids.size <= limit) {
                stagingDao.payloadIds(queryLimit).forEach(ids::add)
            }
            val truncated = ids.size > limit
            val bounded = ids.take(limit).mapTo(linkedSetOf(), ::PayloadId)
            EvidenceKnownPayloadIds(bounded, truncated)
        }
    }

    override suspend fun expiredLeases(
        now: Instant,
        limit: Int,
    ): List<EvidenceStagingExpiredLease> {
        require(limit in 1..MaxBatchSize)
        return database.withTransaction {
            ensureIntegrity()
            stagingDao.expired(now.toEpochMilli(), limit).map { entity ->
                validate(entity)
                EvidenceStagingExpiredLease(
                    payloadId = PayloadId(entity.payloadId),
                    leaseId = EvidenceStagingLeaseId(entity.leaseId),
                    expiresAt = Instant.ofEpochMilli(entity.leaseExpiresAtEpochMillis),
                )
            }
        }
    }

    override suspend fun claimExpired(
        expected: EvidenceStagingExpiredLease,
        recoveryLeaseId: EvidenceStagingLeaseId,
        claimedAt: Instant,
        expiresAt: Instant,
    ): EvidenceStagingClaimResult {
        require(expiresAt.isAfter(claimedAt))
        return database.withTransaction {
            ensureIntegrity()
            val current = stagingDao.findByPayloadId(expected.payloadId.value)
                ?: return@withTransaction claimResult(EvidenceStagingClaimStatus.STALE)
            validate(current)
            if (
                current.leaseId != expected.leaseId.value ||
                current.leaseExpiresAtEpochMillis != expected.expiresAt.toEpochMilli() ||
                current.leaseExpiresAtEpochMillis > claimedAt.toEpochMilli()
            ) {
                return@withTransaction claimResult(EvidenceStagingClaimStatus.STALE)
            }

            evidenceDao.findPayloadByPayloadId(current.payloadId)
                ?.takeUnless { it.state == ClearedState }
                ?.let { tracked ->
                    if (!trackedMatches(current, tracked)) {
                        return@withTransaction claimResult(
                            EvidenceStagingClaimStatus.INVALID_STATE,
                        )
                    }
                    val deleted = when (current.state) {
                        ActiveState -> stagingDao.deleteActive(
                            payloadId = current.payloadId,
                            rawEventId = requireNotNull(current.rawEventId),
                            contentHash = requireNotNull(current.contentHash),
                            payloadSizeBytes = requireNotNull(current.payloadSizeBytes),
                            leaseId = current.leaseId,
                        )

                        RecoveryState -> stagingDao.deleteRecovery(
                            payloadId = current.payloadId,
                            leaseId = current.leaseId,
                        )

                        else -> 0
                    }
                    return@withTransaction claimResult(
                        if (deleted == 1) {
                            EvidenceStagingClaimStatus.TRACKED_FINALIZED
                        } else {
                            EvidenceStagingClaimStatus.STALE
                        },
                    )
                }

            if (
                stagingDao.claimExpired(
                    payloadId = current.payloadId,
                    expectedLeaseId = current.leaseId,
                    recoveryLeaseId = recoveryLeaseId.value,
                    recoveryExpiresAtEpochMillis = expiresAt.toEpochMilli(),
                    nowEpochMillis = claimedAt.toEpochMilli(),
                ) != 1
            ) {
                return@withTransaction claimResult(EvidenceStagingClaimStatus.STALE)
            }
            EvidenceStagingClaimResult(
                status = EvidenceStagingClaimStatus.CLAIMED,
                work = EvidenceStagingRecoveryWork(
                    payloadId = expected.payloadId,
                    leaseId = recoveryLeaseId,
                ),
            )
        }
    }

    override suspend fun claimOrphan(
        payloadId: PayloadId,
        recoveryLeaseId: EvidenceStagingLeaseId,
        claimedAt: Instant,
        expiresAt: Instant,
    ): EvidenceOrphanClaimResult {
        require(expiresAt.isAfter(claimedAt))
        return try {
            database.withTransaction {
                ensureIntegrity()
                val lifecycle = evidenceDao.findPayloadByPayloadId(payloadId.value)
                if (lifecycle != null && lifecycle.state != ClearedState) {
                    return@withTransaction orphanResult(EvidenceOrphanClaimStatus.TRACKED)
                }
                if (stagingDao.findByPayloadId(payloadId.value) != null) {
                    return@withTransaction orphanResult(EvidenceOrphanClaimStatus.RESERVED)
                }
                stagingDao.insert(
                    SourceEvidenceStagingEntity(
                        payloadId = payloadId.value,
                        rawEventId = null,
                        contentHash = null,
                        payloadSizeBytes = null,
                        state = RecoveryState,
                        leaseId = recoveryLeaseId.value,
                        createdAtEpochMillis = claimedAt.toEpochMilli(),
                        leaseExpiresAtEpochMillis = expiresAt.toEpochMilli(),
                    ),
                )
                EvidenceOrphanClaimResult(
                    status = EvidenceOrphanClaimStatus.CLAIMED,
                    work = EvidenceStagingRecoveryWork(payloadId, recoveryLeaseId),
                )
            }
        } catch (_: SQLiteConstraintException) {
            orphanResult(EvidenceOrphanClaimStatus.RESERVED)
        }
    }

    override suspend fun completeRecovery(
        work: EvidenceStagingRecoveryWork,
    ): RepositoryWriteResult = database.withTransaction {
        ensureIntegrity()
        val current = stagingDao.findByPayloadId(work.payloadId.value)
            ?: return@withTransaction writeResult(RepositoryWriteStatus.ALREADY_APPLIED)
        validate(current)
        if (
            current.state != RecoveryState ||
            current.leaseId != work.leaseId.value
        ) {
            return@withTransaction writeResult(RepositoryWriteStatus.INVALID_STATE)
        }
        if (stagingDao.deleteRecovery(work.payloadId.value, work.leaseId.value) != 1) {
            return@withTransaction writeResult(RepositoryWriteStatus.INVALID_STATE)
        }
        writeResult(RepositoryWriteStatus.APPLIED, work.payloadId.value)
    }

    override suspend fun release(
        reservation: EvidenceStagingReservation,
    ): RepositoryWriteResult = database.withTransaction {
        ensureIntegrity()
        evidenceDao.findPayloadByPayloadId(reservation.payloadId.value)?.let { tracked ->
            if (!trackedMatches(reservation.toEntity(), tracked)) {
                return@withTransaction writeResult(RepositoryWriteStatus.INVALID_STATE)
            }
            val current = stagingDao.findByPayloadId(reservation.payloadId.value)
                ?: return@withTransaction writeResult(
                    RepositoryWriteStatus.ALREADY_APPLIED,
                    reservation.payloadId.value,
                )
            validate(current)
            if (!current.matchesReservation(reservation)) {
                return@withTransaction writeResult(RepositoryWriteStatus.INVALID_STATE)
            }
            if (
                stagingDao.deleteActive(
                    payloadId = reservation.payloadId.value,
                    rawEventId = reservation.rawEventId.value,
                    contentHash = reservation.contentHash.value,
                    payloadSizeBytes = reservation.payloadSizeBytes,
                    leaseId = reservation.leaseId.value,
                ) != 1
            ) {
                return@withTransaction writeResult(RepositoryWriteStatus.INVALID_STATE)
            }
            return@withTransaction writeResult(
                RepositoryWriteStatus.ALREADY_APPLIED,
                reservation.payloadId.value,
            )
        }

        val current = stagingDao.findByPayloadId(reservation.payloadId.value)
            ?: return@withTransaction writeResult(RepositoryWriteStatus.ALREADY_APPLIED)
        validate(current)
        if (!current.matchesReservation(reservation)) {
            return@withTransaction writeResult(RepositoryWriteStatus.INVALID_STATE)
        }
        if (
            stagingDao.deleteActive(
                payloadId = reservation.payloadId.value,
                rawEventId = reservation.rawEventId.value,
                contentHash = reservation.contentHash.value,
                payloadSizeBytes = reservation.payloadSizeBytes,
                leaseId = reservation.leaseId.value,
            ) != 1
        ) {
            return@withTransaction writeResult(RepositoryWriteStatus.INVALID_STATE)
        }
        writeResult(RepositoryWriteStatus.APPLIED, reservation.payloadId.value)
    }

    private suspend fun trackedDisposition(
        requested: EvidenceStagingReservation,
    ): EvidenceStagingReserveStatus? {
        val byPayload = evidenceDao.findPayloadByPayloadId(requested.payloadId.value)
        val byRaw = evidenceDao.findPayload(requested.rawEventId.value)
        if (byPayload == null && byRaw == null) return null
        if (
            byPayload == null ||
            byRaw == null ||
            byPayload != byRaw ||
            !trackedMatches(requested.toEntity(), byPayload)
        ) {
            return EvidenceStagingReserveStatus.ID_COLLISION
        }
        return EvidenceStagingReserveStatus.ALREADY_TRACKED
    }

    private suspend fun trackedMatches(
        staging: SourceEvidenceStagingEntity,
        tracked: SourceEvidencePayloadEntity,
    ): Boolean {
        val rawEventId = staging.rawEventId ?: return staging.contentHash == null &&
            staging.payloadSizeBytes == null &&
            tracked.payloadId == staging.payloadId
        val contentHash = staging.contentHash ?: return false
        val payloadSizeBytes = staging.payloadSizeBytes ?: return false
        val raw = rawEventDao.findById(rawEventId) ?: return false
        return tracked.rawEventId == rawEventId &&
            tracked.payloadId == staging.payloadId &&
            tracked.state == AvailableState &&
            tracked.payloadSizeBytes == payloadSizeBytes &&
            raw.payloadReference == staging.payloadId &&
            raw.contentHash == contentHash
    }

    private fun validate(entity: SourceEvidenceStagingEntity) {
        try {
            PayloadId(entity.payloadId)
            EvidenceStagingLeaseId(entity.leaseId)
            val createdAt = Instant.ofEpochMilli(entity.createdAtEpochMillis)
            val expiresAt = Instant.ofEpochMilli(entity.leaseExpiresAtEpochMillis)
            require(expiresAt.isAfter(createdAt))
            val hasSource = entity.rawEventId != null ||
                entity.contentHash != null ||
                entity.payloadSizeBytes != null
            if (hasSource) {
                RawEventId(requireNotNull(entity.rawEventId))
                EvidenceHash(requireNotNull(entity.contentHash))
                require(requireNotNull(entity.payloadSizeBytes) >= 0L)
            }
            when (entity.state) {
                ActiveState -> require(hasSource)
                RecoveryState -> Unit
                else -> error("Unknown evidence staging state")
            }
        } catch (_: RuntimeException) {
            throw LocalDataIntegrityException("source evidence staging")
        }
    }

    private suspend fun ensureIntegrity() {
        if (stagingDao.integrityIssueCount() != 0L) {
            throw LocalDataIntegrityException("source evidence staging")
        }
    }

    private companion object {
        const val ActiveState = "ACTIVE"
        const val RecoveryState = "RECOVERY"
        const val AvailableState = "AVAILABLE"
        const val ClearedState = "CLEARED"
        const val MaxBatchSize = 512
        const val MaxKnownPayloadIds = 2048
    }
}

private fun EvidenceStagingReservation.toEntity() = SourceEvidenceStagingEntity(
    payloadId = payloadId.value,
    rawEventId = rawEventId.value,
    contentHash = contentHash.value,
    payloadSizeBytes = payloadSizeBytes,
    state = "ACTIVE",
    leaseId = leaseId.value,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    leaseExpiresAtEpochMillis = expiresAt.toEpochMilli(),
)

private fun SourceEvidenceStagingEntity.matchesRequest(
    requested: EvidenceStagingReservation,
): Boolean {
    val noSource = rawEventId == null && contentHash == null && payloadSizeBytes == null
    return payloadId == requested.payloadId.value &&
        (
            noSource ||
                (
                    rawEventId == requested.rawEventId.value &&
                        contentHash == requested.contentHash.value &&
                        payloadSizeBytes == requested.payloadSizeBytes
                    )
            )
}

private fun SourceEvidenceStagingEntity.matchesReservation(
    reservation: EvidenceStagingReservation,
): Boolean =
    state == "ACTIVE" &&
        rawEventId == reservation.rawEventId.value &&
        payloadId == reservation.payloadId.value &&
        contentHash == reservation.contentHash.value &&
        payloadSizeBytes == reservation.payloadSizeBytes &&
        leaseId == reservation.leaseId.value

private fun reserveResult(
    status: EvidenceStagingReserveStatus,
    reservation: EvidenceStagingReservation? = null,
) = EvidenceStagingReserveResult(status, reservation)

private fun claimResult(status: EvidenceStagingClaimStatus) =
    EvidenceStagingClaimResult(status)

private fun orphanResult(status: EvidenceOrphanClaimStatus) =
    EvidenceOrphanClaimResult(status)

private fun writeResult(
    status: RepositoryWriteStatus,
    entityId: String? = null,
) = RepositoryWriteResult(status, entityId)
