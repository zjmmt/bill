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
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.ObservedChannel
import dev.bill.core.domain.PostedTransaction
import dev.bill.core.domain.ReconciliationDraftLink
import dev.bill.core.domain.ReconciliationDraftRole
import dev.bill.core.domain.ReconciliationKind
import dev.bill.core.domain.ReconciliationResolution
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.core.domain.ReviewDraft
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.domain.TransactionStatus
import dev.bill.core.ledger.PostingBuildResult
import dev.bill.core.ledger.PostingFactory
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.EntryRole
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionId
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
        assertEquals(
            RepositoryWriteStatus.COMMAND_COLLISION,
            sourceRepository.completeSourceProposal(
                proposal.id.value,
                draft.copy(observedChannel = ObservedChannel.ALIPAY),
                audit,
            ).status,
        )
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
    fun editedExternalDraftsFundedByAndVoidPreserveBothEvidenceChains() = runBlocking {
        val ledgerRepository = RoomLedgerRepository(database)
        val base = Instant.parse("2026-07-19T12:00:00Z")
        val bank = LedgerAccount(
            id = AccountId("account:funded-by-bank"),
            name = "TEST BANK",
            normalizedName = "test bank",
            type = AccountType.ASSET_BANK,
            currency = CurrencyCode.CNY,
            isSystem = false,
            isArchived = false,
            createdAt = base,
            creationCommandId = CommandId("create-funded-by-bank"),
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            ledgerRepository.createAccount(
                account = bank,
                openingTransaction = null,
                auditRecords = listOf(
                    audit(
                        commandId = bank.creationCommandId,
                        suffix = "account-created",
                        action = AuditAction.ACCOUNT_CREATED,
                        entityType = "account",
                        entityId = bank.id.value,
                        occurredAt = base,
                    ),
                ),
            ).status,
        )

        suspend fun completeExternalDraft(
            suffix: String,
            contentHash: String,
            occurredAt: Instant,
        ): ReviewDraft {
            appendRawEvent(
                rawEvent(
                    id = "source-event-$suffix",
                    contentHash = contentHash,
                ),
            )
            val (attempt, proposal) = attemptAndProposal(suffix = suffix)
            assertEquals(ParseCommitResult.Committed, sourceRepository.commit(attempt, proposal))
            val draft = externalDraft(
                commandId = "$suffix-external-command",
                occurredAt = occurredAt,
                counterparty = "TEST MERCHANT",
            )
            assertEquals(
                RepositoryWriteStatus.APPLIED,
                sourceRepository.completeSourceProposal(
                    proposal.id.value,
                    draft,
                    externalAudit(draft),
                ).status,
            )
            return draft
        }

        val channelOriginal = completeExternalDraft(
            suffix = "channel",
            contentHash = "a".repeat(64),
            occurredAt = base.plusSeconds(60),
        )
        val bankOriginal = completeExternalDraft(
            suffix = "bank",
            contentHash = "b".repeat(64),
            occurredAt = base.plusSeconds(120),
        )
        val channelEvidence = requireNotNull(
            database.sourceDao().findDraftSourceEvidence(channelOriginal.id.value),
        )
        val bankEvidence = requireNotNull(
            database.sourceDao().findDraftSourceEvidence(bankOriginal.id.value),
        )

        suspend fun editDraft(
            original: ReviewDraft,
            channel: ObservedChannel,
            commandValue: String,
            editedAt: Instant,
        ): ReviewDraft {
            val commandId = CommandId(commandValue)
            val edited = original.copy(
                state = DraftState.EDITED,
                fundingAccountId = bank.id,
                observedChannel = channel,
                updatedAt = editedAt,
            )
            val editAudit = audit(
                commandId = commandId,
                suffix = "draft-edited",
                action = AuditAction.DRAFT_EDITED,
                entityType = "draft",
                entityId = edited.id.value,
                occurredAt = editedAt,
            )
            assertEquals(
                RepositoryWriteStatus.APPLIED,
                ledgerRepository.updateDraft(edited, editAudit).status,
            )
            assertEquals(
                RepositoryWriteStatus.ALREADY_APPLIED,
                ledgerRepository.updateDraft(edited, editAudit).status,
            )
            assertEquals(
                RepositoryWriteStatus.COMMAND_COLLISION,
                ledgerRepository.updateDraft(
                    edited.copy(note = "DIFFERENT RETRY"),
                    editAudit,
                ).status,
            )
            return requireNotNull(ledgerRepository.findDraft(edited.id))
        }

        val channelDraft = editDraft(
            original = channelOriginal,
            channel = ObservedChannel.ALIPAY,
            commandValue = "edit-channel-draft",
            editedAt = base.plusSeconds(360),
        )
        val bankDraft = editDraft(
            original = bankOriginal,
            channel = ObservedChannel.BANK,
            commandValue = "edit-bank-draft",
            editedAt = base.plusSeconds(420),
        )
        assertEquals(
            channelEvidence,
            database.sourceDao().findDraftSourceEvidence(channelDraft.id.value),
        )
        assertEquals(
            bankEvidence,
            database.sourceDao().findDraftSourceEvidence(bankDraft.id.value),
        )

        val confirmedAt = base.plusSeconds(480)
        val commandId = CommandId("resolve-funded-by")
        val validated = (
            PostingFactory.fundedExpense(
                channelDraft = channelDraft,
                bankEvidenceDraft = bankDraft,
                fundingAccount = bank,
                transactionId = TransactionId("transaction:funded-by"),
                confirmedAt = confirmedAt,
            ) as PostingBuildResult.Valid
            ).transaction
        val transaction = PostedTransaction(
            id = validated.id,
            draftId = null,
            type = validated.type,
            status = TransactionStatus.ACTIVE,
            sourceMode = TransactionSourceMode.EXTERNAL,
            occurredAt = validated.occurredAt,
            confirmedAt = confirmedAt,
            title = channelDraft.counterparty,
            note = channelDraft.note,
            commandId = commandId,
            entries = validated.entries,
        )
        val resolution = ReconciliationResolution(
            kind = ReconciliationKind.FUNDED_BY,
            transaction = transaction,
            draftLinks = listOf(
                ReconciliationDraftLink(
                    channelDraft.id,
                    ReconciliationDraftRole.FUNDED_CHANNEL_EXPENSE,
                ),
                ReconciliationDraftLink(
                    bankDraft.id,
                    ReconciliationDraftRole.FUNDED_BANK_EVIDENCE,
                ),
            ),
            relations = emptyList(),
        )
        val resolutionAudit = audit(
            commandId = commandId,
            suffix = "reconciliation-confirmed",
            action = AuditAction.RECONCILIATION_CONFIRMED,
            entityType = "transaction",
            entityId = transaction.id.value,
            occurredAt = confirmedAt,
        )

        assertEquals(
            RepositoryWriteStatus.APPLIED,
            ledgerRepository.resolveReconciliation(resolution, resolutionAudit).status,
        )
        assertEquals(
            RepositoryWriteStatus.ALREADY_APPLIED,
            ledgerRepository.resolveReconciliation(resolution, resolutionAudit).status,
        )
        val reconciled = ledgerRepository.observeState().first()
        assertEquals(Money.cny(-2_500L), reconciled.accountBalances.single().balance)
        assertTrue(reconciled.pendingDrafts.isEmpty())
        assertEquals(1, transaction.entries.count { it.role == EntryRole.EXPENSE })
        assertEquals(1, transaction.entries.count { it.role == EntryRole.FUNDING })
        assertEquals(
            channelEvidence,
            database.sourceDao().findDraftSourceEvidence(channelDraft.id.value),
        )
        assertEquals(
            bankEvidence,
            database.sourceDao().findDraftSourceEvidence(bankDraft.id.value),
        )

        val voidedAt = base.plusSeconds(540)
        val voidCommand = CommandId("void-funded-by")
        val voidAudit = audit(
            commandId = voidCommand,
            suffix = "transaction-voided",
            action = AuditAction.TRANSACTION_VOIDED,
            entityType = "transaction",
            entityId = transaction.id.value,
            occurredAt = voidedAt,
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            ledgerRepository.voidTransaction(transaction.id, voidAudit).status,
        )
        assertEquals(
            RepositoryWriteStatus.ALREADY_APPLIED,
            ledgerRepository.voidTransaction(transaction.id, voidAudit).status,
        )
        val restored = ledgerRepository.observeState().first()
        assertEquals(Money.cny(0L), restored.accountBalances.single().balance)
        assertEquals(
            setOf(channelDraft.id, bankDraft.id),
            restored.pendingDrafts.mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(
            setOf(ObservedChannel.ALIPAY, ObservedChannel.BANK),
            restored.pendingDrafts.mapTo(mutableSetOf()) { it.observedChannel },
        )
        assertEquals(
            TransactionStatus.VOIDED,
            requireNotNull(ledgerRepository.findTransaction(transaction.id)).status,
        )
        assertEquals(
            channelEvidence,
            database.sourceDao().findDraftSourceEvidence(channelDraft.id.value),
        )
        assertEquals(
            bankEvidence,
            database.sourceDao().findDraftSourceEvidence(bankDraft.id.value),
        )
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
        suffix: String = "1",
    ): Pair<ParseAttempt, DraftProposal> {
        val attemptId = ParseAttemptId("attempt-$suffix")
        val rawEventId = RawEventId("source-event-$suffix")
        val attempt = ParseAttempt(
            id = attemptId,
            rawEventId = rawEventId,
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
            id = DraftProposalId("proposal-$suffix"),
            parseAttemptId = attemptId,
            rawEventId = rawEventId,
            reviewState = DraftProposalReviewState.WAITING_USER,
            candidate = null,
        )
    }

    private fun externalDraft(
        currency: CurrencyCode = CurrencyCode.CNY,
        commandId: String = "external-command",
        occurredAt: Instant = Instant.parse("2026-07-19T12:00:00Z"),
        counterparty: String = "午饭",
    ): ReviewDraft {
        val now = occurredAt.plusSeconds(300)
        return ReviewDraft(
            id = DraftId("draft:$commandId"),
            state = DraftState.WAITING_USER,
            type = TransactionType.EXPENSE,
            amount = Money(2_500, currency),
            occurredAt = occurredAt,
            counterparty = counterparty,
            note = null,
            fundingAccountId = null,
            createdAt = now,
            updatedAt = now,
            creationCommandId = CommandId(commandId),
            sourceMode = TransactionSourceMode.EXTERNAL,
        )
    }

    private fun externalAudit(draft: ReviewDraft) = AuditRecord(
        id = AuditEventId("audit-${draft.creationCommandId.value}"),
        commandId = draft.creationCommandId,
        action = AuditAction.EXTERNAL_DRAFT_CREATED,
        entityType = "draft",
        entityId = draft.id.value,
        occurredAt = draft.createdAt,
    )

    private fun audit(
        commandId: CommandId,
        suffix: String,
        action: AuditAction,
        entityType: String,
        entityId: String,
        occurredAt: Instant,
    ) = AuditRecord(
        id = AuditEventId("audit:${commandId.value}:$suffix"),
        commandId = commandId,
        action = action,
        entityType = entityType,
        entityId = entityId,
        occurredAt = occurredAt,
    )
}
