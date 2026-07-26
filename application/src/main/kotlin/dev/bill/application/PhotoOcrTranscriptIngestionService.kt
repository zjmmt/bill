package dev.bill.application

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.RawEventRepository
import dev.bill.source.genericphotoocr.OcrTranscript
import dev.bill.source.pipeline.SourceIngestionService
import dev.bill.source.review.EvidenceStagingStore
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A bounded OCR transcript created from one image entirely on the device. */
data class PhotoOcrTranscriptEvidence(
    val bytes: ByteArray,
)

interface PhotoOcrTranscriptCapture {
    suspend fun ingest(
        commandId: String,
        evidence: PhotoOcrTranscriptEvidence,
    ): SourceCaptureResult

    data object Unavailable : PhotoOcrTranscriptCapture {
        override suspend fun ingest(
            commandId: String,
            evidence: PhotoOcrTranscriptEvidence,
        ): SourceCaptureResult {
            evidence.bytes.fill(0)
            return SourceCaptureResult.Failure(
                error = SourceCaptureError.PARSER_UNAVAILABLE,
                diagnosticCode = null,
            )
        }
    }
}

/**
 * Validates and stores one local OCR transcript before opening an editable source review.
 *
 * The caller transfers ownership of [PhotoOcrTranscriptEvidence.bytes]; every outcome wipes it.
 */
class PhotoOcrTranscriptIngestionService(
    rawEventRepository: RawEventRepository,
    evidenceStore: EvidenceStagingStore,
    sourceIngestionService: SourceIngestionService,
    evidenceAdmission: EvidenceStorageAdmission = EvidenceStorageAdmission.AllowAll,
    clock: Clock = Clock.systemUTC(),
) : PhotoOcrTranscriptCapture {
    private val intakeMutex = Mutex()
    private val evidenceIngestion = UserEvidenceIngestion(
        descriptor = UserEvidenceCaptureDescriptor(
            idPrefix = "photo-ocr",
            connectorId = ConnectorId(OcrTranscript.CONNECTOR_ID),
            captureMethod = CaptureMethod.PHOTO_OCR,
            allowedMediaTypes = setOf(OcrTranscript.MEDIA_TYPE),
            maxEvidenceBytes = OcrTranscript.MAX_EVIDENCE_BYTES.toLong(),
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
        evidence: PhotoOcrTranscriptEvidence,
    ): SourceCaptureResult = withContext(Dispatchers.Default) {
        intakeMutex.withLock {
            try {
                if (evidence.bytes.isEmpty()) {
                    return@withLock SourceCaptureResult.Failure(
                        error = SourceCaptureError.EMPTY_CONTENT,
                        diagnosticCode = null,
                    )
                }
                if (evidence.bytes.size > OcrTranscript.MAX_EVIDENCE_BYTES) {
                    return@withLock SourceCaptureResult.Failure(
                        error = SourceCaptureError.CONTENT_TOO_LARGE,
                        diagnosticCode = null,
                    )
                }
                if (OcrTranscript.decode(evidence.bytes) == null) {
                    return@withLock SourceCaptureResult.Failure(
                        error = SourceCaptureError.PARSE_REJECTED,
                        diagnosticCode = DiagnosticCode.MALFORMED_EVIDENCE,
                    )
                }
                evidenceIngestion.ingest(
                    commandId = commandId,
                    mediaType = OcrTranscript.MEDIA_TYPE,
                    bytes = evidence.bytes,
                )
            } finally {
                evidence.bytes.fill(0)
            }
        }
    }
}
