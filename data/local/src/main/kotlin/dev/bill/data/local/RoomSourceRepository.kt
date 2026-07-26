package dev.bill.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.bill.core.domain.AuditAction
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.RepositoryWriteResult
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.core.domain.ReviewDraft
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.model.isSupportedLedgerCurrency
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.VersionId
import dev.bill.source.pipeline.DraftProposal
import dev.bill.source.pipeline.DraftProposalReviewState
import dev.bill.source.pipeline.ParseAttempt
import dev.bill.source.pipeline.ParseAttemptOutcome
import dev.bill.source.pipeline.ParseCommitResult
import dev.bill.source.pipeline.ParseCommitStore
import dev.bill.source.review.SourceProposalRecord
import dev.bill.source.review.SourceReviewRepository
import dev.bill.source.review.allowsExternalDraftCurrency
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class RoomSourceRepository(
    private val database: BillDatabase,
) : ParseCommitStore, SourceReviewRepository {
    private val sourceDao = database.sourceDao()
    private val rawEventDao = database.rawEventDao()
    private val ledgerDao = database.ledgerDao()
    private val evidenceDao = database.sourceEvidenceDao()

    override suspend fun commit(
        attempt: ParseAttempt,
        draftProposal: DraftProposal?,
    ): ParseCommitResult {
        val entities = try {
            mapCommit(attempt, draftProposal)
        } catch (_: CandidateCodecException) {
            return ParseCommitResult.InvalidPayload
        } catch (_: IllegalArgumentException) {
            return ParseCommitResult.InvalidPayload
        } catch (_: ArithmeticException) {
            return ParseCommitResult.InvalidPayload
        }

        return try {
            database.withTransaction {
                inspectExisting(entities)?.let { return@withTransaction it }
                val rawEntity = rawEventDao.findById(entities.attempt.rawEventId)
                    ?: return@withTransaction ParseCommitResult.InvalidPayload
                if (!hasAvailableEvidence(rawEntity)) {
                    return@withTransaction ParseCommitResult.InvalidPayload
                }
                val rawEvent = RawEventEntityMapper.toDomain(rawEntity)
                if (!attempt.sourceIdentity.accepts(rawEvent)) {
                    return@withTransaction ParseCommitResult.InvalidPayload
                }

                sourceDao.insertParseAttempt(entities.attempt)
                entities.proposal?.let { proposal ->
                    sourceDao.insertProposal(proposal)
                }
                ParseCommitResult.Committed
            }
        } catch (_: SQLiteConstraintException) {
            database.withTransaction {
                inspectExisting(entities) ?: ParseCommitResult.KeyCollision
            }
        }
    }

    override fun observePendingSourceProposals(): Flow<List<SourceProposalRecord>> = combine(
        sourceDao.observePendingProposalRows(),
        sourceDao.observeSourceIntegrityIssueCount(),
        evidenceDao.observeEvidenceIntegrityIssueCount(),
    ) { rows, sourceIntegrityIssueCount, evidenceIntegrityIssueCount ->
        if (sourceIntegrityIssueCount != 0L || evidenceIntegrityIssueCount != 0L) {
            throw LocalDataIntegrityException("source ingestion")
        }
        rows.map(::mapProposalRow)
    }

    override suspend fun completeSourceProposal(
        proposalId: String,
        draft: ReviewDraft,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult = try {
        database.withTransaction {
            val fingerprint = externalDraftFingerprint(proposalId, draft)
            ledgerDao.findCommandReceipt(draft.creationCommandId.value)?.let { existing ->
                return@withTransaction if (
                    existing.operation == CompleteSourceProposalOperation &&
                    existing.targetId == proposalId &&
                    existing.payloadFingerprint == fingerprint
                ) {
                    result(RepositoryWriteStatus.ALREADY_APPLIED, existing.resultEntityId)
                } else {
                    result(RepositoryWriteStatus.COMMAND_COLLISION)
                }
            }

            val proposal = sourceDao.findProposalRow(proposalId)
                ?: return@withTransaction result(RepositoryWriteStatus.NOT_FOUND)
            if (proposal.proposalState != ProposalWaitingUser) {
                return@withTransaction result(RepositoryWriteStatus.INVALID_STATE)
            }
            requireAvailableEvidence(
                rawEventDao.findById(proposal.rawEventId)
                    ?: throw LocalDataIntegrityException("source ingestion"),
            )
            val proposalRecord = mapProposalRow(proposal)
            if (
                draft.sourceMode != TransactionSourceMode.EXTERNAL ||
                draft.state != DraftState.WAITING_USER ||
                draft.fundingAccountId != null ||
                !draft.amount.currency.isSupportedLedgerCurrency() ||
                !proposalRecord.allowsExternalDraftCurrency(draft.amount.currency) ||
                auditRecord.commandId != draft.creationCommandId ||
                auditRecord.action != AuditAction.EXTERNAL_DRAFT_CREATED ||
                auditRecord.entityType != "draft" ||
                auditRecord.entityId != draft.id.value ||
                ledgerDao.findDraft(draft.id.value) != null ||
                ledgerDao.countAuditEvents(listOf(auditRecord.id.value)) != 0
            ) {
                return@withTransaction result(RepositoryWriteStatus.INVALID_STATE)
            }

            ledgerDao.insertDraft(LedgerEntityMapper.draftToEntity(draft))
            sourceDao.insertDraftSourceEvidence(
                DraftSourceEvidenceEntity(
                    draftId = draft.id.value,
                    proposalId = proposal.proposalId,
                    rawEventId = proposal.rawEventId,
                    parseAttemptId = proposal.parseAttemptId,
                ),
            )
            if (sourceDao.completeProposal(proposalId, draft.id.value) != 1) {
                throw ConcurrentSourceStateChange()
            }
            ledgerDao.insertAuditEvents(listOf(LedgerEntityMapper.auditToEntity(auditRecord)))
            ledgerDao.insertCommandReceipt(
                CommandReceiptEntity(
                    commandId = draft.creationCommandId.value,
                    operation = CompleteSourceProposalOperation,
                    targetId = proposalId,
                    resultEntityId = draft.id.value,
                    payloadFingerprint = fingerprint,
                    appliedAtEpochMillis = auditRecord.occurredAt.toEpochMilli(),
                ),
            )
            result(RepositoryWriteStatus.APPLIED, draft.id.value)
        }
    } catch (_: SQLiteConstraintException) {
        result(RepositoryWriteStatus.COMMAND_COLLISION)
    } catch (_: ConcurrentSourceStateChange) {
        result(RepositoryWriteStatus.INVALID_STATE)
    } catch (_: LocalDataIntegrityException) {
        throw LocalDataIntegrityException("source ingestion")
    }

    override suspend fun dismissSourceProposal(
        proposalId: String,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult = try {
        database.withTransaction {
            val fingerprint = SourceFingerprint()
                .add(DismissSourceProposalOperation)
                .add(proposalId)
                .finish()
            ledgerDao.findCommandReceipt(auditRecord.commandId.value)?.let { existing ->
                return@withTransaction if (
                    existing.operation == DismissSourceProposalOperation &&
                    existing.targetId == proposalId &&
                    existing.payloadFingerprint == fingerprint
                ) {
                    result(RepositoryWriteStatus.ALREADY_APPLIED, existing.resultEntityId)
                } else {
                    result(RepositoryWriteStatus.COMMAND_COLLISION)
                }
            }

            val proposal = sourceDao.findProposalRow(proposalId)
                ?: return@withTransaction result(RepositoryWriteStatus.NOT_FOUND)
            if (
                proposal.proposalState != ProposalWaitingUser ||
                auditRecord.action != AuditAction.SOURCE_PROPOSAL_DISMISSED ||
                auditRecord.entityType != SourceProposalEntityType ||
                auditRecord.entityId != proposalId ||
                ledgerDao.countAuditEvents(listOf(auditRecord.id.value)) != 0
            ) {
                return@withTransaction result(RepositoryWriteStatus.INVALID_STATE)
            }
            requireAvailableEvidence(
                rawEventDao.findById(proposal.rawEventId)
                    ?: throw LocalDataIntegrityException("source ingestion"),
            )
            mapProposalRow(proposal)

            if (sourceDao.dismissProposal(proposalId) != 1) {
                throw ConcurrentSourceStateChange()
            }
            ledgerDao.insertAuditEvents(listOf(LedgerEntityMapper.auditToEntity(auditRecord)))
            ledgerDao.insertCommandReceipt(
                CommandReceiptEntity(
                    commandId = auditRecord.commandId.value,
                    operation = DismissSourceProposalOperation,
                    targetId = proposalId,
                    resultEntityId = proposalId,
                    payloadFingerprint = fingerprint,
                    appliedAtEpochMillis = auditRecord.occurredAt.toEpochMilli(),
                ),
            )
            result(RepositoryWriteStatus.APPLIED, proposalId)
        }
    } catch (_: SQLiteConstraintException) {
        result(RepositoryWriteStatus.COMMAND_COLLISION)
    } catch (_: ConcurrentSourceStateChange) {
        result(RepositoryWriteStatus.INVALID_STATE)
    } catch (_: LocalDataIntegrityException) {
        throw LocalDataIntegrityException("source ingestion")
    }

    private suspend fun inspectExisting(entities: CommitEntities): ParseCommitResult? {
        val existing = sourceDao.findParseAttempt(entities.attempt.id)
            ?: sourceDao.findParseAttemptByKey(
                rawEventId = entities.attempt.rawEventId,
                parserId = entities.attempt.parserId,
                parserVersion = entities.attempt.parserVersion,
                ruleVersion = entities.attempt.ruleVersion,
            )
            ?: return null
        if (!existing.matches(entities.attempt)) return ParseCommitResult.KeyCollision

        val existingProposal = sourceDao.findProposalByAttemptId(existing.id)
        val expectedProposal = entities.proposal
        return if (
            (existingProposal == null && expectedProposal == null) ||
            (
                existingProposal != null &&
                    expectedProposal != null &&
                    existingProposal.id == expectedProposal.id &&
                    existingProposal.parseAttemptId == expectedProposal.parseAttemptId &&
                    existingProposal.rawEventId == expectedProposal.rawEventId
            )
        ) {
            ParseCommitResult.AlreadyCommitted
        } else {
            ParseCommitResult.KeyCollision
        }
    }

    private suspend fun hasAvailableEvidence(rawEvent: RawEventEntity): Boolean {
        val lifecycle = evidenceDao.findPayload(rawEvent.id)
            ?: throw LocalDataIntegrityException("source evidence lifecycle")
        if (lifecycle.payloadId != rawEvent.payloadReference) {
            throw LocalDataIntegrityException("source evidence lifecycle")
        }
        return lifecycle.state == PayloadAvailable
    }

    private suspend fun requireAvailableEvidence(rawEvent: RawEventEntity) {
        if (!hasAvailableEvidence(rawEvent)) {
            throw LocalDataIntegrityException("source evidence lifecycle")
        }
    }

    private fun mapCommit(
        attempt: ParseAttempt,
        proposal: DraftProposal?,
    ): CommitEntities {
        val candidate = attempt.result.candidateOrNull()
        val diagnostic = attempt.result.diagnosticOrNull()
        if (
            proposal != null &&
            (
                proposal.parseAttemptId != attempt.id ||
                    proposal.rawEventId != attempt.rawEventId ||
                    proposal.reviewState != DraftProposalReviewState.WAITING_USER ||
                    proposal.candidate != candidate ||
                    attempt.result is ParseResult.Rejected
            )
        ) {
            throw CandidateCodecException()
        }
        if (
            proposal == null &&
            (attempt.result is ParseResult.Parsed || attempt.result is ParseResult.NeedsUserReview)
        ) {
            throw CandidateCodecException()
        }

        val candidatePayload = SourceCandidateCodec.encode(candidate)
        val identity = attempt.sourceIdentity
        val fingerprint = parseAttemptFingerprint(
            attempt = attempt,
            candidatePayload = candidatePayload,
            diagnostic = diagnostic,
        )
        return CommitEntities(
            attempt = ParseAttemptEntity(
                id = attempt.id.value,
                rawEventId = attempt.rawEventId.value,
                parserId = identity.parserId.value,
                providerId = identity.providerId.value,
                sourceFamily = identity.sourceFamily.name,
                connectorId = identity.connectorId.value,
                parserVersion = identity.parserVersion.value,
                ruleVersion = identity.ruleVersion.value,
                attemptedAtEpochMillis = attempt.attemptedAt.toEpochMilli(),
                outcome = attempt.outcome.name,
                diagnosticCode = diagnostic?.code?.name,
                diagnosticRecoverable = diagnostic?.recoverable,
                candidatePayload = candidatePayload,
                resultFingerprint = fingerprint,
            ),
            proposal = proposal?.let {
                SourceDraftProposalEntity(
                    id = it.id.value,
                    parseAttemptId = it.parseAttemptId.value,
                    rawEventId = it.rawEventId.value,
                    state = ProposalWaitingUser,
                    createdAtEpochMillis = attempt.attemptedAt.toEpochMilli(),
                    completedDraftId = null,
                )
            },
        )
    }

    private fun mapProposalRow(row: SourceProposalRow): SourceProposalRecord {
        try {
            require(row.proposalState == ProposalWaitingUser)
            val sourceFamily = enumValueOf<SourceFamily>(row.sourceFamily)
            val captureMethod = enumValueOf<CaptureMethod>(row.captureMethod)
            val outcome = enumValueOf<ParseAttemptOutcome>(row.parseOutcome)
            require(row.rawEventId == row.attemptRawEventId)
            require(row.sourceFamily == row.attemptSourceFamily)
            require(row.rawConnectorId == row.connectorId)
            require(
                outcome == ParseAttemptOutcome.PARSED ||
                    outcome == ParseAttemptOutcome.NEEDS_USER_REVIEW,
            )
            RawEventId(row.rawEventId)
            ParserId(row.parserId)
            ProviderId(row.providerId)
            ConnectorId(row.connectorId)
            VersionId(row.parserVersion)
            VersionId(row.ruleVersion)
            val diagnostic = row.diagnosticCode?.let { code ->
                SafeDiagnostic(
                    code = enumValueOf<DiagnosticCode>(code),
                    recoverable = requireNotNull(row.diagnosticRecoverable),
                )
            }
            require(row.diagnosticCode != null || row.diagnosticRecoverable == null)
            if (outcome == ParseAttemptOutcome.PARSED) require(diagnostic == null)
            if (outcome == ParseAttemptOutcome.NEEDS_USER_REVIEW) require(diagnostic != null)
            require(row.matchingObservationCount > 0L)
            val candidate = SourceCandidateCodec.decode(row.candidatePayload)
            if (outcome == ParseAttemptOutcome.PARSED) require(candidate != null)
            return SourceProposalRecord(
                id = row.proposalId,
                rawEventId = row.rawEventId,
                parseAttemptId = row.parseAttemptId,
                sourceFamily = sourceFamily,
                captureMethod = captureMethod,
                capturedAt = Instant.ofEpochMilli(row.capturedAtEpochMillis),
                diagnostic = diagnostic,
                candidate = candidate,
                isPossibleDuplicate = row.matchingObservationCount > 1L,
            )
        } catch (_: RuntimeException) {
            throw LocalDataIntegrityException("source proposal")
        }
    }

    private data class CommitEntities(
        val attempt: ParseAttemptEntity,
        val proposal: SourceDraftProposalEntity?,
    )

    private companion object {
        const val ProposalWaitingUser = "WAITING_USER"
        const val CompleteSourceProposalOperation = "COMPLETE_SOURCE_PROPOSAL"
        const val DismissSourceProposalOperation = "DISMISS_SOURCE_PROPOSAL"
        const val SourceProposalEntityType = "source_proposal"
        const val PayloadAvailable = "AVAILABLE"
    }
}

private fun ParseResult.candidateOrNull(): NormalizedCandidate? = when (this) {
    is ParseResult.Parsed -> candidate
    is ParseResult.NeedsUserReview -> candidate
    is ParseResult.Rejected -> null
}

private fun ParseResult.diagnosticOrNull(): SafeDiagnostic? = when (this) {
    is ParseResult.Parsed -> null
    is ParseResult.NeedsUserReview -> diagnostic
    is ParseResult.Rejected -> diagnostic
}

private fun ParseAttemptEntity.matches(other: ParseAttemptEntity): Boolean =
    id == other.id &&
        rawEventId == other.rawEventId &&
        parserId == other.parserId &&
        providerId == other.providerId &&
        sourceFamily == other.sourceFamily &&
        connectorId == other.connectorId &&
        parserVersion == other.parserVersion &&
        ruleVersion == other.ruleVersion &&
        outcome == other.outcome &&
        diagnosticCode == other.diagnosticCode &&
        diagnosticRecoverable == other.diagnosticRecoverable &&
        resultFingerprint == other.resultFingerprint &&
        candidatePayload.contentEqualsNullable(other.candidatePayload)

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> contentEquals(other)
}

private fun parseAttemptFingerprint(
    attempt: ParseAttempt,
    candidatePayload: ByteArray?,
    diagnostic: SafeDiagnostic?,
): String = SourceFingerprint()
    .add(attempt.rawEventId.value)
    .add(attempt.sourceIdentity.parserId.value)
    .add(attempt.sourceIdentity.providerId.value)
    .add(attempt.sourceIdentity.sourceFamily.name)
    .add(attempt.sourceIdentity.connectorId.value)
    .add(attempt.sourceIdentity.parserVersion.value)
    .add(attempt.sourceIdentity.ruleVersion.value)
    .add(attempt.sourceIdentity.capabilities.map { it.name }.sorted())
    .add(attempt.sourceIdentity.supportedCaptureMethods.map { it.name }.sorted())
    .add(attempt.outcome.name)
    .addNullable(diagnostic?.code?.name)
    .addNullable(diagnostic?.recoverable?.toString())
    .addBytes(candidatePayload)
    .finish()

private fun externalDraftFingerprint(proposalId: String, draft: ReviewDraft): String =
    SourceFingerprint()
        .add(proposalId)
        .add(draft.id.value)
        .add(draft.state.name)
        .add(draft.sourceMode.name)
        .add(draft.type.name)
        .add(draft.amount.minorUnits.toString())
        .add(draft.amount.currency.value)
        .add(draft.counterparty)
        .addNullable(draft.note)
        .finish()

private class SourceFingerprint {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun add(value: String): SourceFingerprint = apply {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    fun add(values: List<String>): SourceFingerprint = apply {
        add(values.size.toString())
        values.forEach(::add)
    }

    fun addNullable(value: String?): SourceFingerprint =
        if (value == null) add("<null>") else add("<value>").add(value)

    fun addBytes(value: ByteArray?): SourceFingerprint = apply {
        if (value == null) {
            add("<null-bytes>")
        } else {
            add(value.size.toString())
            digest.update(value)
        }
    }

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private class ConcurrentSourceStateChange : RuntimeException()

private fun result(status: RepositoryWriteStatus, entityId: String? = null) =
    RepositoryWriteResult(status = status, entityId = entityId)
