package dev.bill.application

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.RawEventRepository
import dev.bill.source.contract.TextEvidenceMediaTypes
import dev.bill.source.pipeline.SourceIngestionService
import dev.bill.source.review.EvidenceStagingStore
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Clock

/** A safe, user-visible outcome for any explicit local evidence capture. */
enum class SourceCaptureError {
    INVALID_COMMAND,
    EMPTY_CONTENT,
    CONTENT_TOO_LARGE,
    EVIDENCE_COLLISION,
    EVIDENCE_WRITE_FAILED,
    EVIDENCE_ALREADY_CLEARED,
    STORAGE_LIMIT_REACHED,
    STAGING_RECOVERY_IN_PROGRESS,
    RAW_EVENT_COLLISION,
    PARSER_UNAVAILABLE,
    PARSE_REJECTED,
    COMMIT_FAILED,
}

sealed interface SourceCaptureResult {
    data class ReadyForReview(
        val proposalId: String,
        val rawEventId: String,
        val alreadyPresent: Boolean,
    ) : SourceCaptureResult

    data class Failure(
        val error: SourceCaptureError,
        val diagnosticCode: DiagnosticCode?,
    ) : SourceCaptureResult
}

/** Compatibility names for the existing explicit text-share API. */
typealias SharedTextCaptureError = SourceCaptureError

typealias SharedTextCaptureResult = SourceCaptureResult

interface SharedTextCapture {
    suspend fun ingest(
        commandId: String,
        sharedText: CharSequence?,
    ): SourceCaptureResult
}

/**
 * Converts one explicit Android text share into immutable evidence plus a review work item.
 * It never interprets the text as a provider-specific transaction.
 */
class SharedTextIngestionService(
    rawEventRepository: RawEventRepository,
    evidenceStore: EvidenceStagingStore,
    sourceIngestionService: SourceIngestionService,
    evidenceAdmission: EvidenceStorageAdmission = EvidenceStorageAdmission.AllowAll,
    clock: Clock = Clock.systemUTC(),
) : SharedTextCapture {
    private val genericTextCapture = UserEvidenceIngestion(
        descriptor = UserEvidenceCaptureDescriptor(
            idPrefix = "share",
            connectorId = ConnectorId("android-share-text"),
            captureMethod = CaptureMethod.SHARE_TEXT,
            allowedMediaTypes = TextEvidenceMediaTypes.SHARED_TEXT,
            maxEvidenceBytes = UserEvidenceIngestion.MAX_TEXT_EVIDENCE_BYTES,
            requiresStrictUtf8Text = true,
        ),
        rawEventRepository = rawEventRepository,
        evidenceStore = evidenceStore,
        sourceIngestionService = sourceIngestionService,
        evidenceAdmission = evidenceAdmission,
        clock = clock,
    )

    override suspend fun ingest(
        commandId: String,
        sharedText: CharSequence?,
    ): SourceCaptureResult {
        if (sharedText == null || sharedText.isEmpty()) {
            return failure(SharedTextCaptureError.EMPTY_CONTENT)
        }
        if (sharedText.length.toLong() > MAX_SHARED_TEXT_BYTES) {
            return failure(SharedTextCaptureError.CONTENT_TOO_LARGE)
        }
        val text = sharedText.toString()
        if (text.isBlank()) return failure(SharedTextCaptureError.EMPTY_CONTENT)
        if (NUL in text) {
            return failure(
                SharedTextCaptureError.PARSE_REJECTED,
                DiagnosticCode.MALFORMED_EVIDENCE,
            )
        }
        val bytes = try {
            encodeUtf8(text)
        } catch (_: CharacterCodingException) {
            return failure(
                SharedTextCaptureError.PARSE_REJECTED,
                DiagnosticCode.MALFORMED_EVIDENCE,
            )
        }
        if (bytes.isEmpty()) return failure(SharedTextCaptureError.EMPTY_CONTENT)
        if (bytes.size.toLong() > MAX_SHARED_TEXT_BYTES) {
            bytes.fill(0)
            return failure(SharedTextCaptureError.CONTENT_TOO_LARGE)
        }

        return try {
            genericTextCapture.ingest(
                commandId = commandId,
                mediaType = TextEvidenceMediaTypes.TEXT_PLAIN,
                bytes = bytes,
            )
        } finally {
            bytes.fill(0)
        }
    }

    private fun failure(
        error: SourceCaptureError,
        diagnosticCode: DiagnosticCode? = null,
    ) = SourceCaptureResult.Failure(error, diagnosticCode)

    @Throws(CharacterCodingException::class)
    private fun encodeUtf8(text: String): ByteArray {
        val buffer = StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    companion object {
        const val MAX_SHARED_TEXT_BYTES = UserEvidenceIngestion.MAX_TEXT_EVIDENCE_BYTES

        private const val NUL = '\u0000'
    }
}
