package dev.bill.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceReadResult
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventAppendResult
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.review.EvidenceDeleteResult
import dev.bill.source.review.EvidenceOrphanClaimStatus
import dev.bill.source.review.EvidenceStageResult
import dev.bill.source.review.EvidenceStagingClaimStatus
import dev.bill.source.review.EvidenceStagingLeaseId
import dev.bill.source.review.EvidenceStagingReservation
import dev.bill.source.review.EvidenceStagingReserveStatus
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSourceEvidenceStagingRepositoryTest {
    private lateinit var database: BillDatabase
    private lateinit var repository: RoomSourceEvidenceStagingRepository
    private lateinit var rawEventRepository: RoomRawEventRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BillDatabase::class.java,
        )
            .addCallback(BillDatabaseCallbacks.EnsureSourceEvidencePolicy)
            .allowMainThreadQueries()
            .build()
        repository = RoomSourceEvidenceStagingRepository(database)
        rawEventRepository = RoomRawEventRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun shareTextAppendRequiresAndAtomicallyConsumesActiveReservation() = runBlocking {
        val event = shareEvent("staged-append")

        assertEquals(RawEventAppendResult.IdCollision, rawEventRepository.append(event))
        assertNull(database.rawEventDao().findById(event.id.value))

        val reserved = repository.reserve(
            reservation(event, "lease-one"),
            BaseTime,
        )
        assertEquals(EvidenceStagingReserveStatus.RESERVED, reserved.status)
        assertEquals(1L, repository.storageSummary().stagedCount)

        assertEquals(RawEventAppendResult.Inserted, rawEventRepository.append(event))
        assertNull(database.sourceEvidenceStagingDao().findByPayloadId(event.payloadId.value))
        assertEquals(
            event.payloadSizeBytes,
            database.sourceEvidenceDao().findPayload(event.id.value)?.payloadSizeBytes,
        )
        assertEquals(RawEventAppendResult.AlreadyPresent, rawEventRepository.append(event))
    }

    @Test
    fun statementImportAppendAlsoRequiresAndAtomicallyConsumesActiveReservation() = runBlocking {
        val event = statementImportEvent("staged-statement-import")

        assertEquals(RawEventAppendResult.IdCollision, rawEventRepository.append(event))
        val reserved = repository.reserve(
            reservation(event, "lease-statement-import"),
            BaseTime,
        )
        assertEquals(EvidenceStagingReserveStatus.RESERVED, reserved.status)

        assertEquals(RawEventAppendResult.Inserted, rawEventRepository.append(event))
        assertNull(database.sourceEvidenceStagingDao().findByPayloadId(event.payloadId.value))
        assertEquals(
            event.payloadSizeBytes,
            database.sourceEvidenceDao().findPayload(event.id.value)?.payloadSizeBytes,
        )
    }

    @Test
    fun renewedReservationCannotBeClaimedUsingAnExpiredLeaseSnapshot() = runBlocking {
        val event = shareEvent("renewed-reservation")
        val first = reservation(event, "lease-first")
        repository.reserve(first, BaseTime)
        val expiredSnapshot = repository.expiredLeases(
            now = first.expiresAt,
            limit = 10,
        ).single()

        val renewedRequest = reservation(
            event = event,
            leaseId = "lease-renewed",
            createdAt = BaseTime.plusSeconds(60),
        )
        val renewed = repository.reserve(
            renewedRequest,
            renewedRequest.createdAt,
        )
        assertEquals(EvidenceStagingReserveStatus.ALREADY_RESERVED, renewed.status)

        val staleClaim = repository.claimExpired(
            expected = expiredSnapshot,
            recoveryLeaseId = EvidenceStagingLeaseId("recovery-stale"),
            claimedAt = first.expiresAt.plusSeconds(1),
            expiresAt = first.expiresAt.plusSeconds(301),
        )
        assertEquals(EvidenceStagingClaimStatus.STALE, staleClaim.status)
        assertEquals(RawEventAppendResult.Inserted, rawEventRepository.append(event))
    }

    @Test
    fun claimedExpiredReservationBlocksLateRawCommitUntilRecoveryFinishes() = runBlocking {
        val event = shareEvent("claimed-expired")
        val reservation = reservation(event, "lease-expiring")
        repository.reserve(reservation, BaseTime)
        val recoveryAt = reservation.expiresAt.plusSeconds(1)
        val expired = repository.expiredLeases(recoveryAt, 10).single()

        val claim = repository.claimExpired(
            expected = expired,
            recoveryLeaseId = EvidenceStagingLeaseId("recovery-owner"),
            claimedAt = recoveryAt,
            expiresAt = recoveryAt.plusSeconds(300),
        )

        assertEquals(EvidenceStagingClaimStatus.CLAIMED, claim.status)
        assertEquals(RawEventAppendResult.IdCollision, rawEventRepository.append(event))
        assertNull(database.rawEventDao().findById(event.id.value))
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.completeRecovery(requireNotNull(claim.work)).status,
        )
        assertEquals(0L, repository.storageSummary().stagedCount)
    }

    @Test
    fun orphanClaimNeverTakesATrackedOrReservedPayload() = runBlocking {
        val tracked = shareEvent("tracked-payload")
        repository.reserve(reservation(tracked, "lease-tracked"), BaseTime)
        assertEquals(RawEventAppendResult.Inserted, rawEventRepository.append(tracked))

        val reserved = shareEvent("reserved-payload")
        repository.reserve(reservation(reserved, "lease-reserved"), BaseTime)

        val trackedClaim = repository.claimOrphan(
            payloadId = tracked.payloadId,
            recoveryLeaseId = EvidenceStagingLeaseId("recovery-tracked"),
            claimedAt = BaseTime,
            expiresAt = BaseTime.plusSeconds(300),
        )
        val reservedClaim = repository.claimOrphan(
            payloadId = reserved.payloadId,
            recoveryLeaseId = EvidenceStagingLeaseId("recovery-reserved"),
            claimedAt = BaseTime,
            expiresAt = BaseTime.plusSeconds(300),
        )
        val orphanClaim = repository.claimOrphan(
            payloadId = PayloadId("payload-untracked-orphan"),
            recoveryLeaseId = EvidenceStagingLeaseId("recovery-orphan"),
            claimedAt = BaseTime,
            expiresAt = BaseTime.plusSeconds(300),
        )

        assertEquals(EvidenceOrphanClaimStatus.TRACKED, trackedClaim.status)
        assertEquals(EvidenceOrphanClaimStatus.RESERVED, reservedClaim.status)
        assertEquals(EvidenceOrphanClaimStatus.CLAIMED, orphanClaim.status)
    }

    @Test
    fun orphanClaimCanRecoverResidualArtifactForClearedLifecycle() = runBlocking {
        val cleared = shareEvent("cleared-residual")
        repository.reserve(reservation(cleared, "lease-cleared"), BaseTime)
        assertEquals(RawEventAppendResult.Inserted, rawEventRepository.append(cleared))
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE source_evidence_payloads
            SET state = 'CLEARED',
                clearCommandId = 'test-clear',
                clearReason = 'USER_REQUEST',
                clearRequestedAtEpochMillis = 1,
                clearedAtEpochMillis = 2
            WHERE payloadId = ?
            """.trimIndent(),
            arrayOf(cleared.payloadId.value),
        )

        assertTrue(
            cleared.payloadId !in repository.knownPayloadIds(limit = 100).payloadIds,
        )
        val claim = repository.claimOrphan(
            payloadId = cleared.payloadId,
            recoveryLeaseId = EvidenceStagingLeaseId("recovery-cleared"),
            claimedAt = BaseTime,
            expiresAt = BaseTime.plusSeconds(300),
        )

        assertEquals(EvidenceOrphanClaimStatus.CLAIMED, claim.status)
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.completeRecovery(requireNotNull(claim.work)).status,
        )
    }

    @Test
    fun reconstructedComponentsDiscardExpiredPrecommitFileButKeepCommittedFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "source-evidence-staging-restart-test.db"
        val precommitPayload = "precommit000".toByteArray()
        val committedPayload = "committed000".toByteArray()
        val precommit = shareEvent("process-death-precommit", precommitPayload)
        val committed = shareEvent("process-death-committed", committedPayload)
        val firstStore = AppPrivateEvidenceStore(context)
        context.deleteDatabase(databaseName)
        listOf(precommit.payloadId, committed.payloadId).forEach {
            assertEquals(EvidenceDeleteResult.DeletedOrAbsent, firstStore.delete(it))
        }

        var initialDatabase: BillDatabase? = Room.databaseBuilder(
            context,
            BillDatabase::class.java,
            databaseName,
        )
            .addCallback(BillDatabaseCallbacks.EnsureSourceEvidencePolicy)
            .allowMainThreadQueries()
            .build()
        var restartedDatabase: BillDatabase? = null
        try {
            val initialRepository = RoomSourceEvidenceStagingRepository(
                requireNotNull(initialDatabase),
            )
            val initialRawEvents = RoomRawEventRepository(requireNotNull(initialDatabase))
            initialRepository.reserve(
                reservation(precommit, "lease-precommit"),
                BaseTime,
            )
            initialRepository.reserve(
                reservation(committed, "lease-committed"),
                BaseTime,
            )
            assertEquals(
                EvidenceStageResult.Stored,
                firstStore.stage(
                    precommit.payloadId,
                    "text/plain",
                    precommitPayload,
                    1024,
                ),
            )
            assertEquals(
                EvidenceStageResult.Stored,
                firstStore.stage(
                    committed.payloadId,
                    "text/plain",
                    committedPayload,
                    1024,
                ),
            )
            assertEquals(
                RawEventAppendResult.Inserted,
                initialRawEvents.append(committed),
            )

            requireNotNull(initialDatabase).close()
            initialDatabase = null
            restartedDatabase = Room.databaseBuilder(
                context,
                BillDatabase::class.java,
                databaseName,
            )
                .addCallback(BillDatabaseCallbacks.EnsureSourceEvidencePolicy)
                .allowMainThreadQueries()
                .build()
            val reconstructedRepository = RoomSourceEvidenceStagingRepository(
                requireNotNull(restartedDatabase),
            )
            val reconstructedStore = AppPrivateEvidenceStore(context)
            val recoveryAt = BaseTime.plusSeconds(301)
            val expired = reconstructedRepository.expiredLeases(recoveryAt, 10).single()
            val claim = reconstructedRepository.claimExpired(
                expected = expired,
                recoveryLeaseId = EvidenceStagingLeaseId("recovery-after-restart"),
                claimedAt = recoveryAt,
                expiresAt = recoveryAt.plusSeconds(300),
            )
            assertEquals(EvidenceStagingClaimStatus.CLAIMED, claim.status)
            assertEquals(
                EvidenceDeleteResult.DeletedOrAbsent,
                reconstructedStore.delete(precommit.payloadId),
            )
            assertEquals(
                RepositoryWriteStatus.APPLIED,
                reconstructedRepository.completeRecovery(requireNotNull(claim.work)).status,
            )

            assertEquals(
                EvidenceReadResult.NotFound,
                reconstructedStore.read(precommit.payloadId, 1024),
            )
            assertTrue(
                reconstructedStore.read(committed.payloadId, 1024) is EvidenceReadResult.Found,
            )
            assertEquals(
                EvidenceOrphanClaimStatus.TRACKED,
                reconstructedRepository.claimOrphan(
                    payloadId = committed.payloadId,
                    recoveryLeaseId = EvidenceStagingLeaseId("recovery-committed"),
                    claimedAt = recoveryAt,
                    expiresAt = recoveryAt.plusSeconds(300),
                ).status,
            )
        } finally {
            initialDatabase?.close()
            restartedDatabase?.close()
            firstStore.delete(precommit.payloadId)
            firstStore.delete(committed.payloadId)
            context.deleteDatabase(databaseName)
        }
    }

    private fun shareEvent(
        id: String,
        payload: ByteArray = id.toByteArray(),
    ) = RawEvent(
        id = RawEventId("raw-$id"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("android-share-text"),
        captureMethod = CaptureMethod.SHARE_TEXT,
        captureScope = CaptureScopeId("local-install"),
        contentHash = EvidenceHash(sha256(payload)),
        capturedAt = BaseTime,
        payloadId = PayloadId("payload-$id"),
        payloadSizeBytes = payload.size.toLong(),
    )

    private fun statementImportEvent(
        id: String,
        payload: ByteArray = id.toByteArray(),
    ) = RawEvent(
        id = RawEventId("raw-$id"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("android-saf-text-file"),
        captureMethod = CaptureMethod.STATEMENT_IMPORT,
        captureScope = CaptureScopeId("local-install"),
        contentHash = EvidenceHash(sha256(payload)),
        capturedAt = BaseTime,
        payloadId = PayloadId("payload-$id"),
        payloadSizeBytes = payload.size.toLong(),
    )

    private fun sha256(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString(separator = "") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    private fun reservation(
        event: RawEvent,
        leaseId: String,
        createdAt: Instant = BaseTime,
    ) = EvidenceStagingReservation(
        rawEventId = event.id,
        payloadId = event.payloadId,
        contentHash = event.contentHash,
        payloadSizeBytes = requireNotNull(event.payloadSizeBytes),
        leaseId = EvidenceStagingLeaseId(leaseId),
        createdAt = createdAt,
        expiresAt = createdAt.plusSeconds(300),
    )

    private companion object {
        val BaseTime: Instant = Instant.parse("2026-07-25T12:00:00Z")
    }
}
