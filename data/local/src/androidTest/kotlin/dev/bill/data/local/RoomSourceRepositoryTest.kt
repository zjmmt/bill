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
import dev.bill.core.model.CurrencyCode
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
import dev.bill.source.review.EvidenceStagingLeaseId
import dev.bill.source.review.EvidenceStagingReservation
import dev.bill.source.review.EvidenceStagingReserveStatus
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSourceRepositoryTest {
    private lateinit var database: BillDatabase
    private lateinit var sourceRepository: RoomSourceRepository
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
        sourceRepository = RoomSourceRepository(database)
        rawEventRepository = RoomRawEventRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun commitPersistsNullCandidateWorkItemAndRetryIsIdempotent() = runBlocking {
        appendRawEvent(rawEvent())
        val (attempt, proposal) = attemptAndProposal()

        assertEquals(ParseCommitResult.Committed, sourceRepository.commit(attempt, proposal))
        assertEquals(
            ParseCommitResult.AlreadyCommitted,
            sourceRepository.commit(
                attempt.copy(attemptedAt = attempt.attemptedAt.plusSeconds(30)),
                proposal,
            ),
        )

        val records = sourceRepository.observePendingSourceProposals().first()
        assertEquals(1, records.size)
        assertEquals(proposal.id.value, records.single().id)
        assertEquals(attempt.id.value, records.single().parseAttemptId)
        assertEquals("generic-share-text", records.single().parserId)
        assertEquals("generic", records.single().providerId)
        assertEquals("android-share-text", records.single().connectorId)
        assertEquals(null, records.single().candidate)
        assertEquals(DiagnosticCode.INSUFFICIENT_FIELDS, records.single().diagnostic?.code)
        assertFalse(records.single().isPossibleDuplicate)
    }

    @Test
    fun matchingEvidenceObservationIsExposedAsAPossibleDuplicate() = runBlocking {
        appendRawEvent(rawEvent())
        val (attempt, proposal) = attemptAndProposal()
        sourceRepository.commit(attempt, proposal)
        appendRawEvent(rawEvent(id = "source-event-2"))

        val record = sourceRepository.observePendingSourceProposals().first().single()

        assertTrue(record.isPossibleDuplicate)
    }

    @Test
    fun sameAttemptIdWithDifferentResultFailsAsCollision() = runBlocking {
        appendRawEvent(rawEvent())
        val (attempt, proposal) = attemptAndProposal()
        sourceRepository.commit(attempt, proposal)
        val changed = attempt.copy(
            result = ParseResult.NeedsUserReview(
                candidate = null,
                diagnostic = SafeDiagnostic(
                    code = DiagnosticCode.MALFORMED_EVIDENCE,
                    recoverable = false,
                ),
            ),
        )

        assertEquals(
            ParseCommitResult.KeyCollision,
            sourceRepository.commit(changed, proposal),
        )
    }

    @Test
    fun completingProposalAtomicallyCreatesExternalDraftAndProvenance() = runBlocking {
        appendRawEvent(rawEvent())
        val (attempt, proposal) = attemptAndProposal()
        sourceRepository.commit(attempt, proposal)
        val draft = externalDraft()
        val audit = externalAudit(draft)

        val first = sourceRepository.completeSourceProposal(proposal.id.value, draft, audit)
        val replay = sourceRepository.completeSourceProposal(proposal.id.value, draft, audit)

        assertEquals(RepositoryWriteStatus.APPLIED, first.status)
        assertEquals(RepositoryWriteStatus.ALREADY_APPLIED, replay.status)
        assertTrue(sourceRepository.observePendingSourceProposals().first().isEmpty())
        assertEquals(
            TransactionSourceMode.EXTERNAL,
            RoomLedgerRepository(database).findDraft(draft.id)?.sourceMode,
        )
        val evidence = database.sourceDao().findDraftSourceEvidence(draft.id.value)
        assertNotNull(evidence)
        assertEquals(proposal.id.value, evidence?.proposalId)
        assertEquals(attempt.rawEventId.value, evidence?.rawEventId)
        assertEquals(attempt.id.value, evidence?.parseAttemptId)
    }

    @Test
    fun alipayProposalRejectsAnUsdExternalDraftBeforePersistingIt() = runBlocking {
        appendRawEvent(rawEvent(sourceFamily = SourceFamily.ALIPAY))
        val (attempt, proposal) = attemptAndProposal(sourceFamily = SourceFamily.ALIPAY)
        sourceRepository.commit(attempt, proposal)
        val draft = externalDraft(currency = CurrencyCode.USD)

        val result = sourceRepository.completeSourceProposal(
            proposal.id.value,
            draft,
            externalAudit(draft),
        )

        assertEquals(RepositoryWriteStatus.INVALID_STATE, result.status)
        assertEquals(1, sourceRepository.observePendingSourceProposals().first().size)
        assertEquals(null, RoomLedgerRepository(database).findDraft(draft.id))
    }

    @Test
    fun dismissingProposalIsAuditedIdempotentAndPreventsLaterCompletion() = runBlocking {
        appendRawEvent(rawEvent())
        val (attempt, proposal) = attemptAndProposal()
        sourceRepository.commit(attempt, proposal)
        val audit = AuditRecord(
            id = AuditEventId("audit-dismiss-proposal"),
            commandId = CommandId("dismiss-proposal"),
            action = AuditAction.SOURCE_PROPOSAL_DISMISSED,
            entityType = "source_proposal",
            entityId = proposal.id.value,
            occurredAt = Instant.parse("2026-07-19T12:05:00Z"),
        )

        val first = sourceRepository.dismissSourceProposal(proposal.id.value, audit)
        val replay = sourceRepository.dismissSourceProposal(proposal.id.value, audit)
        val lateCompletion = sourceRepository.completeSourceProposal(
            proposal.id.value,
            externalDraft(),
            externalAudit(externalDraft()),
        )

        assertEquals(RepositoryWriteStatus.APPLIED, first.status)
        assertEquals(RepositoryWriteStatus.ALREADY_APPLIED, replay.status)
        assertEquals(RepositoryWriteStatus.INVALID_STATE, lateCompletion.status)
        assertTrue(sourceRepository.observePendingSourceProposals().first().isEmpty())
        assertEquals("DISMISSED", database.sourceDao().findProposal(proposal.id.value)?.state)
    }

    @Test
    fun invalidCandidatePayloadFailsClosedWithoutEchoingStoredBytes() = runBlocking {
        appendRawEvent(rawEvent())
        val (attempt, proposal) = attemptAndProposal()
        sourceRepository.commit(attempt, proposal)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE parse_attempts SET candidatePayload = ? WHERE id = ?",
            arrayOf(byteArrayOf(0x01, 0x02), attempt.id.value),
        )

        val failure = runCatching {
            sourceRepository.observePendingSourceProposals().first()
        }.exceptionOrNull()

        assertTrue(failure is LocalDataIntegrityException)
        assertEquals("Local ledger data failed validation: source proposal", failure?.message)
        assertFalse(failure?.message.orEmpty().contains("1, 2"))
    }

    @Test
    fun proposalWhoseRawEventDiffersFromItsParseAttemptFailsClosed() = runBlocking {
        appendRawEvent(rawEvent())
        appendRawEvent(
            rawEvent(
                id = "source-event-2",
                contentHash = "b".repeat(64),
            ),
        )
        val (attempt, proposal) = attemptAndProposal()
        sourceRepository.commit(attempt, proposal)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE source_draft_proposals SET rawEventId = ? WHERE id = ?",
            arrayOf("source-event-2", proposal.id.value),
        )

        val failure = runCatching {
            sourceRepository.observePendingSourceProposals().first()
        }.exceptionOrNull()

        assertTrue(failure is LocalDataIntegrityException)
    }

    @Test
    fun ledgerRejectsAnExternalDraftWhoseEvidenceChainWasRewired() = runBlocking {
        appendRawEvent(rawEvent())
        appendRawEvent(
            rawEvent(
                id = "source-event-2",
                contentHash = "b".repeat(64),
            ),
        )
        val (attempt, proposal) = attemptAndProposal()
        sourceRepository.commit(attempt, proposal)
        val draft = externalDraft()
        sourceRepository.completeSourceProposal(
            proposal.id.value,
            draft,
            externalAudit(draft),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE draft_source_evidence SET rawEventId = ? WHERE draftId = ?",
            arrayOf("source-event-2", draft.id.value),
        )

        val ledger = RoomLedgerRepository(database)
        val directFailure = runCatching { ledger.findDraft(draft.id) }.exceptionOrNull()
        val observationFailure = runCatching { ledger.observeState().first() }.exceptionOrNull()

        assertTrue(directFailure is LocalDataIntegrityException)
        assertTrue(observationFailure is LocalDataIntegrityException)
    }

    private fun rawEvent(
        id: String = "source-event-1",
        contentHash: String = "a".repeat(64),
        sourceFamily: SourceFamily = SourceFamily.GENERIC,
    ) = RawEvent(
        id = RawEventId(id),
        sourceFamily = sourceFamily,
        connectorId = ConnectorId("android-share-text"),
        captureMethod = CaptureMethod.SHARE_TEXT,
        captureScope = CaptureScopeId("local-install"),
        contentHash = EvidenceHash(contentHash),
        capturedAt = Instant.parse("2026-07-19T12:00:00Z"),
        payloadId = PayloadId("source-payload-$id"),
        payloadSizeBytes = 12L,
    )

    private suspend fun appendRawEvent(event: RawEvent) {
        val reservation = EvidenceStagingReservation(
            rawEventId = event.id,
            payloadId = event.payloadId,
            contentHash = event.contentHash,
            payloadSizeBytes = requireNotNull(event.payloadSizeBytes),
            leaseId = EvidenceStagingLeaseId("lease-${event.id.value}"),
            createdAt = event.capturedAt,
            expiresAt = event.capturedAt.plusSeconds(300),
        )
        val reserved = RoomSourceEvidenceStagingRepository(database).reserve(
            reservation,
            event.capturedAt,
        )
        assertTrue(
            reserved.status == EvidenceStagingReserveStatus.RESERVED ||
                reserved.status == EvidenceStagingReserveStatus.ALREADY_TRACKED,
        )
        rawEventRepository.append(event)
    }

    private fun attemptAndProposal(
        sourceFamily: SourceFamily = SourceFamily.GENERIC,
    ): Pair<ParseAttempt, DraftProposal> {
        val attemptId = ParseAttemptId("attempt-1")
        val attempt = ParseAttempt(
            id = attemptId,
            rawEventId = RawEventId("source-event-1"),
            sourceIdentity = SourceIdentity(
                parserId = ParserId("generic-share-text"),
                providerId = ProviderId("generic"),
                sourceFamily = sourceFamily,
                connectorId = ConnectorId("android-share-text"),
                capabilities = emptySet(),
                supportedCaptureMethods = setOf(CaptureMethod.SHARE_TEXT),
                parserVersion = VersionId("parser-1"),
                ruleVersion = VersionId("rules-1"),
            ),
            attemptedAt = Instant.parse("2026-07-19T12:00:01Z"),
            result = ParseResult.NeedsUserReview(
                candidate = null,
                diagnostic = SafeDiagnostic(
                    code = DiagnosticCode.INSUFFICIENT_FIELDS,
                    recoverable = true,
                ),
            ),
        )
        return attempt to DraftProposal(
            id = DraftProposalId("proposal-1"),
            parseAttemptId = attemptId,
            rawEventId = attempt.rawEventId,
            reviewState = DraftProposalReviewState.WAITING_USER,
            candidate = null,
        )
    }

    private fun externalDraft(
        currency: CurrencyCode = CurrencyCode.CNY,
    ): ReviewDraft {
        val now = Instant.parse("2026-07-19T12:05:00Z")
        return ReviewDraft(
            id = DraftId("draft:external-command"),
            state = DraftState.WAITING_USER,
            type = TransactionType.EXPENSE,
            amount = Money(2_500, currency),
            occurredAt = Instant.parse("2026-07-19T12:00:00Z"),
            counterparty = "午饭",
            note = null,
            fundingAccountId = null,
            createdAt = now,
            updatedAt = now,
            creationCommandId = CommandId("external-command"),
            sourceMode = TransactionSourceMode.EXTERNAL,
        )
    }

    private fun externalAudit(draft: ReviewDraft) = AuditRecord(
        id = AuditEventId("audit-external-command"),
        commandId = draft.creationCommandId,
        action = AuditAction.EXTERNAL_DRAFT_CREATED,
        entityType = "draft",
        entityId = draft.id.value,
        occurredAt = draft.createdAt,
    )
}
