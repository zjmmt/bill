package dev.bill.application

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.ImageEvidenceMediaTypes
import dev.bill.source.contract.RawEventRepository
import dev.bill.source.genericreceiptimage.PngReceiptImageValidator
import dev.bill.source.pipeline.SourceIngestionService
import dev.bill.source.review.EvidenceStagingStore
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A bounded in-memory copy of one receipt screenshot explicitly shared by the user. */
data class SharedReceiptImageEvidence(
    val mediaType: String,
    val bytes: ByteArray,
)

interface SharedReceiptImageCapture {
    suspend fun ingest(
        commandId: String,
        evidence: SharedReceiptImageEvidence,
    ): SourceCaptureResult

    data object Unavailable : SharedReceiptImageCapture {
        override suspend fun ingest(
            commandId: String,
            evidence: SharedReceiptImageEvidence,
        ): SourceCaptureResult = try {
            SourceCaptureResult.Failure(
                error = SourceCaptureError.PARSER_UNAVAILABLE,
                diagnosticCode = null,
            )
        } finally {
            evidence.bytes.fill(0)
        }
    }
}

/**
 * Stores a single user-shared PNG receipt as private evidence and opens a manual review item.
 *
 * This is deliberately not OCR: it performs only a bounded PNG envelope check. No amount,
 * provider, counterparty, direction, wallet balance, or transaction is inferred from the image.
 */
class SharedReceiptImageIngestionService(
    rawEventRepository: RawEventRepository,
    evidenceStore: EvidenceStagingStore,
    sourceIngestionService: SourceIngestionService,
    evidenceAdmission: EvidenceStorageAdmission = EvidenceStorageAdmission.AllowAll,
    clock: Clock = Clock.systemUTC(),
) : SharedReceiptImageCapture {
    private val intakeMutex = Mutex()

    private val evidenceIngestion = UserEvidenceIngestion(
        descriptor = UserEvidenceCaptureDescriptor(
            idPrefix = "receipt-image",
            connectorId = ConnectorId("android-share-receipt-image"),
            captureMethod = CaptureMethod.SHARE_FILE,
            allowedMediaTypes = ImageEvidenceMediaTypes.SHARED_RECEIPT_IMAGE,
            maxEvidenceBytes = MAX_SHARED_RECEIPT_IMAGE_BYTES,
            requiresStrictUtf8Text = false,
        ),
        rawEventRepository = rawEventRepository,
        evidenceStore = evidenceStore,
        sourceIngestionService = sourceIngestionService,
        evidenceAdmission = evidenceAdmission,
        clock = clock,
    )

    override suspend fun ingest(
        commandId: String,
        evidence: SharedReceiptImageEvidence,
    ): SourceCaptureResult = try {
        withContext(Dispatchers.Default) {
            intakeMutex.withLock {
                val mediaType = ImageEvidenceMediaTypes.canonicalize(evidence.mediaType)
                if (mediaType !in ImageEvidenceMediaTypes.SHARED_RECEIPT_IMAGE) {
                    return@withLock failure(
                        SourceCaptureError.PARSE_REJECTED,
                        DiagnosticCode.UNSUPPORTED_MEDIA_TYPE,
                    )
                }
                if (evidence.bytes.isEmpty()) return@withLock failure(SourceCaptureError.EMPTY_CONTENT)
                if (evidence.bytes.size.toLong() > MAX_SHARED_RECEIPT_IMAGE_BYTES) {
                    return@withLock failure(SourceCaptureError.CONTENT_TOO_LARGE)
                }
                if (!PngReceiptImageValidator.isAcceptableReceiptScreenshot(evidence.bytes)) {
                    return@withLock failure(
                        SourceCaptureError.PARSE_REJECTED,
                        DiagnosticCode.MALFORMED_EVIDENCE,
                    )
                }
                evidenceIngestion.ingest(commandId, mediaType, evidence.bytes)
            }
        }
    } finally {
        evidence.bytes.fill(0)
    }

    private fun failure(
        error: SourceCaptureError,
        diagnosticCode: DiagnosticCode? = null,
    ) = SourceCaptureResult.Failure(error, diagnosticCode)

    companion object {
        const val MAX_SHARED_RECEIPT_IMAGE_BYTES: Long = PngReceiptImageValidator.MAX_PNG_BYTES
    }
}
