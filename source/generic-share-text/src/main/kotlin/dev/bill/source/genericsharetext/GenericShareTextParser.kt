package dev.bill.source.genericsharetext

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
import dev.bill.source.contract.TextEvidenceMediaTypes
import dev.bill.source.contract.VersionId
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A deliberately non-semantic parser for text explicitly shared by the user.
 *
 * It validates the transport envelope and encoding, but leaves every financial field for user
 * review. Provider-specific parsers can be added only after representative, sanitized fixtures
 * exist; this parser must never grow heuristic amount, direction, time, or counterparty guesses.
 */
class GenericShareTextParser : GenericTextEvidenceParser(
    identity = SourceIdentity(
        parserId = ParserId("generic-share-text"),
        providerId = ProviderId("user-shared-text"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("android-share-text"),
        capabilities = emptySet(),
        supportedCaptureMethods = setOf(CaptureMethod.SHARE_TEXT),
        parserVersion = VersionId("parser-1"),
        ruleVersion = VersionId("rules-1"),
    ),
    allowedMediaTypes = TextEvidenceMediaTypes.SHARED_TEXT,
)

/**
 * A deliberately non-semantic parser for a small text file chosen by the user through SAF.
 *
 * A matching MIME type only establishes a safe transport envelope. It is not evidence that the
 * document came from a particular provider or that any row describes a transaction.
 */
class GenericSelectedTextFileParser : GenericTextEvidenceParser(
    identity = SourceIdentity(
        parserId = ParserId("generic-selected-text-file"),
        providerId = ProviderId("user-selected-text-file"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("android-saf-text-file"),
        capabilities = emptySet(),
        supportedCaptureMethods = setOf(CaptureMethod.STATEMENT_IMPORT),
        parserVersion = VersionId("parser-1"),
        ruleVersion = VersionId("rules-1"),
    ),
    allowedMediaTypes = TextEvidenceMediaTypes.USER_SELECTED_TEXT_FILE,
)

/** Shared strict transport validation for generic user-provided text evidence. */
abstract class GenericTextEvidenceParser(
    final override val identity: SourceIdentity,
    private val allowedMediaTypes: Set<String>,
) : SourceParser {
    final override fun parse(rawEvent: RawEvent, evidenceInput: EvidenceInput): ParseResult {
        if (!identity.accepts(rawEvent)) {
            return rejected(DiagnosticCode.SOURCE_NOT_ACCEPTED)
        }
        if (evidenceInput.mediaType !in allowedMediaTypes) {
            return rejected(DiagnosticCode.UNSUPPORTED_MEDIA_TYPE)
        }

        val text = try {
            decodeUtf8(evidenceInput)
        } catch (_: CharacterCodingException) {
            return rejected(DiagnosticCode.MALFORMED_EVIDENCE)
        }

        if (text.isBlank() || NUL in text) {
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

    private fun decodeUtf8(evidenceInput: EvidenceInput): String {
        val bytes = evidenceInput.copyBytes()
        return try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } finally {
            bytes.fill(0)
        }
    }

    private fun rejected(code: DiagnosticCode): ParseResult.Rejected =
        ParseResult.Rejected(
            SafeDiagnostic(
                code = code,
                recoverable = false,
            ),
        )

    private companion object {
        const val NUL = '\u0000'
    }
}
