package dev.bill.source.contract

enum class SourceCapability {
    AMOUNT,
    MONEY_DIRECTION,
    OCCURRED_AT,
    COUNTERPARTY,
    FUNDING_HINT,
    ECONOMIC_EVENT,
    EXTERNAL_REFERENCE,
}

data class SourceIdentity(
    val parserId: ParserId,
    val providerId: ProviderId,
    val sourceFamily: SourceFamily,
    val connectorId: ConnectorId,
    val capabilities: Set<SourceCapability>,
    val supportedCaptureMethods: Set<CaptureMethod>,
    val parserVersion: VersionId,
    val ruleVersion: VersionId,
) {
    init {
        require(supportedCaptureMethods.isNotEmpty()) {
            "A source parser must support at least one capture method"
        }
    }

    fun accepts(event: RawEvent): Boolean =
        event.sourceFamily == sourceFamily &&
            event.connectorId == connectorId &&
            event.captureMethod in supportedCaptureMethods
}

enum class DiagnosticCode {
    NO_MATCHING_PARSER,
    AMBIGUOUS_PARSER,
    SOURCE_NOT_ACCEPTED,
    EVIDENCE_NOT_FOUND,
    EVIDENCE_READ_FAILED,
    EVIDENCE_TOO_LARGE,
    EVIDENCE_INTEGRITY_MISMATCH,
    UNSUPPORTED_MEDIA_TYPE,
    MALFORMED_EVIDENCE,
    INSUFFICIENT_FIELDS,
    PARSER_RUNTIME_FAILURE,
    RAW_EVENT_ID_COLLISION,
    COMMIT_CONFLICT,
    COMMIT_REJECTED,
}

data class SafeDiagnostic(
    val code: DiagnosticCode,
    val recoverable: Boolean,
)

sealed interface ParseResult {
    data class Parsed(val candidate: NormalizedCandidate) : ParseResult

    data class NeedsUserReview(
        val candidate: NormalizedCandidate?,
        val diagnostic: SafeDiagnostic,
    ) : ParseResult

    data class Rejected(val diagnostic: SafeDiagnostic) : ParseResult
}

interface SourceParser {
    val identity: SourceIdentity

    fun parse(rawEvent: RawEvent, evidenceInput: EvidenceInput): ParseResult
}
