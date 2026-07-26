package dev.bill.application

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.RawEventRepository
import dev.bill.source.contract.TextEvidenceMediaTypes
import dev.bill.source.pipeline.SourceIngestionService
import dev.bill.source.review.EvidenceStagingStore
import java.time.Clock

/** The bounded content copied from one SAF document; it deliberately contains no URI or filename. */
data class SelectedTextFileEvidence(
    val mediaType: String,
    val bytes: ByteArray,
)

interface SelectedTextFileCapture {
    suspend fun ingest(
        commandId: String,
        evidence: SelectedTextFileEvidence,
    ): SourceCaptureResult

    object Unavailable : SelectedTextFileCapture {
        override suspend fun ingest(
            commandId: String,
            evidence: SelectedTextFileEvidence,
        ): SourceCaptureResult = SourceCaptureResult.Failure(
            error = SharedTextCaptureError.PARSER_UNAVAILABLE,
            diagnosticCode = null,
        )
    }
}

/**
 * Captures one text document explicitly selected through the Android document picker.
 *
 * This is a generic transport path only. It stores a bounded private copy immediately and creates
 * `GENERIC/STATEMENT_IMPORT` evidence for user review; it does not infer a provider or parse rows.
 */
class SelectedTextFileIngestionService(
    rawEventRepository: RawEventRepository,
    evidenceStore: EvidenceStagingStore,
    sourceIngestionService: SourceIngestionService,
    evidenceAdmission: EvidenceStorageAdmission = EvidenceStorageAdmission.AllowAll,
    clock: Clock = Clock.systemUTC(),
) : SelectedTextFileCapture {
    private val genericTextCapture = UserEvidenceIngestion(
        descriptor = UserEvidenceCaptureDescriptor(
            idPrefix = "file",
            connectorId = ConnectorId("android-saf-text-file"),
            captureMethod = CaptureMethod.STATEMENT_IMPORT,
            allowedMediaTypes = TextEvidenceMediaTypes.USER_SELECTED_TEXT_FILE,
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
        evidence: SelectedTextFileEvidence,
    ): SourceCaptureResult = try {
        genericTextCapture.ingest(
            commandId = commandId,
            mediaType = TextEvidenceMediaTypes.canonicalize(evidence.mediaType),
            bytes = evidence.bytes,
        )
    } finally {
        evidence.bytes.fill(0)
    }

    companion object {
        const val MAX_SELECTED_TEXT_FILE_BYTES = UserEvidenceIngestion.MAX_TEXT_EVIDENCE_BYTES
    }
}
