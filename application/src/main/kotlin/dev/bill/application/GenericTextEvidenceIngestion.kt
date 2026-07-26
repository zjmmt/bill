package dev.bill.application

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.RawEventRepository
import dev.bill.source.contract.SourceFamily
import dev.bill.source.pipeline.IngestionResult
import dev.bill.source.pipeline.SourceIngestionService
import dev.bill.source.review.EvidenceStageResult
import dev.bill.source.review.EvidenceStagingReservation
import dev.bill.source.review.EvidenceStagingStore
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class UserEvidenceCaptureDescriptor(
    val idPrefix: String,
    val connectorId: ConnectorId,
    val captureMethod: CaptureMethod,
    val allowedMediaTypes: Set<String>,
    val maxEvidenceBytes: Long,
    val requiresStrictUtf8Text: Boolean,
)

/**
 * Shared storage and source-pipeline boundary for bounded, user-provided evidence.
 *
 * Callers own the byte array and must wipe it after this method returns. This class never infers a
 * provider or a transaction field: the parser chosen by [descriptor] always creates a review item.
 */
internal class UserEvidenceIngestion(
    private val descriptor: UserEvidenceCaptureDescriptor,
    private val rawEventRepository: RawEventRepository,
    private val evidenceStore: EvidenceStagingStore,
    private val sourceIngestionService: SourceIngestionService,
    private val evidenceAdmission: EvidenceStorageAdmission,
    private val clock: Clock,
) {
    private val ingestMutex = Mutex()

    suspend fun ingest(
        commandId: String,
        mediaType: String,
        bytes: ByteArray,
    ): SourceCaptureResult = ingestMutex.withLock {
        ingestLocked(commandId, mediaType, bytes)
    }

    private suspend fun ingestLocked(
        commandId: String,
        mediaType: String,
        bytes: ByteArray,
    ): SourceCaptureResult {
        if (bytes.isEmpty()) return failure(SharedTextCaptureError.EMPTY_CONTENT)
        if (bytes.size.toLong() > descriptor.maxEvidenceBytes) {
            return failure(SharedTextCaptureError.CONTENT_TOO_LARGE)
        }
        if (mediaType !in descriptor.allowedMediaTypes) {
            return failure(
                SharedTextCaptureError.PARSE_REJECTED,
                DiagnosticCode.UNSUPPORTED_MEDIA_TYPE,
            )
        }
        if (descriptor.requiresStrictUtf8Text) {
            val text = try {
                decodeUtf8(bytes)
            } catch (_: CharacterCodingException) {
                return failure(
                    SharedTextCaptureError.PARSE_REJECTED,
                    DiagnosticCode.MALFORMED_EVIDENCE,
                )
            }
            if (text.isBlank()) return failure(SharedTextCaptureError.EMPTY_CONTENT)
            if (NUL in text) {
                return failure(
                    SharedTextCaptureError.PARSE_REJECTED,
                    DiagnosticCode.MALFORMED_EVIDENCE,
                )
            }
        }

        val rawEventId: RawEventId
        val payloadId: PayloadId
        try {
            rawEventId = RawEventId("${descriptor.idPrefix}-$commandId")
            payloadId = PayloadId("${descriptor.idPrefix}-$commandId")
        } catch (_: IllegalArgumentException) {
            return failure(SharedTextCaptureError.INVALID_COMMAND)
        }

        val contentHash = EvidenceHash.fromBytes(bytes)
        val existing = rawEventRepository.findById(rawEventId)
        if (
            existing != null &&
            (
                existing.sourceFamily != SourceFamily.GENERIC ||
                    existing.connectorId != descriptor.connectorId ||
                    existing.captureMethod != descriptor.captureMethod ||
                    existing.captureScope != LOCAL_CAPTURE_SCOPE ||
                    existing.contentHash != contentHash ||
                    existing.payloadId != payloadId ||
                    existing.payloadSizeBytes != bytes.size.toLong()
            )
        ) {
            return failure(
                SharedTextCaptureError.RAW_EVENT_COLLISION,
                DiagnosticCode.RAW_EVENT_ID_COLLISION,
            )
        }

        val admission = evidenceAdmission.admit(
            rawEventId = rawEventId,
            payloadId = payloadId,
            contentHash = contentHash,
            payloadSizeBytes = bytes.size.toLong(),
        )
        val stagingReservation = when (admission) {
            is EvidenceAdmissionResult.Allowed -> admission.stagingReservation
            EvidenceAdmissionResult.IdCollision -> return failure(
                SharedTextCaptureError.EVIDENCE_COLLISION,
            )
            EvidenceAdmissionResult.PayloadAlreadyCleared -> return failure(
                SharedTextCaptureError.EVIDENCE_ALREADY_CLEARED,
            )
            EvidenceAdmissionResult.StorageLimitReached -> return failure(
                SharedTextCaptureError.STORAGE_LIMIT_REACHED,
            )

            EvidenceAdmissionResult.RecoveryInProgress -> return failure(
                SharedTextCaptureError.STAGING_RECOVERY_IN_PROGRESS,
            )
        }

        val stageResult = evidenceStore.stage(
            payloadId = payloadId,
            mediaType = mediaType,
            bytes = bytes,
            maxBytes = descriptor.maxEvidenceBytes,
        )
        when (stageResult) {
            EvidenceStageResult.Stored,
            EvidenceStageResult.AlreadyStored,
            -> Unit

            EvidenceStageResult.IdCollision -> {
                discardBestEffort(
                    payloadId = payloadId,
                    reservation = stagingReservation,
                    deletePayload = false,
                )
                return failure(SharedTextCaptureError.EVIDENCE_COLLISION)
            }

            EvidenceStageResult.TooLarge -> {
                discardBestEffort(payloadId, stagingReservation)
                return failure(SharedTextCaptureError.CONTENT_TOO_LARGE)
            }

            EvidenceStageResult.Failed -> {
                discardBestEffort(payloadId, stagingReservation)
                return failure(SharedTextCaptureError.EVIDENCE_WRITE_FAILED)
            }
        }

        val rawEvent = existing ?: RawEvent(
            id = rawEventId,
            sourceFamily = SourceFamily.GENERIC,
            connectorId = descriptor.connectorId,
            captureMethod = descriptor.captureMethod,
            captureScope = LOCAL_CAPTURE_SCOPE,
            contentHash = contentHash,
            capturedAt = clock.instant(),
            payloadId = payloadId,
            payloadSizeBytes = bytes.size.toLong(),
        )
        val ingestionResult = sourceIngestionService.ingest(rawEvent)
        if (ingestionResult is IngestionResult.Failed) {
            discardBestEffort(payloadId, stagingReservation)
        }
        return when (val result = ingestionResult) {
            is IngestionResult.Recorded -> {
                val proposalId = result.draftProposalId
                    ?: return failure(
                        SharedTextCaptureError.PARSE_REJECTED,
                        result.diagnostic?.code,
                    )
                SourceCaptureResult.ReadyForReview(
                    proposalId = proposalId.value,
                    rawEventId = result.rawEventId.value,
                    alreadyPresent = result.commitDisposition ==
                        dev.bill.source.pipeline.ParseCommitDisposition.ALREADY_COMMITTED,
                )
            }

            is IngestionResult.Failed -> failure(
                error = when (result.failure) {
                    dev.bill.source.pipeline.IngestionFailure.RAW_EVENT_ID_COLLISION ->
                        SharedTextCaptureError.RAW_EVENT_COLLISION

                    dev.bill.source.pipeline.IngestionFailure.NO_MATCHING_PARSER,
                    dev.bill.source.pipeline.IngestionFailure.AMBIGUOUS_PARSER,
                    -> SharedTextCaptureError.PARSER_UNAVAILABLE

                    dev.bill.source.pipeline.IngestionFailure.COMMIT_KEY_COLLISION,
                    dev.bill.source.pipeline.IngestionFailure.COMMIT_REJECTED,
                    -> SharedTextCaptureError.COMMIT_FAILED

                    else -> SharedTextCaptureError.PARSE_REJECTED
                },
                diagnosticCode = result.diagnostic.code,
            )
        }
    }

    private suspend fun discardBestEffort(
        payloadId: PayloadId,
        reservation: EvidenceStagingReservation?,
        deletePayload: Boolean = true,
    ) {
        try {
            evidenceAdmission.discardUncommitted(
                payloadId = payloadId,
                reservation = reservation,
                deletePayload = deletePayload,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            // The lease remains recoverable; cleanup must not hide the primary diagnostic.
        }
    }

    private fun failure(
        error: SourceCaptureError,
        diagnosticCode: DiagnosticCode? = null,
    ) = SourceCaptureResult.Failure(error, diagnosticCode)

    @Throws(CharacterCodingException::class)
    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    companion object {
        const val MAX_TEXT_EVIDENCE_BYTES = 64L * 1024L

        private const val NUL = '\u0000'
        private val LOCAL_CAPTURE_SCOPE = CaptureScopeId("local-install")
    }
}
