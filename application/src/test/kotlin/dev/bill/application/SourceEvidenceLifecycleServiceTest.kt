package dev.bill.application

import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.RepositoryWriteResult
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.review.EvidenceClearReason
import dev.bill.source.review.EvidenceClearRequestResult
import dev.bill.source.review.EvidenceClearRequestStatus
import dev.bill.source.review.EvidenceClearWork
import dev.bill.source.review.EvidenceArtifactScanResult
import dev.bill.source.review.EvidenceArtifactStore
import dev.bill.source.review.EvidenceDeleteResult
import dev.bill.source.review.EvidenceKnownPayloadIds
import dev.bill.source.review.EvidenceLifecycleStore
import dev.bill.source.review.EvidenceMeasureResult
import dev.bill.source.review.EvidenceOrphanClaimResult
import dev.bill.source.review.EvidenceOrphanClaimStatus
import dev.bill.source.review.EvidencePayloadState
import dev.bill.source.review.EvidenceStagingClaimResult
import dev.bill.source.review.EvidenceStagingClaimStatus
import dev.bill.source.review.EvidenceStagingExpiredLease
import dev.bill.source.review.EvidenceStagingLeaseId
import dev.bill.source.review.EvidenceStagingRecoveryWork
import dev.bill.source.review.EvidenceStagingReservation
import dev.bill.source.review.EvidenceStagingReserveResult
import dev.bill.source.review.EvidenceStagingReserveStatus
import dev.bill.source.review.EvidenceStagingStorageSummary
import dev.bill.source.review.SourceEvidenceCursor
import dev.bill.source.review.SourceEvidenceItem
import dev.bill.source.review.SourceEvidenceLifecycleRepository
import dev.bill.source.review.SourceEvidenceOverview
import dev.bill.source.review.SourceEvidencePage
import dev.bill.source.review.SourceEvidencePolicy
import dev.bill.source.review.SourceEvidenceStorageSummary
import dev.bill.source.review.SourceEvidenceStagingRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEvidenceLifecycleServiceTest {
    @Test
    fun `failed file deletion remains pending and a later user retry completes it`() = runBlocking {
        val fixture = Fixture()
        val item = evidenceItem("retry-clear", capturedAt = fixture.now.minusSeconds(60))
        fixture.repository.add(item)
        fixture.store.sizes[item.payloadId] = 32L
        fixture.store.deleteFailures += item.payloadId

        val first = fixture.service().clearEvidence(item.rawEventId)

        assertEquals(
            SourceEvidenceOperationError.PAYLOAD_DELETE_FAILED,
            (first as SourceEvidenceOperationResult.Failure).error,
        )
        assertEquals(
            EvidencePayloadState.CLEAR_PENDING,
            fixture.repository.find(item.rawEventId)?.state,
        )

        fixture.store.deleteFailures -= item.payloadId
        val retry = fixture.service().clearEvidence(item.rawEventId)

        assertTrue(retry is SourceEvidenceOperationResult.Success)
        assertEquals(
            EvidencePayloadState.CLEARED,
            fixture.repository.find(item.rawEventId)?.state,
        )
        assertEquals(0L, fixture.repository.getOverview().storage.storedCount)
        assertEquals(1L, fixture.repository.getOverview().storage.clearedCount)
    }

    @Test
    fun `retention clears only resolved evidence and measures legacy payload sizes`() = runBlocking {
        val fixture = Fixture(retentionDays = 7)
        val oldResolved = evidenceItem(
            "old-resolved",
            capturedAt = fixture.now.minusSeconds(10L * 24L * 60L * 60L),
            payloadSizeBytes = null,
        )
        val oldPending = evidenceItem(
            "old-pending",
            capturedAt = oldResolved.capturedAt,
            hasPendingReview = true,
        )
        val recent = evidenceItem(
            "recent",
            capturedAt = fixture.now.minusSeconds(24L * 60L * 60L),
        )
        listOf(oldResolved, oldPending, recent).forEach(fixture.repository::add)
        fixture.store.sizes[oldResolved.payloadId] = 48L
        fixture.store.sizes[oldPending.payloadId] = 12L
        fixture.store.sizes[recent.payloadId] = 12L

        val report = fixture.service().runMaintenance()

        assertEquals(1, report.measuredPayloadCount)
        assertEquals(1, report.clearedByRetentionCount)
        assertEquals(
            EvidencePayloadState.CLEARED,
            fixture.repository.find(oldResolved.rawEventId)?.state,
        )
        assertEquals(
            EvidencePayloadState.AVAILABLE,
            fixture.repository.find(oldPending.rawEventId)?.state,
        )
        assertTrue(fixture.repository.find(oldPending.rawEventId)?.hasPendingReview == true)
        assertEquals(
            EvidencePayloadState.AVAILABLE,
            fixture.repository.find(recent.rawEventId)?.state,
        )
    }

    @Test
    fun `capacity evicts oldest resolved evidence but never pending review evidence`() =
        runBlocking {
            val fixture = Fixture(retentionDays = null)
            val oldestResolved = evidenceItem(
                "oldest-resolved",
                capturedAt = fixture.now.minusSeconds(120),
            )
            val pending = evidenceItem(
                "pending",
                capturedAt = fixture.now.minusSeconds(60),
                hasPendingReview = true,
            )
            listOf(oldestResolved, pending).forEach(fixture.repository::add)
            fixture.store.sizes[oldestResolved.payloadId] = 12L
            fixture.store.sizes[pending.payloadId] = 12L
            val service = fixture.service(maxStoredCount = 2L)

            val admitted = service.admit(
                rawEventId = RawEventId("new-event"),
                payloadId = PayloadId("payload-new-event"),
                contentHash = EvidenceHash(ValidHash),
                payloadSizeBytes = 12L,
            )

            assertEquals(EvidenceAdmissionResult.Allowed(false), admitted)
            assertEquals(
                EvidencePayloadState.CLEARED,
                fixture.repository.find(oldestResolved.rawEventId)?.state,
            )
            assertEquals(
                EvidencePayloadState.AVAILABLE,
                fixture.repository.find(pending.rawEventId)?.state,
            )

            val secondFixture = Fixture(retentionDays = null)
            listOf("pending-a", "pending-b").forEachIndexed { index, id ->
                val pendingItem = evidenceItem(
                    id,
                    capturedAt = secondFixture.now.minusSeconds(index.toLong()),
                    hasPendingReview = true,
                )
                secondFixture.repository.add(pendingItem)
                secondFixture.store.sizes[pendingItem.payloadId] = 12L
            }
            val blocked = secondFixture.service(maxStoredCount = 2L).admit(
                rawEventId = RawEventId("blocked-event"),
                payloadId = PayloadId("payload-blocked-event"),
                contentHash = EvidenceHash(ValidHash),
                payloadSizeBytes = 12L,
            )

            assertEquals(EvidenceAdmissionResult.StorageLimitReached, blocked)
            assertTrue(
                secondFixture.repository.items.values.all {
                    it.state == EvidencePayloadState.AVAILABLE
                },
            )
        }

    @Test
    fun `missing legacy payload is finalized as cleared without inventing content`() = runBlocking {
        val fixture = Fixture(retentionDays = null)
        val missing = evidenceItem(
            "missing-payload",
            capturedAt = fixture.now.minusSeconds(60),
            payloadSizeBytes = null,
        )
        fixture.repository.add(missing)

        val report = fixture.service().runMaintenance()
        val cleared = fixture.repository.find(missing.rawEventId)

        assertEquals(1, report.clearedMissingPayloadCount)
        assertEquals(EvidencePayloadState.CLEARED, cleared?.state)
        assertEquals(EvidenceClearReason.MISSING_PAYLOAD, cleared?.clearReason)
        assertFalse(cleared?.hasPendingReview == true)
    }

    @Test
    fun `maintenance recovers expired staging and old unindexed artifacts but keeps active work`() =
        runBlocking {
            val fixture = Fixture(retentionDays = null)
            val staging = FakeStagingRepository()
            val expired = stagingReservation(
                id = "expired",
                createdAt = fixture.now.minusSeconds(600),
                expiresAt = fixture.now.minusSeconds(300),
            )
            val active = stagingReservation(
                id = "active",
                createdAt = fixture.now.minusSeconds(60),
                expiresAt = fixture.now.plusSeconds(240),
            )
            staging.entries[expired.payloadId] = FakeStagingEntry(expired)
            staging.entries[active.payloadId] = FakeStagingEntry(active)
            val orphan = PayloadId("payload-old-orphan")
            fixture.store.sizes[expired.payloadId] = 12L
            fixture.store.sizes[active.payloadId] = 12L
            fixture.store.sizes[orphan] = 9L
            val artifacts = FakeArtifactStore(fixture.store.sizes.keys)

            val report = fixture.service(
                stagingRepository = staging,
                artifactStore = artifacts,
            ).runMaintenance()

            assertEquals(1, report.discardedExpiredStagingCount)
            assertEquals(1, report.discardedOrphanArtifactCount)
            assertEquals(0, report.failedOperationCount)
            assertFalse(report.stagingScanTruncated)
            assertFalse(expired.payloadId in fixture.store.sizes)
            assertFalse(orphan in fixture.store.sizes)
            assertTrue(active.payloadId in fixture.store.sizes)
            assertEquals(setOf(active.payloadId), staging.entries.keys)
        }

    @Test
    fun `admission reserves before staging and explicit rollback releases both layers`() =
        runBlocking {
            val fixture = Fixture(retentionDays = null)
            val staging = FakeStagingRepository()
            val service = fixture.service(stagingRepository = staging)
            val payloadId = PayloadId("payload-new-reservation")

            val admitted = service.admit(
                rawEventId = RawEventId("raw-new-reservation"),
                payloadId = payloadId,
                contentHash = EvidenceHash(ValidHash),
                payloadSizeBytes = 12L,
            ) as EvidenceAdmissionResult.Allowed
            val reservation = requireNotNull(admitted.stagingReservation)
            fixture.store.sizes[payloadId] = 12L

            assertEquals(1L, staging.storageSummary().stagedCount)
            assertTrue(
                service.discardUncommitted(
                    payloadId = payloadId,
                    reservation = reservation,
                ),
            )
            assertTrue(staging.entries.isEmpty())
            assertFalse(payloadId in fixture.store.sizes)
        }

    @Test
    fun `stale rollback lease cannot delete payload owned by a newer reservation`() =
        runBlocking {
            val fixture = Fixture(retentionDays = null)
            val staging = FakeStagingRepository()
            val service = fixture.service(stagingRepository = staging)
            val original = stagingReservation(
                id = "lease-race",
                createdAt = fixture.now.minusSeconds(60),
                expiresAt = fixture.now.plusSeconds(240),
            )
            val current = original.copy(
                leaseId = EvidenceStagingLeaseId("lease-current"),
            )
            staging.entries[current.payloadId] = FakeStagingEntry(current)
            fixture.store.sizes[current.payloadId] = current.payloadSizeBytes

            assertFalse(
                service.discardUncommitted(
                    payloadId = original.payloadId,
                    reservation = original,
                ),
            )
            assertEquals(current, staging.entries[current.payloadId]?.reservation)
            assertTrue(current.payloadId in fixture.store.sizes)
        }

    private class Fixture(retentionDays: Int? = 30) {
        val now: Instant = Instant.parse("2026-07-20T12:00:00Z")
        val repository = FakeLifecycleRepository(retentionDays)
        val store = FakeLifecycleStore()
        private var commandSequence = 0

        fun service(
            maxStoredCount: Long = 512L,
            stagingRepository: SourceEvidenceStagingRepository =
                SourceEvidenceStagingRepository.Disabled,
            artifactStore: EvidenceArtifactStore = EvidenceArtifactStore.Empty,
        ) = SourceEvidenceLifecycleService(
            repository = repository,
            store = store,
            stagingRepository = stagingRepository,
            artifactStore = artifactStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            commandIdFactory = {
                commandSequence += 1
                CommandId("command-$commandSequence")
            },
            maxStoredBytes = 16L * 1024L * 1024L,
            maxStoredCount = maxStoredCount,
            unknownPayloadEstimateBytes = 64L * 1024L,
        )
    }
}

private class FakeLifecycleStore : EvidenceLifecycleStore {
    val sizes = linkedMapOf<PayloadId, Long>()
    val deleteFailures = mutableSetOf<PayloadId>()
    val measureFailures = mutableSetOf<PayloadId>()

    override suspend fun delete(payloadId: PayloadId): EvidenceDeleteResult {
        if (payloadId in deleteFailures) return EvidenceDeleteResult.Failed
        sizes.remove(payloadId)
        return EvidenceDeleteResult.DeletedOrAbsent
    }

    override suspend fun measure(
        payloadId: PayloadId,
        maxBytes: Long,
    ): EvidenceMeasureResult {
        if (payloadId in measureFailures) return EvidenceMeasureResult.Failed
        val size = sizes[payloadId] ?: return EvidenceMeasureResult.NotFound
        return if (size <= maxBytes) {
            EvidenceMeasureResult.Found(size)
        } else {
            EvidenceMeasureResult.Failed
        }
    }
}

private data class FakeStagingEntry(
    val reservation: EvidenceStagingReservation?,
    val leaseId: EvidenceStagingLeaseId = requireNotNull(reservation).leaseId,
    val expiresAt: Instant = requireNotNull(reservation).expiresAt,
    val recovery: Boolean = false,
)

private class FakeStagingRepository : SourceEvidenceStagingRepository {
    val entries = linkedMapOf<PayloadId, FakeStagingEntry>()
    val trackedPayloadIds = mutableSetOf<PayloadId>()

    override suspend fun reserve(
        requested: EvidenceStagingReservation,
        now: Instant,
    ): EvidenceStagingReserveResult {
        if (requested.payloadId in trackedPayloadIds) {
            return EvidenceStagingReserveResult(EvidenceStagingReserveStatus.ALREADY_TRACKED)
        }
        val existing = entries[requested.payloadId]
        if (existing == null) {
            entries[requested.payloadId] = FakeStagingEntry(requested)
            return EvidenceStagingReserveResult(
                EvidenceStagingReserveStatus.RESERVED,
                requested,
            )
        }
        if (existing.recovery && existing.expiresAt.isAfter(now)) {
            return EvidenceStagingReserveResult(
                EvidenceStagingReserveStatus.RECOVERY_IN_PROGRESS,
            )
        }
        if (
            existing.reservation != null &&
            (
                existing.reservation.rawEventId != requested.rawEventId ||
                    existing.reservation.contentHash != requested.contentHash ||
                    existing.reservation.payloadSizeBytes != requested.payloadSizeBytes
                )
        ) {
            return EvidenceStagingReserveResult(EvidenceStagingReserveStatus.ID_COLLISION)
        }
        entries[requested.payloadId] = FakeStagingEntry(requested)
        return EvidenceStagingReserveResult(
            EvidenceStagingReserveStatus.ALREADY_RESERVED,
            requested,
        )
    }

    override suspend fun storageSummary(): EvidenceStagingStorageSummary =
        EvidenceStagingStorageSummary(
            stagedCount = entries.size.toLong(),
            stagedBytes = entries.values.sumOf {
                it.reservation?.payloadSizeBytes ?: 0L
            },
            unknownSizeCount = entries.values.count {
                it.reservation == null
            }.toLong(),
        )

    override suspend fun knownPayloadIds(limit: Int): EvidenceKnownPayloadIds {
        val ids = (trackedPayloadIds + entries.keys).take(limit + 1)
        return EvidenceKnownPayloadIds(
            payloadIds = ids.take(limit).toSet(),
            truncated = ids.size > limit,
        )
    }

    override suspend fun expiredLeases(
        now: Instant,
        limit: Int,
    ): List<EvidenceStagingExpiredLease> = entries.mapNotNull { (payloadId, entry) ->
        entry.takeIf { !it.expiresAt.isAfter(now) }?.let {
            EvidenceStagingExpiredLease(payloadId, it.leaseId, it.expiresAt)
        }
    }.take(limit)

    override suspend fun claimExpired(
        expected: EvidenceStagingExpiredLease,
        recoveryLeaseId: EvidenceStagingLeaseId,
        claimedAt: Instant,
        expiresAt: Instant,
    ): EvidenceStagingClaimResult {
        val current = entries[expected.payloadId]
            ?: return EvidenceStagingClaimResult(EvidenceStagingClaimStatus.STALE)
        if (
            current.leaseId != expected.leaseId ||
            current.expiresAt != expected.expiresAt ||
            current.expiresAt.isAfter(claimedAt)
        ) {
            return EvidenceStagingClaimResult(EvidenceStagingClaimStatus.STALE)
        }
        if (expected.payloadId in trackedPayloadIds) {
            entries.remove(expected.payloadId)
            return EvidenceStagingClaimResult(
                EvidenceStagingClaimStatus.TRACKED_FINALIZED,
            )
        }
        entries[expected.payloadId] = FakeStagingEntry(
            reservation = current.reservation,
            leaseId = recoveryLeaseId,
            expiresAt = expiresAt,
            recovery = true,
        )
        return EvidenceStagingClaimResult(
            EvidenceStagingClaimStatus.CLAIMED,
            EvidenceStagingRecoveryWork(expected.payloadId, recoveryLeaseId),
        )
    }

    override suspend fun claimOrphan(
        payloadId: PayloadId,
        recoveryLeaseId: EvidenceStagingLeaseId,
        claimedAt: Instant,
        expiresAt: Instant,
    ): EvidenceOrphanClaimResult {
        if (payloadId in trackedPayloadIds) {
            return EvidenceOrphanClaimResult(EvidenceOrphanClaimStatus.TRACKED)
        }
        if (payloadId in entries) {
            return EvidenceOrphanClaimResult(EvidenceOrphanClaimStatus.RESERVED)
        }
        entries[payloadId] = FakeStagingEntry(
            reservation = null,
            leaseId = recoveryLeaseId,
            expiresAt = expiresAt,
            recovery = true,
        )
        return EvidenceOrphanClaimResult(
            EvidenceOrphanClaimStatus.CLAIMED,
            EvidenceStagingRecoveryWork(payloadId, recoveryLeaseId),
        )
    }

    override suspend fun completeRecovery(
        work: EvidenceStagingRecoveryWork,
    ): RepositoryWriteResult {
        val current = entries[work.payloadId]
            ?: return writeResult(RepositoryWriteStatus.ALREADY_APPLIED)
        if (!current.recovery || current.leaseId != work.leaseId) {
            return writeResult(RepositoryWriteStatus.INVALID_STATE)
        }
        entries.remove(work.payloadId)
        return writeResult(RepositoryWriteStatus.APPLIED, work.payloadId.value)
    }

    override suspend fun release(
        reservation: EvidenceStagingReservation,
    ): RepositoryWriteResult {
        val current = entries[reservation.payloadId]
            ?: return writeResult(RepositoryWriteStatus.ALREADY_APPLIED)
        if (current.recovery || current.leaseId != reservation.leaseId) {
            return writeResult(RepositoryWriteStatus.INVALID_STATE)
        }
        entries.remove(reservation.payloadId)
        return writeResult(RepositoryWriteStatus.APPLIED, reservation.payloadId.value)
    }
}

private class FakeArtifactStore(
    private val payloadIds: Set<PayloadId>,
) : EvidenceArtifactStore {
    override suspend fun scanOrphanCandidates(
        olderThanOrEqualTo: Instant,
        excludedPayloadIds: Set<PayloadId>,
        limit: Int,
        inspectionLimit: Int,
    ): EvidenceArtifactScanResult {
        val candidates = payloadIds
            .filterNot { it in excludedPayloadIds }
            .take(limit)
        return EvidenceArtifactScanResult(
            payloadIds = candidates,
            inspectedEntryCount = payloadIds.size.coerceAtMost(inspectionLimit),
            truncated = payloadIds.size > inspectionLimit ||
                payloadIds.count { it !in excludedPayloadIds } > limit,
        )
    }
}

private class FakeLifecycleRepository(
    retentionDays: Int?,
) : SourceEvidenceLifecycleRepository {
    val items = linkedMapOf<RawEventId, SourceEvidenceItem>()
    private val clearWork = linkedMapOf<RawEventId, EvidenceClearWork>()
    private var policy = SourceEvidencePolicy(retentionDays, Instant.EPOCH)
    private val overview = MutableStateFlow(buildOverview())

    fun add(item: SourceEvidenceItem) {
        items[item.rawEventId] = item
        publish()
    }

    override fun observeOverview(): Flow<SourceEvidenceOverview> = overview

    override suspend fun getOverview(): SourceEvidenceOverview = buildOverview()

    override suspend fun page(
        limit: Int,
        cursor: SourceEvidenceCursor?,
    ): SourceEvidencePage {
        val sorted = items.values.sortedWith(
            compareByDescending<SourceEvidenceItem> { it.capturedAt }
                .thenByDescending { it.rawEventId.value },
        )
        val filtered = cursor?.let { key ->
            sorted.filter {
                it.capturedAt < key.capturedAt ||
                    (it.capturedAt == key.capturedAt && it.rawEventId.value < key.rawEventId.value)
            }
        } ?: sorted
        val pageItems = filtered.take(limit)
        return SourceEvidencePage(
            items = pageItems,
            nextCursor = if (filtered.size > limit) {
                pageItems.last().let { SourceEvidenceCursor(it.capturedAt, it.rawEventId) }
            } else {
                null
            },
        )
    }

    override suspend fun find(rawEventId: RawEventId): SourceEvidenceItem? = items[rawEventId]

    override suspend fun findByPayloadId(payloadId: PayloadId): SourceEvidenceItem? =
        items.values.firstOrNull { it.payloadId == payloadId }

    override suspend fun updateRetentionPolicy(
        commandId: CommandId,
        retentionDays: Int?,
        updatedAt: Instant,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        policy = SourceEvidencePolicy(retentionDays, updatedAt)
        publish()
        return writeResult(RepositoryWriteStatus.APPLIED, "default")
    }

    override suspend fun requestClear(
        commandId: CommandId,
        rawEventId: RawEventId,
        reason: EvidenceClearReason,
        requestedAt: Instant,
        auditRecord: AuditRecord,
    ): EvidenceClearRequestResult {
        val item = items[rawEventId]
            ?: return EvidenceClearRequestResult(EvidenceClearRequestStatus.NOT_FOUND)
        if (item.state == EvidencePayloadState.CLEARED) {
            return EvidenceClearRequestResult(EvidenceClearRequestStatus.ALREADY_CLEARED)
        }
        if (item.state == EvidencePayloadState.CLEAR_PENDING) {
            val work = clearWork[rawEventId]
            return if (work?.commandId == commandId) {
                EvidenceClearRequestResult(
                    EvidenceClearRequestStatus.ALREADY_PENDING,
                    work,
                )
            } else {
                EvidenceClearRequestResult(EvidenceClearRequestStatus.INVALID_STATE)
            }
        }
        val work = EvidenceClearWork(
            rawEventId = rawEventId,
            payloadId = item.payloadId,
            commandId = commandId,
            reason = reason,
            requestedAt = requestedAt,
        )
        clearWork[rawEventId] = work
        items[rawEventId] = item.copy(
            state = EvidencePayloadState.CLEAR_PENDING,
            hasPendingReview = false,
            clearReason = reason,
            clearedAt = null,
        )
        publish()
        return EvidenceClearRequestResult(EvidenceClearRequestStatus.REQUESTED, work)
    }

    override suspend fun markCleared(
        work: EvidenceClearWork,
        clearedAt: Instant,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        val item = items[work.rawEventId]
            ?: return writeResult(RepositoryWriteStatus.NOT_FOUND)
        if (item.state == EvidencePayloadState.CLEARED) {
            return writeResult(RepositoryWriteStatus.ALREADY_APPLIED, work.rawEventId.value)
        }
        if (item.state != EvidencePayloadState.CLEAR_PENDING || clearWork[work.rawEventId] != work) {
            return writeResult(RepositoryWriteStatus.INVALID_STATE)
        }
        items[work.rawEventId] = item.copy(
            state = EvidencePayloadState.CLEARED,
            clearReason = work.reason,
            clearedAt = clearedAt,
        )
        publish()
        return writeResult(RepositoryWriteStatus.APPLIED, work.rawEventId.value)
    }

    override suspend fun pendingClearWork(limit: Int): List<EvidenceClearWork> =
        clearWork.values
            .filter { items[it.rawEventId]?.state == EvidencePayloadState.CLEAR_PENDING }
            .sortedBy { it.requestedAt }
            .take(limit)

    override suspend fun findPendingClearWork(
        rawEventId: RawEventId,
    ): EvidenceClearWork? =
        clearWork[rawEventId]
            ?.takeIf { items[rawEventId]?.state == EvidencePayloadState.CLEAR_PENDING }

    override suspend fun retentionCandidates(
        capturedBefore: Instant,
        limit: Int,
    ): List<SourceEvidenceItem> = candidates()
        .filter { it.capturedAt < capturedBefore }
        .take(limit)

    override suspend fun capacityCandidates(limit: Int): List<SourceEvidenceItem> =
        candidates().take(limit)

    override suspend fun unknownSizeItems(limit: Int): List<SourceEvidenceItem> =
        items.values
            .filter {
                it.state != EvidencePayloadState.CLEARED && it.payloadSizeBytes == null
            }
            .sortedBy { it.capturedAt }
            .take(limit)

    override suspend fun recordMeasuredSize(
        rawEventId: RawEventId,
        payloadSizeBytes: Long,
    ): RepositoryWriteResult {
        val item = items[rawEventId] ?: return writeResult(RepositoryWriteStatus.NOT_FOUND)
        if (item.state != EvidencePayloadState.AVAILABLE) {
            return writeResult(RepositoryWriteStatus.INVALID_STATE)
        }
        items[rawEventId] = item.copy(payloadSizeBytes = payloadSizeBytes)
        publish()
        return writeResult(RepositoryWriteStatus.APPLIED, rawEventId.value)
    }

    private fun candidates(): List<SourceEvidenceItem> = items.values
        .filter {
            it.state == EvidencePayloadState.AVAILABLE && !it.hasPendingReview
        }
        .sortedWith(compareBy<SourceEvidenceItem> { it.capturedAt }.thenBy { it.rawEventId.value })

    private fun buildOverview(): SourceEvidenceOverview {
        val stored = items.values.filter { it.state != EvidencePayloadState.CLEARED }
        return SourceEvidenceOverview(
            policy = policy,
            storage = SourceEvidenceStorageSummary(
                storedCount = stored.size.toLong(),
                storedBytes = stored.sumOf { it.payloadSizeBytes ?: 0L },
                unknownSizeCount = stored.count { it.payloadSizeBytes == null }.toLong(),
                clearPendingCount = stored.count {
                    it.state == EvidencePayloadState.CLEAR_PENDING
                }.toLong(),
                clearedCount = items.values.count {
                    it.state == EvidencePayloadState.CLEARED
                }.toLong(),
            ),
        )
    }

    private fun publish() {
        overview.value = buildOverview()
    }
}

private fun evidenceItem(
    id: String,
    capturedAt: Instant,
    payloadSizeBytes: Long? = 12L,
    hasPendingReview: Boolean = false,
) = SourceEvidenceItem(
    rawEventId = RawEventId(id),
    payloadId = PayloadId("payload-$id"),
    sourceFamily = SourceFamily.GENERIC,
    captureMethod = CaptureMethod.SHARE_TEXT,
    capturedAt = capturedAt,
    payloadSizeBytes = payloadSizeBytes,
    state = EvidencePayloadState.AVAILABLE,
    hasPendingReview = hasPendingReview,
    clearReason = null,
    clearedAt = null,
)

private fun stagingReservation(
    id: String,
    createdAt: Instant,
    expiresAt: Instant,
) = EvidenceStagingReservation(
    rawEventId = RawEventId("raw-$id"),
    payloadId = PayloadId("payload-$id"),
    contentHash = EvidenceHash(ValidHash),
    payloadSizeBytes = 12L,
    leaseId = EvidenceStagingLeaseId("lease-$id"),
    createdAt = createdAt,
    expiresAt = expiresAt,
)

private fun writeResult(
    status: RepositoryWriteStatus,
    entityId: String? = null,
) = RepositoryWriteResult(status, entityId)

private const val ValidHash =
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
