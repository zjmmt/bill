package dev.bill.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bill.core.domain.AuditAction
import dev.bill.core.domain.AuditEventId
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.core.domain.ReviewDraft
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionType
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventAppendResult
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.VersionId
import dev.bill.source.pipeline.DraftProposal
import dev.bill.source.pipeline.DraftProposalId
import dev.bill.source.pipeline.DraftProposalReviewState
import dev.bill.source.pipeline.ParseAttempt
import dev.bill.source.pipeline.ParseAttemptId
import dev.bill.source.pipeline.ParseCommitResult
import dev.bill.source.review.EvidenceClearReason
import dev.bill.source.review.EvidenceClearRequestStatus
import dev.bill.source.review.EvidencePayloadState
import dev.bill.source.review.EvidenceStagingLeaseId
import dev.bill.source.review.EvidenceStagingReservation
import dev.bill.source.review.EvidenceStagingReserveStatus
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSourceEvidenceLifecycleRepositoryTest {
    private lateinit var database: BillDatabase
    private lateinit var rawRepository: RoomRawEventRepository
    private lateinit var sourceRepository: RoomSourceRepository
    private lateinit var repository: RoomSourceEvidenceLifecycleRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BillDatabase::class.java,
        )
            .addCallback(BillDatabaseCallbacks.EnsureSourceEvidencePolicy)
            .allowMainThreadQueries()
            .build()
        rawRepository = RoomRawEventRepository(database)
        sourceRepository = RoomSourceRepository(database)
        repository = RoomSourceEvidenceLifecycleRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun appendPolicyAndKeysetPageExposeBoundedMetadataOnly() = runBlocking {
        val capturedAt = Instant.parse("2026-07-19T12:00:00Z")
        listOf("event-a", "event-b", "event-c").forEachIndexed { index, id ->
            assertEquals(
                if (index == 0) {
                    RawEventAppendResult.Inserted
                } else {
                    RawEventAppendResult.DuplicateObservation(index)
                },
                appendStaged(rawEvent(id, capturedAt)),
            )
        }

        val overview = repository.getOverview()
        assertEquals(30, overview.policy.retentionDays)
        assertEquals(3L, overview.storage.storedCount)
        assertEquals(36L, overview.storage.storedBytes)
        assertEquals(0L, overview.storage.unknownSizeCount)

        val firstPage = repository.page(limit = 2, cursor = null)
        assertEquals(listOf("event-c", "event-b"), firstPage.items.map { it.rawEventId.value })
        assertNotNull(firstPage.nextCursor)
        val secondPage = repository.page(limit = 2, cursor = firstPage.nextCursor)
        assertEquals(listOf("event-a"), secondPage.items.map { it.rawEventId.value })
        assertNull(secondPage.nextCursor)
        assertTrue(firstPage.items.all { it.payloadSizeBytes == 12L })

        val commandId = CommandId("retention-command")
        val changedAt = Instant.parse("2026-07-19T12:05:00Z")
        val audit = policyAudit(commandId, changedAt)
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.updateRetentionPolicy(commandId, 7, changedAt, audit).status,
        )
        assertEquals(
            RepositoryWriteStatus.ALREADY_APPLIED,
            repository.updateRetentionPolicy(commandId, 7, changedAt, audit).status,
        )
        assertEquals(
            RepositoryWriteStatus.COMMAND_COLLISION,
            repository.updateRetentionPolicy(commandId, 90, changedAt, audit).status,
        )
        assertEquals(7, repository.getOverview().policy.retentionDays)
    }

    @Test
    fun twoPhaseClearDismissesPendingReviewAndIsResumableAndIdempotent() = runBlocking {
        val event = rawEvent("event-clear")
        appendStaged(event)
        createPendingProposal(event)

        val commandId = CommandId("clear-command")
        val requestedAt = Instant.parse("2026-07-19T12:10:00Z")
        val requestAudit = clearRequestAudit(commandId, event.id, requestedAt)
        val requested = repository.requestClear(
            commandId = commandId,
            rawEventId = event.id,
            reason = EvidenceClearReason.USER_REQUEST,
            requestedAt = requestedAt,
            auditRecord = requestAudit,
        )
        val replay = repository.requestClear(
            commandId = commandId,
            rawEventId = event.id,
            reason = EvidenceClearReason.USER_REQUEST,
            requestedAt = requestedAt,
            auditRecord = requestAudit,
        )

        assertEquals(EvidenceClearRequestStatus.REQUESTED, requested.status)
        assertNotNull(requested.work)
        assertEquals(EvidenceClearRequestStatus.ALREADY_PENDING, replay.status)
        assertEquals(requested.work, replay.work)
        assertEquals(
            "DISMISSED",
            database.sourceDao().findProposal("proposal-event-clear")?.state,
        )
        assertEquals(1L, repository.getOverview().storage.clearPendingCount)

        val work = checkNotNull(requested.work)
        assertEquals(work, repository.findPendingClearWork(event.id))
        assertEquals(
            RepositoryWriteStatus.INVALID_STATE,
            repository.recordMeasuredSize(event.id, 12L).status,
        )
        val clearedAt = requestedAt.plusSeconds(1)
        val clearedAudit = clearCompletedAudit(commandId, event.id, clearedAt)
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.markCleared(work, clearedAt, clearedAudit).status,
        )
        assertEquals(
            RepositoryWriteStatus.ALREADY_APPLIED,
            repository.markCleared(work, clearedAt, clearedAudit).status,
        )

        val item = checkNotNull(repository.find(event.id))
        assertEquals(EvidencePayloadState.CLEARED, item.state)
        assertEquals(EvidenceClearReason.USER_REQUEST, item.clearReason)
        assertEquals(clearedAt, item.clearedAt)
        assertFalse(item.hasPendingReview)
        assertEquals(0L, repository.getOverview().storage.storedCount)
        assertEquals(1L, repository.getOverview().storage.clearedCount)
        assertNull(repository.findPendingClearWork(event.id))
        assertEquals(RawEventAppendResult.IdCollision, rawRepository.append(event))

        val lateCommand = CommandId("late-clear-command")
        val lateAt = clearedAt.plusSeconds(1)
        val lateAudit = clearRequestAudit(lateCommand, event.id, lateAt)
        assertEquals(
            EvidenceClearRequestStatus.ALREADY_CLEARED,
            repository.requestClear(
                lateCommand,
                event.id,
                EvidenceClearReason.USER_REQUEST,
                lateAt,
                lateAudit,
            ).status,
        )
        assertEquals(
            EvidenceClearRequestStatus.COMMAND_COLLISION,
            repository.requestClear(
                lateCommand,
                event.id,
                EvidenceClearReason.RETENTION,
                lateAt,
                lateAudit,
            ).status,
        )
    }

    @Test
    fun malformedLifecycleFailsClosedWithoutEchoingStoredPayloadId() = runBlocking {
        val event = rawEvent("event-corrupt")
        appendStaged(event)
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE source_evidence_payloads
            SET state = 'CLEARED'
            WHERE rawEventId = ?
            """.trimIndent(),
            arrayOf(event.id.value),
        )

        val failure = runCatching { repository.getOverview() }.exceptionOrNull()

        assertTrue(failure is LocalDataIntegrityException)
        assertEquals(
            "Local ledger data failed validation: source evidence lifecycle",
            failure?.message,
        )
        assertFalse(failure?.message.orEmpty().contains(event.payloadId.value))
    }

    @Test
    fun blankMatchingPayloadReferencesStillFailClosed() = runBlocking {
        val event = rawEvent("event-blank-payload")
        appendStaged(event)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE raw_events SET payloadReference = '' WHERE id = ?",
            arrayOf(event.id.value),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE source_evidence_payloads SET payloadId = '' WHERE rawEventId = ?",
            arrayOf(event.id.value),
        )

        val failure = runCatching { repository.getOverview() }.exceptionOrNull()

        assertTrue(failure is LocalDataIntegrityException)
        assertEquals(
            "Local ledger data failed validation: source evidence lifecycle",
            failure?.message,
        )
    }

    @Test
    fun clearingPayloadPreservesCompletedDraftAndProvenance() = runBlocking {
        val event = rawEvent("event-completed")
        appendStaged(event)
        createPendingProposal(event)
        val draftTime = event.capturedAt.plusSeconds(60)
        val draftCommand = CommandId("external-draft-command")
        val draft = ReviewDraft(
            id = DraftId("draft:event-completed"),
            state = DraftState.WAITING_USER,
            type = TransactionType.EXPENSE,
            amount = Money.cny(1_200L),
            occurredAt = event.capturedAt,
            counterparty = "fixture",
            note = null,
            fundingAccountId = null,
            createdAt = draftTime,
            updatedAt = draftTime,
            creationCommandId = draftCommand,
            sourceMode = TransactionSourceMode.EXTERNAL,
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            sourceRepository.completeSourceProposal(
                proposalId = "proposal-event-completed",
                draft = draft,
                auditRecord = AuditRecord(
                    id = AuditEventId("audit-external-draft-command"),
                    commandId = draftCommand,
                    action = AuditAction.EXTERNAL_DRAFT_CREATED,
                    entityType = "draft",
                    entityId = draft.id.value,
                    occurredAt = draftTime,
                ),
            ).status,
        )

        val clearCommand = CommandId("clear-completed-command")
        val requestedAt = draftTime.plusSeconds(1)
        val request = repository.requestClear(
            commandId = clearCommand,
            rawEventId = event.id,
            reason = EvidenceClearReason.USER_REQUEST,
            requestedAt = requestedAt,
            auditRecord = clearRequestAudit(clearCommand, event.id, requestedAt),
        )
        val work = checkNotNull(request.work)
        val clearedAt = requestedAt.plusSeconds(1)
        repository.markCleared(
            work,
            clearedAt,
            clearCompletedAudit(clearCommand, event.id, clearedAt),
        )

        assertEquals(
            EvidencePayloadState.CLEARED,
            repository.find(event.id)?.state,
        )
        assertEquals(
            "COMPLETED",
            database.sourceDao().findProposal("proposal-event-completed")?.state,
        )
        assertNotNull(database.sourceDao().findDraftSourceEvidence(draft.id.value))
        assertNotNull(RoomLedgerRepository(database).findDraft(draft.id))
    }

    private suspend fun createPendingProposal(event: RawEvent) {
        val attemptId = ParseAttemptId("attempt-${event.id.value}")
        val attempt = ParseAttempt(
            id = attemptId,
            rawEventId = event.id,
            sourceIdentity = SourceIdentity(
                parserId = ParserId("generic-share-text"),
                providerId = ProviderId("generic"),
                sourceFamily = SourceFamily.GENERIC,
                connectorId = event.connectorId,
                capabilities = emptySet(),
                supportedCaptureMethods = setOf(CaptureMethod.SHARE_TEXT),
                parserVersion = VersionId("parser-1"),
                ruleVersion = VersionId("rules-1"),
            ),
            attemptedAt = event.capturedAt.plusSeconds(1),
            result = ParseResult.NeedsUserReview(
                candidate = null,
                diagnostic = SafeDiagnostic(
                    code = DiagnosticCode.INSUFFICIENT_FIELDS,
                    recoverable = true,
                ),
            ),
        )
        val proposal = DraftProposal(
            id = DraftProposalId("proposal-${event.id.value}"),
            parseAttemptId = attemptId,
            rawEventId = event.id,
            reviewState = DraftProposalReviewState.WAITING_USER,
            candidate = null,
        )
        assertEquals(ParseCommitResult.Committed, sourceRepository.commit(attempt, proposal))
    }

    private suspend fun appendStaged(event: RawEvent): RawEventAppendResult {
        val reservation = EvidenceStagingReservation(
            rawEventId = event.id,
            payloadId = event.payloadId,
            contentHash = event.contentHash,
            payloadSizeBytes = requireNotNull(event.payloadSizeBytes),
            leaseId = EvidenceStagingLeaseId("lease-${event.id.value}"),
            createdAt = event.capturedAt,
            expiresAt = event.capturedAt.plusSeconds(300),
        )
        assertEquals(
            EvidenceStagingReserveStatus.RESERVED,
            RoomSourceEvidenceStagingRepository(database).reserve(
                reservation,
                event.capturedAt,
            ).status,
        )
        return rawRepository.append(event)
    }

    private fun rawEvent(
        id: String,
        capturedAt: Instant = Instant.parse("2026-07-19T12:00:00Z"),
    ) = RawEvent(
        id = RawEventId(id),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("android-share-text"),
        captureMethod = CaptureMethod.SHARE_TEXT,
        captureScope = CaptureScopeId("local-install"),
        contentHash = EvidenceHash("a".repeat(64)),
        capturedAt = capturedAt,
        payloadId = PayloadId("payload-$id"),
        payloadSizeBytes = 12L,
    )

    private fun policyAudit(commandId: CommandId, occurredAt: Instant) = AuditRecord(
        id = AuditEventId("audit-policy-${commandId.value}"),
        commandId = commandId,
        action = AuditAction.SOURCE_EVIDENCE_RETENTION_CHANGED,
        entityType = "source_evidence_policy",
        entityId = "default",
        occurredAt = occurredAt,
    )

    private fun clearRequestAudit(
        commandId: CommandId,
        rawEventId: RawEventId,
        occurredAt: Instant,
    ) = AuditRecord(
        id = AuditEventId("audit-clear-request-${commandId.value}"),
        commandId = commandId,
        action = AuditAction.SOURCE_EVIDENCE_CLEAR_REQUESTED,
        entityType = "source_evidence",
        entityId = rawEventId.value,
        occurredAt = occurredAt,
    )

    private fun clearCompletedAudit(
        commandId: CommandId,
        rawEventId: RawEventId,
        occurredAt: Instant,
    ) = AuditRecord(
        id = AuditEventId("audit-cleared-${commandId.value}"),
        commandId = commandId,
        action = AuditAction.SOURCE_EVIDENCE_CLEARED,
        entityType = "source_evidence",
        entityId = rawEventId.value,
        occurredAt = occurredAt,
    )
}
