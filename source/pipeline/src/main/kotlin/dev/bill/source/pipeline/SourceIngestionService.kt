package dev.bill.source.pipeline

import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceReadResult
import dev.bill.source.contract.EvidenceReader
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventAppendResult
import dev.bill.source.contract.RawEventRepository
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceIdentity
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

class SourceIngestionService(
    private val rawEventRepository: RawEventRepository,
    private val evidenceReader: EvidenceReader,
    private val parserRegistry: ParserRegistry,
    private val commitStore: ParseCommitStore,
    private val clock: Clock,
    private val maxEvidenceBytes: Long,
) {
    init {
        require(maxEvidenceBytes > 0) { "Evidence size budget must be positive" }
    }

    suspend fun ingest(rawEvent: RawEvent): IngestionResult {
        val rawEventDisposition = when (rawEventRepository.append(rawEvent)) {
            RawEventAppendResult.Inserted -> RawEventDisposition.INSERTED
            RawEventAppendResult.AlreadyPresent -> RawEventDisposition.ALREADY_PRESENT
            is RawEventAppendResult.DuplicateObservation ->
                RawEventDisposition.DUPLICATE_OBSERVATION

            RawEventAppendResult.IdCollision -> return failure(
                rawEvent = rawEvent,
                failure = IngestionFailure.RAW_EVENT_ID_COLLISION,
                code = DiagnosticCode.RAW_EVENT_ID_COLLISION,
                recoverable = false,
            )
        }

        val parser = when (val resolution = parserRegistry.resolve(rawEvent)) {
            is ParserResolution.Selected -> resolution.parser
            ParserResolution.NoMatch -> return failure(
                rawEvent = rawEvent,
                failure = IngestionFailure.NO_MATCHING_PARSER,
                code = DiagnosticCode.NO_MATCHING_PARSER,
                recoverable = true,
                rawEventDisposition = rawEventDisposition,
            )

            ParserResolution.Ambiguous -> return failure(
                rawEvent = rawEvent,
                failure = IngestionFailure.AMBIGUOUS_PARSER,
                code = DiagnosticCode.AMBIGUOUS_PARSER,
                recoverable = true,
                rawEventDisposition = rawEventDisposition,
            )
        }

        val readResult = try {
            evidenceReader.read(rawEvent.payloadId, maxEvidenceBytes)
        } catch (_: RuntimeException) {
            return recordRejectedAttempt(
                rawEvent = rawEvent,
                identity = parser.identity,
                rawEventDisposition = rawEventDisposition,
                code = DiagnosticCode.EVIDENCE_READ_FAILED,
                recoverable = true,
            )
        }
        val evidenceInput = when (readResult) {
            is EvidenceReadResult.Found -> readResult.input
            EvidenceReadResult.NotFound -> return recordRejectedAttempt(
                rawEvent = rawEvent,
                identity = parser.identity,
                rawEventDisposition = rawEventDisposition,
                code = DiagnosticCode.EVIDENCE_NOT_FOUND,
                recoverable = true,
            )

            is EvidenceReadResult.Failed -> return recordRejectedAttempt(
                rawEvent = rawEvent,
                identity = parser.identity,
                rawEventDisposition = rawEventDisposition,
                code = DiagnosticCode.EVIDENCE_READ_FAILED,
                recoverable = readResult.diagnostic.recoverable,
            )
        }

        if (evidenceInput.sizeBytes > maxEvidenceBytes) {
            return recordRejectedAttempt(
                rawEvent = rawEvent,
                identity = parser.identity,
                rawEventDisposition = rawEventDisposition,
                code = DiagnosticCode.EVIDENCE_TOO_LARGE,
                recoverable = true,
            )
        }

        if (!matchesContentHash(evidenceInput.copyBytes(), rawEvent.contentHash)) {
            return recordRejectedAttempt(
                rawEvent = rawEvent,
                identity = parser.identity,
                rawEventDisposition = rawEventDisposition,
                code = DiagnosticCode.EVIDENCE_INTEGRITY_MISMATCH,
                recoverable = false,
            )
        }

        val parseResult = try {
            parser.parse(rawEvent, evidenceInput)
        } catch (_: RuntimeException) {
            return recordRejectedAttempt(
                rawEvent = rawEvent,
                identity = parser.identity,
                rawEventDisposition = rawEventDisposition,
                code = DiagnosticCode.PARSER_RUNTIME_FAILURE,
                recoverable = true,
            )
        }

        return recordAttempt(rawEvent, parser.identity, rawEventDisposition, parseResult)
    }

    private suspend fun recordRejectedAttempt(
        rawEvent: RawEvent,
        identity: SourceIdentity,
        rawEventDisposition: RawEventDisposition,
        code: DiagnosticCode,
        recoverable: Boolean,
    ): IngestionResult = recordAttempt(
        rawEvent = rawEvent,
        identity = identity,
        rawEventDisposition = rawEventDisposition,
        parseResult = ParseResult.Rejected(
            SafeDiagnostic(code = code, recoverable = recoverable),
        ),
    )

    private suspend fun recordAttempt(
        rawEvent: RawEvent,
        identity: SourceIdentity,
        rawEventDisposition: RawEventDisposition,
        parseResult: ParseResult,
    ): IngestionResult {
        val attemptId = deterministicAttemptId(rawEvent, identity)
        val attempt = ParseAttempt(
            id = attemptId,
            rawEventId = rawEvent.id,
            sourceIdentity = identity,
            attemptedAt = clock.instant(),
            result = parseResult,
        )
        val draftProposal = if (parseResult.requiresUserWorkItem()) {
            DraftProposal(
                id = deterministicDraftProposalId(attemptId),
                parseAttemptId = attemptId,
                rawEventId = rawEvent.id,
                reviewState = DraftProposalReviewState.WAITING_USER,
                candidate = parseResult.candidateOrNull(),
            )
        } else {
            null
        }

        val commitDisposition = when (commitStore.commit(attempt, draftProposal)) {
            ParseCommitResult.Committed -> ParseCommitDisposition.COMMITTED
            ParseCommitResult.AlreadyCommitted -> ParseCommitDisposition.ALREADY_COMMITTED
            ParseCommitResult.KeyCollision -> return failure(
                rawEvent = rawEvent,
                failure = IngestionFailure.COMMIT_KEY_COLLISION,
                code = DiagnosticCode.COMMIT_CONFLICT,
                recoverable = false,
                rawEventDisposition = rawEventDisposition,
                attemptId = attemptId,
            )

            ParseCommitResult.InvalidPayload -> return failure(
                rawEvent = rawEvent,
                failure = IngestionFailure.COMMIT_REJECTED,
                code = DiagnosticCode.COMMIT_REJECTED,
                recoverable = false,
                rawEventDisposition = rawEventDisposition,
                attemptId = attemptId,
            )
        }

        return IngestionResult.Recorded(
            rawEventId = rawEvent.id,
            rawEventDisposition = rawEventDisposition,
            attemptId = attemptId,
            draftProposalId = draftProposal?.id,
            commitDisposition = commitDisposition,
            outcome = attempt.outcome,
            draftProposalState = draftProposal?.reviewState,
            diagnostic = parseResult.diagnosticOrNull(),
        )
    }

    private fun failure(
        rawEvent: RawEvent,
        failure: IngestionFailure,
        code: DiagnosticCode,
        recoverable: Boolean,
        rawEventDisposition: RawEventDisposition? = null,
        attemptId: ParseAttemptId? = null,
    ): IngestionResult.Failed = IngestionResult.Failed(
        rawEventId = rawEvent.id,
        failure = failure,
        diagnostic = SafeDiagnostic(code = code, recoverable = recoverable),
        rawEventDisposition = rawEventDisposition,
        attemptId = attemptId,
    )
}

private fun ParseResult.candidateOrNull(): NormalizedCandidate? = when (this) {
    is ParseResult.Parsed -> candidate
    is ParseResult.NeedsUserReview -> candidate
    is ParseResult.Rejected -> null
}

private fun ParseResult.requiresUserWorkItem(): Boolean =
    this is ParseResult.Parsed || this is ParseResult.NeedsUserReview

private fun ParseResult.diagnosticOrNull(): SafeDiagnostic? = when (this) {
    is ParseResult.Parsed -> null
    is ParseResult.NeedsUserReview -> diagnostic
    is ParseResult.Rejected -> diagnostic
}

private fun matchesContentHash(bytes: ByteArray, expected: EvidenceHash): Boolean = try {
    EvidenceHash.fromBytes(bytes) == expected
} finally {
    bytes.fill(0)
}

private fun deterministicAttemptId(
    rawEvent: RawEvent,
    identity: SourceIdentity,
): ParseAttemptId = ParseAttemptId(
    "parse-attempt-${
        stableDigest(
            rawEvent.id.value,
            identity.parserId.value,
            identity.parserVersion.value,
            identity.ruleVersion.value,
        )
    }",
)

private fun deterministicDraftProposalId(attemptId: ParseAttemptId): DraftProposalId =
    DraftProposalId("draft-proposal-${stableDigest(attemptId.value)}")

private fun stableDigest(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
