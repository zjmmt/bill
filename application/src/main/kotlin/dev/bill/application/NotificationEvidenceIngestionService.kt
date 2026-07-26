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
import dev.bill.source.genericnotification.NotificationEnvelope
import dev.bill.source.genericnotification.NotificationEnvelopeCodec
import dev.bill.source.genericnotification.NotificationEvidenceMediaTypes
import dev.bill.source.pipeline.IngestionFailure
import dev.bill.source.pipeline.IngestionResult
import dev.bill.source.pipeline.ParseCommitDisposition
import dev.bill.source.pipeline.SourceIngestionService
import dev.bill.source.review.EvidenceStageResult
import dev.bill.source.review.EvidenceStagingReservation
import dev.bill.source.review.EvidenceStagingStore
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NotificationCaptureError {
    INVALID_COMMAND,
    MALFORMED_ENVELOPE,
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

sealed interface NotificationCaptureResult {
    data class ReadyForReview(
        val proposalId: String,
        val rawEventId: String,
        val alreadyPresent: Boolean,
    ) : NotificationCaptureResult

    data class Failure(
        val error: NotificationCaptureError,
        val diagnosticCode: DiagnosticCode? = null,
    ) : NotificationCaptureResult
}

interface NotificationEvidenceCapture {
    suspend fun ingest(
        commandId: String,
        envelope: NotificationEnvelope,
    ): NotificationCaptureResult
}

/**
 * A notification-specific private-evidence boundary.
 *
 * It deliberately does not reuse the text-share implementation: notification envelopes have an
 * 8 KiB hard budget, a distinct media type and distinct error surface. The caller owns no raw
 * notification field after [ingest] returns; encoded bytes are wiped on every path.
 */
class NotificationEvidenceIngestionService(
    private val rawEventRepository: RawEventRepository,
    private val evidenceStore: EvidenceStagingStore,
    private val sourceIngestionService: SourceIngestionService,
    private val evidenceAdmission: EvidenceStorageAdmission = EvidenceStorageAdmission.AllowAll,
    private val clock: Clock = Clock.systemUTC(),
) : NotificationEvidenceCapture {
    private val ingestMutex = Mutex()

    override suspend fun ingest(
        commandId: String,
        envelope: NotificationEnvelope,
    ): NotificationCaptureResult {
        val encoded = NotificationEnvelopeCodec.encode(envelope)
            ?: return failure(NotificationCaptureError.CONTENT_TOO_LARGE)
        return try {
            ingestMutex.withLock { ingestLocked(commandId, encoded) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            // The listener has no right to surface or retry raw notification content. Existing
            // staging leases remain recoverable; callers receive only a closed, safe failure.
            failure(NotificationCaptureError.COMMIT_FAILED)
        } finally {
            encoded.fill(0)
        }
    }

    private suspend fun ingestLocked(
        commandId: String,
        encoded: ByteArray,
    ): NotificationCaptureResult {
        if (encoded.isEmpty()) return failure(NotificationCaptureError.MALFORMED_ENVELOPE)
        if (encoded.size.toLong() > MAX_NOTIFICATION_EVIDENCE_BYTES) {
            return failure(NotificationCaptureError.CONTENT_TOO_LARGE)
        }

        val rawEventId: RawEventId
        val payloadId: PayloadId
        try {
            rawEventId = RawEventId("notification-$commandId")
            payloadId = PayloadId("notification-$commandId")
        } catch (_: IllegalArgumentException) {
            return failure(NotificationCaptureError.INVALID_COMMAND)
        }

        val contentHash = EvidenceHash.fromBytes(encoded)
        val existing = rawEventRepository.findById(rawEventId)
        if (
            existing != null &&
            (
                existing.sourceFamily != SourceFamily.GENERIC ||
                    existing.connectorId != CONNECTOR_ID ||
                    existing.captureMethod != CaptureMethod.NOTIFICATION ||
                    existing.captureScope != LOCAL_CAPTURE_SCOPE ||
                    existing.contentHash != contentHash ||
                    existing.payloadId != payloadId ||
                    existing.payloadSizeBytes != encoded.size.toLong()
            )
        ) {
            return failure(
                NotificationCaptureError.RAW_EVENT_COLLISION,
                DiagnosticCode.RAW_EVENT_ID_COLLISION,
            )
        }

        val admission = evidenceAdmission.admit(
            rawEventId = rawEventId,
            payloadId = payloadId,
            contentHash = contentHash,
            payloadSizeBytes = encoded.size.toLong(),
        )
        val reservation = when (admission) {
            is EvidenceAdmissionResult.Allowed -> admission.stagingReservation
            EvidenceAdmissionResult.IdCollision -> return failure(NotificationCaptureError.EVIDENCE_COLLISION)
            EvidenceAdmissionResult.PayloadAlreadyCleared ->
                return failure(NotificationCaptureError.EVIDENCE_ALREADY_CLEARED)

            EvidenceAdmissionResult.StorageLimitReached ->
                return failure(NotificationCaptureError.STORAGE_LIMIT_REACHED)

            EvidenceAdmissionResult.RecoveryInProgress ->
                return failure(NotificationCaptureError.STAGING_RECOVERY_IN_PROGRESS)
        }

        when (
            evidenceStore.stage(
                payloadId = payloadId,
                mediaType = NotificationEvidenceMediaTypes.ENVELOPE,
                bytes = encoded,
                maxBytes = MAX_NOTIFICATION_EVIDENCE_BYTES,
            )
        ) {
            EvidenceStageResult.Stored,
            EvidenceStageResult.AlreadyStored,
            -> Unit

            EvidenceStageResult.IdCollision -> {
                discardBestEffort(payloadId, reservation, deletePayload = false)
                return failure(NotificationCaptureError.EVIDENCE_COLLISION)
            }

            EvidenceStageResult.TooLarge -> {
                discardBestEffort(payloadId, reservation)
                return failure(NotificationCaptureError.CONTENT_TOO_LARGE)
            }

            EvidenceStageResult.Failed -> {
                discardBestEffort(payloadId, reservation)
                return failure(NotificationCaptureError.EVIDENCE_WRITE_FAILED)
            }
        }

        val rawEvent = existing ?: RawEvent(
            id = rawEventId,
            sourceFamily = SourceFamily.GENERIC,
            connectorId = CONNECTOR_ID,
            captureMethod = CaptureMethod.NOTIFICATION,
            captureScope = LOCAL_CAPTURE_SCOPE,
            contentHash = contentHash,
            capturedAt = clock.instant(),
            payloadId = payloadId,
            payloadSizeBytes = encoded.size.toLong(),
        )
        val ingestion = try {
            sourceIngestionService.ingest(rawEvent)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            discardBestEffort(payloadId, reservation)
            return failure(NotificationCaptureError.COMMIT_FAILED)
        }
        if (ingestion is IngestionResult.Failed) {
            discardBestEffort(payloadId, reservation)
        }
        return when (ingestion) {
            is IngestionResult.Recorded -> {
                val proposalId = ingestion.draftProposalId
                    ?: return failure(NotificationCaptureError.PARSE_REJECTED, ingestion.diagnostic?.code)
                NotificationCaptureResult.ReadyForReview(
                    proposalId = proposalId.value,
                    rawEventId = ingestion.rawEventId.value,
                    alreadyPresent = ingestion.commitDisposition ==
                        ParseCommitDisposition.ALREADY_COMMITTED,
                )
            }

            is IngestionResult.Failed -> failure(
                error = when (ingestion.failure) {
                    IngestionFailure.RAW_EVENT_ID_COLLISION -> NotificationCaptureError.RAW_EVENT_COLLISION
                    IngestionFailure.NO_MATCHING_PARSER,
                    IngestionFailure.AMBIGUOUS_PARSER,
                    -> NotificationCaptureError.PARSER_UNAVAILABLE

                    IngestionFailure.COMMIT_KEY_COLLISION,
                    IngestionFailure.COMMIT_REJECTED,
                    -> NotificationCaptureError.COMMIT_FAILED

                    else -> NotificationCaptureError.PARSE_REJECTED
                },
                diagnosticCode = ingestion.diagnostic.code,
            )
        }
    }

    private suspend fun discardBestEffort(
        payloadId: PayloadId,
        reservation: EvidenceStagingReservation?,
        deletePayload: Boolean = true,
    ) {
        try {
            evidenceAdmission.discardUncommitted(payloadId, reservation, deletePayload)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            // A remaining lease is recoverable; preserve the original safe diagnostic.
        }
    }

    private fun failure(
        error: NotificationCaptureError,
        diagnosticCode: DiagnosticCode? = null,
    ) = NotificationCaptureResult.Failure(error, diagnosticCode)

    companion object {
        const val MAX_NOTIFICATION_EVIDENCE_BYTES = NotificationEnvelopeCodec.MAX_ENCODED_BYTES.toLong()

        private val CONNECTOR_ID = ConnectorId("android-notification")
        private val LOCAL_CAPTURE_SCOPE = CaptureScopeId("local-install")
    }
}
