package dev.bill.source.pipeline

import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceIdentity
import java.time.Instant

@JvmInline
value class ParseAttemptId(val value: String) {
    init {
        require(value.isNotBlank()) { "Parse attempt id cannot be blank" }
    }
}

@JvmInline
value class DraftProposalId(val value: String) {
    init {
        require(value.isNotBlank()) { "Draft proposal id cannot be blank" }
    }
}

enum class ParseAttemptOutcome {
    PARSED,
    NEEDS_USER_REVIEW,
    REJECTED,
}

data class ParseAttempt(
    val id: ParseAttemptId,
    val rawEventId: RawEventId,
    val sourceIdentity: SourceIdentity,
    val attemptedAt: Instant,
    val result: ParseResult,
) {
    val outcome: ParseAttemptOutcome
        get() = when (result) {
            is ParseResult.Parsed -> ParseAttemptOutcome.PARSED
            is ParseResult.NeedsUserReview -> ParseAttemptOutcome.NEEDS_USER_REVIEW
            is ParseResult.Rejected -> ParseAttemptOutcome.REJECTED
        }
}

/**
 * A source-derived candidate is always held for review. It deliberately has no
 * TransactionType field: observed money direction is evidence, not an accounting decision.
 */
data class DraftProposal(
    val id: DraftProposalId,
    val parseAttemptId: ParseAttemptId,
    val rawEventId: RawEventId,
    val reviewState: DraftProposalReviewState,
    val candidate: NormalizedCandidate?,
)

enum class DraftProposalReviewState {
    WAITING_USER,
}

/**
 * Atomically persists the attempt and its optional source-derived review draft.
 *
 * [ParseAttempt.id] is the idempotency key. A retry with the same id must not overwrite the
 * first committed attempt or draft (including its original [ParseAttempt.attemptedAt]); it returns
 * [ParseCommitResult.AlreadyCommitted]. A different parser or rule version has a different id and
 * is therefore appended as a new attempt.
 */
interface ParseCommitStore {
    suspend fun commit(
        attempt: ParseAttempt,
        draftProposal: DraftProposal?,
    ): ParseCommitResult
}

sealed interface ParseCommitResult {
    data object Committed : ParseCommitResult

    data object AlreadyCommitted : ParseCommitResult

    data object KeyCollision : ParseCommitResult

    data object InvalidPayload : ParseCommitResult
}

enum class RawEventDisposition {
    INSERTED,
    ALREADY_PRESENT,
    DUPLICATE_OBSERVATION,
}

enum class ParseCommitDisposition {
    COMMITTED,
    ALREADY_COMMITTED,
}

enum class IngestionFailure {
    RAW_EVENT_ID_COLLISION,
    NO_MATCHING_PARSER,
    AMBIGUOUS_PARSER,
    EVIDENCE_NOT_FOUND,
    EVIDENCE_READ_FAILED,
    EVIDENCE_TOO_LARGE,
    EVIDENCE_INTEGRITY_MISMATCH,
    PARSER_RUNTIME_FAILURE,
    COMMIT_KEY_COLLISION,
    COMMIT_REJECTED,
}

sealed interface IngestionResult {
    val rawEventId: RawEventId

    data class Recorded(
        override val rawEventId: RawEventId,
        val rawEventDisposition: RawEventDisposition,
        val attemptId: ParseAttemptId,
        val draftProposalId: DraftProposalId?,
        val commitDisposition: ParseCommitDisposition,
        val outcome: ParseAttemptOutcome,
        val draftProposalState: DraftProposalReviewState?,
        val diagnostic: SafeDiagnostic?,
    ) : IngestionResult

    data class Failed(
        override val rawEventId: RawEventId,
        val failure: IngestionFailure,
        val diagnostic: SafeDiagnostic,
        val rawEventDisposition: RawEventDisposition? = null,
        val attemptId: ParseAttemptId? = null,
    ) : IngestionResult
}
