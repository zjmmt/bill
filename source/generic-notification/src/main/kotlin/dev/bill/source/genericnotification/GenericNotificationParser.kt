package dev.bill.source.genericnotification

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.SourceParser
import dev.bill.source.contract.VersionId

/**
 * Transport-only parser for a synthetic/local notification envelope. It intentionally does not
 * infer amount, direction, completion state, provider or account. Provider templates belong in
 * later source-specific modules backed by sanitized replay fixtures.
 */
class GenericNotificationParser : SourceParser {
    override val identity: SourceIdentity = SourceIdentity(
        parserId = ParserId("generic-notification"),
        providerId = ProviderId("android-notification"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = GENERIC_NOTIFICATION_CONNECTOR_ID,
        capabilities = emptySet(),
        supportedCaptureMethods = setOf(CaptureMethod.NOTIFICATION),
        parserVersion = VersionId("parser-1"),
        ruleVersion = VersionId("rules-1"),
    )

    override fun parse(rawEvent: RawEvent, evidenceInput: EvidenceInput): ParseResult {
        if (!identity.accepts(rawEvent)) return rejected(DiagnosticCode.SOURCE_NOT_ACCEPTED)
        if (evidenceInput.mediaType != NotificationEvidenceMediaTypes.ENVELOPE) {
            return rejected(DiagnosticCode.UNSUPPORTED_MEDIA_TYPE)
        }
        val bytes = evidenceInput.copyBytes()
        val decoded = try {
            NotificationEnvelopeCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
        if (decoded !is NotificationEnvelopeDecodeResult.Decoded) {
            return rejected(DiagnosticCode.MALFORMED_EVIDENCE)
        }
        return ParseResult.NeedsUserReview(
            candidate = null,
            diagnostic = SafeDiagnostic(
                code = DiagnosticCode.INSUFFICIENT_FIELDS,
                recoverable = true,
            ),
        )
    }

    private fun rejected(code: DiagnosticCode) = ParseResult.Rejected(
        SafeDiagnostic(code = code, recoverable = false),
    )
}

/** Reserved for the transport-only parser bundled by this module. */
internal val GENERIC_NOTIFICATION_CONNECTOR_ID = ConnectorId("android-notification")
