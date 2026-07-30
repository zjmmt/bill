package dev.bill.source.genericdelimited

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.GenericDelimitedStatementIdentity
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceCapability
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.SourceParser
import dev.bill.source.contract.VersionId

class GenericDelimitedStatementParser : SourceParser {
    override val identity = SourceIdentity(
        parserId = ParserId(GenericDelimitedStatementIdentity.PARSER_ID),
        providerId = ProviderId(GenericDelimitedStatementIdentity.PROVIDER_ID),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = CONNECTOR_ID,
        capabilities = setOf(
            SourceCapability.AMOUNT,
            SourceCapability.MONEY_DIRECTION,
            SourceCapability.OCCURRED_AT,
            SourceCapability.COUNTERPARTY,
            SourceCapability.EXTERNAL_REFERENCE,
        ),
        supportedCaptureMethods = setOf(CaptureMethod.STATEMENT_IMPORT),
        parserVersion = VersionId("parser-1"),
        ruleVersion = VersionId("rules-1"),
    )

    override fun parse(rawEvent: RawEvent, evidenceInput: EvidenceInput): ParseResult {
        if (!identity.accepts(rawEvent)) {
            return rejected(DiagnosticCode.SOURCE_NOT_ACCEPTED)
        }
        if (evidenceInput.mediaType != DelimitedStatementRowEvidenceCodec.MEDIA_TYPE) {
            return rejected(DiagnosticCode.UNSUPPORTED_MEDIA_TYPE)
        }
        if (evidenceInput.sizeBytes > DelimitedStatementRowEvidenceCodec.MAX_EVIDENCE_BYTES) {
            return rejected(DiagnosticCode.EVIDENCE_TOO_LARGE)
        }

        val bytes = evidenceInput.copyBytes()
        val decoded = try {
            DelimitedStatementRowEvidenceCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
        val evidence = (decoded as? DelimitedRowEvidenceDecodeResult.Decoded)?.evidence
            ?: return rejected(DiagnosticCode.MALFORMED_EVIDENCE)
        val mapped = DelimitedStatementMapper.map(
            fileHash = evidence.fileHash,
            mapping = evidence.mapping,
            row = evidence.row,
        )
        val candidate = (mapped as? StatementRowMappingResult.Mapped)?.value?.candidate
            ?: return rejected(DiagnosticCode.MALFORMED_EVIDENCE)
        return ParseResult.Parsed(candidate)
    }

    private fun rejected(code: DiagnosticCode): ParseResult.Rejected =
        ParseResult.Rejected(
            SafeDiagnostic(
                code = code,
                recoverable = false,
            ),
        )

    companion object {
        val CONNECTOR_ID = ConnectorId(GenericDelimitedStatementIdentity.CONNECTOR_ID)
    }
}
