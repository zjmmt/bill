package dev.bill.application

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.EvidenceReadResult
import dev.bill.source.contract.EvidenceReader
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventAppendResult
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.RawEventRepository
import dev.bill.source.genericnotification.GenericNotificationParser
import dev.bill.source.genericnotification.NotificationContent
import dev.bill.source.genericnotification.NotificationEnvelope
import dev.bill.source.genericnotification.NotificationEnvelopeCodec
import dev.bill.source.pipeline.DraftProposal
import dev.bill.source.pipeline.ParseAttempt
import dev.bill.source.pipeline.ParseAttemptId
import dev.bill.source.pipeline.ParseCommitResult
import dev.bill.source.pipeline.ParseCommitStore
import dev.bill.source.pipeline.ParserRegistry
import dev.bill.source.pipeline.SourceIngestionService
import dev.bill.source.review.EvidenceStageResult
import dev.bill.source.review.EvidenceStagingLeaseId
import dev.bill.source.review.EvidenceStagingReservation
import dev.bill.source.review.EvidenceStagingStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEvidenceIngestionServiceTest {
    @Test
    fun `notification envelope creates only review work with bounded private evidence`() = runBlocking {
        val fixture = Fixture()

        val result = fixture.service().ingest("notification-one", envelope("private paid marker"))

        assertTrue(result is NotificationCaptureResult.ReadyForReview)
        result as NotificationCaptureResult.ReadyForReview
        assertFalse(result.alreadyPresent)
        assertEquals("notification-notification-one", result.rawEventId)
        val rawEvent = fixture.rawEvents.findById(RawEventId(result.rawEventId))
        assertEquals(CaptureMethod.NOTIFICATION, rawEvent?.captureMethod)
        assertEquals("android-notification", rawEvent?.connectorId?.value)
        assertEquals(1, fixture.evidenceStore.payloads.size)
        assertEquals(1, fixture.commitStore.commits.size)
        assertTrue(fixture.commitStore.commits.values.single().proposal != null)
    }

    @Test
    fun `same notification command retries safely and altered evidence fails closed`() = runBlocking {
        val fixture = Fixture()
        val service = fixture.service()

        val first = service.ingest("stable-notification", envelope("first marker"))
        val retry = service.ingest("stable-notification", envelope("first marker"))
        val altered = service.ingest("stable-notification", envelope("different marker"))

        assertTrue(first is NotificationCaptureResult.ReadyForReview)
        assertTrue(retry is NotificationCaptureResult.ReadyForReview)
        assertTrue((retry as NotificationCaptureResult.ReadyForReview).alreadyPresent)
        assertEquals(
            NotificationCaptureError.RAW_EVENT_COLLISION,
            (altered as NotificationCaptureResult.Failure).error,
        )
        val payload = fixture.evidenceStore.payloads.getValue(PayloadId("notification-stable-notification"))
        val decoded = NotificationEnvelopeCodec.decode(payload.bytes)
        assertTrue(decoded is dev.bill.source.genericnotification.NotificationEnvelopeDecodeResult.Decoded)
        decoded as dev.bill.source.genericnotification.NotificationEnvelopeDecodeResult.Decoded
        assertEquals("first marker", decoded.envelope.content.field(NotificationField.TEXT))
        assertEquals(1, fixture.rawEvents.events.size)
        assertEquals(1, fixture.commitStore.commits.size)
    }

    @Test
    fun `oversized envelope is rejected before any private evidence is staged`() = runBlocking {
        val fixture = Fixture()
        val oversizedContent = checkNotNull(
            NotificationContent.from(
                NotificationField.entries.associateWith { "账".repeat(1_024) },
            ),
        )

        val result = fixture.service().ingest(
            "large-notification",
            NotificationEnvelope("fixture-payment", "v1", 1_700_000_000_000L, oversizedContent),
        )

        assertEquals(
            NotificationCaptureError.CONTENT_TOO_LARGE,
            (result as NotificationCaptureResult.Failure).error,
        )
        assertTrue(fixture.rawEvents.events.isEmpty())
        assertTrue(fixture.evidenceStore.payloads.isEmpty())
        assertTrue(fixture.commitStore.commits.isEmpty())
    }

    @Test
    fun `unexpected repository failure becomes a closed result without staging notification text`() = runBlocking {
        val fixture = Fixture()
        val failingRepository = object : RawEventRepository {
            override suspend fun append(event: RawEvent): RawEventAppendResult =
                error("simulated raw-event storage fault")

            override suspend fun findById(id: RawEventId): RawEvent? =
                error("simulated raw-event storage fault")
        }
        val clock = Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC)
        val service = NotificationEvidenceIngestionService(
            rawEventRepository = failingRepository,
            evidenceStore = fixture.evidenceStore,
            sourceIngestionService = SourceIngestionService(
                rawEventRepository = failingRepository,
                evidenceReader = fixture.evidenceStore,
                parserRegistry = ParserRegistry(listOf(GenericNotificationParser())),
                commitStore = fixture.commitStore,
                clock = clock,
                maxEvidenceBytes = NotificationEvidenceIngestionService.MAX_NOTIFICATION_EVIDENCE_BYTES,
            ),
            clock = clock,
        )

        val result = service.ingest("failing-notification", envelope("private failure marker"))

        assertEquals(
            NotificationCaptureError.COMMIT_FAILED,
            (result as NotificationCaptureResult.Failure).error,
        )
        assertTrue(fixture.evidenceStore.payloads.isEmpty())
        assertTrue(fixture.commitStore.commits.isEmpty())
    }

    @Test
    fun `pipeline fault releases the staged notification reservation`() = runBlocking {
        val fixture = Fixture()
        val rawEventId = RawEventId("notification-stage-failure")
        val payloadId = PayloadId("notification-stage-failure")
        val reservation = EvidenceStagingReservation(
            rawEventId = rawEventId,
            payloadId = payloadId,
            contentHash = EvidenceHash.fromBytes("reservation".toByteArray()),
            payloadSizeBytes = 1L,
            leaseId = EvidenceStagingLeaseId("lease-stage-failure"),
            createdAt = Instant.parse("2026-07-25T12:00:00Z"),
            expiresAt = Instant.parse("2026-07-25T12:05:00Z"),
        )
        val admission = TrackingAdmission(reservation)
        val failingRepository = object : RawEventRepository {
            override suspend fun append(event: RawEvent): RawEventAppendResult =
                error("simulated parse-pipeline storage fault")

            override suspend fun findById(id: RawEventId): RawEvent? = null
        }
        val clock = Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC)
        val service = NotificationEvidenceIngestionService(
            rawEventRepository = failingRepository,
            evidenceStore = fixture.evidenceStore,
            sourceIngestionService = SourceIngestionService(
                rawEventRepository = failingRepository,
                evidenceReader = fixture.evidenceStore,
                parserRegistry = ParserRegistry(listOf(GenericNotificationParser())),
                commitStore = fixture.commitStore,
                clock = clock,
                maxEvidenceBytes = NotificationEvidenceIngestionService.MAX_NOTIFICATION_EVIDENCE_BYTES,
            ),
            evidenceAdmission = admission,
            clock = clock,
        )

        val result = service.ingest("stage-failure", envelope("private staged failure marker"))

        assertEquals(
            NotificationCaptureError.COMMIT_FAILED,
            (result as NotificationCaptureResult.Failure).error,
        )
        assertEquals(listOf(PayloadId("notification-stage-failure")), admission.discardedPayloadIds)
    }

    private fun envelope(text: String): NotificationEnvelope = NotificationEnvelope(
        templateId = "fixture-payment",
        templateVersion = "v1",
        postedAtEpochMillis = 1_700_000_000_000L,
        content = checkNotNull(NotificationContent.from(mapOf(NotificationField.TEXT to text))),
    )

    private class Fixture {
        val rawEvents = InMemoryRawEvents()
        val evidenceStore = InMemoryEvidenceStore()
        val commitStore = InMemoryCommitStore()

        fun service(): NotificationEvidenceIngestionService {
            val clock = Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC)
            return NotificationEvidenceIngestionService(
                rawEventRepository = rawEvents,
                evidenceStore = evidenceStore,
                sourceIngestionService = SourceIngestionService(
                    rawEventRepository = rawEvents,
                    evidenceReader = evidenceStore,
                    parserRegistry = ParserRegistry(listOf(GenericNotificationParser())),
                    commitStore = commitStore,
                    clock = clock,
                    maxEvidenceBytes = NotificationEvidenceIngestionService.MAX_NOTIFICATION_EVIDENCE_BYTES,
                ),
                clock = clock,
            )
        }
    }

    private class InMemoryRawEvents : RawEventRepository {
        val events = linkedMapOf<RawEventId, RawEvent>()

        override suspend fun append(event: RawEvent): RawEventAppendResult {
            val existing = events[event.id]
            return when {
                existing == null -> {
                    events[event.id] = event
                    RawEventAppendResult.Inserted
                }

                existing == event -> RawEventAppendResult.AlreadyPresent
                else -> RawEventAppendResult.IdCollision
            }
        }

        override suspend fun findById(id: RawEventId): RawEvent? = events[id]
    }

    private class InMemoryEvidenceStore : EvidenceReader(), EvidenceStagingStore {
        data class Payload(
            val mediaType: String,
            val bytes: ByteArray,
        )

        val payloads = linkedMapOf<PayloadId, Payload>()

        override suspend fun stage(
            payloadId: PayloadId,
            mediaType: String,
            bytes: ByteArray,
            maxBytes: Long,
        ): EvidenceStageResult {
            if (bytes.size > maxBytes) return EvidenceStageResult.TooLarge
            val existing = payloads[payloadId]
            return when {
                existing == null -> {
                    payloads[payloadId] = Payload(mediaType, bytes.copyOf())
                    EvidenceStageResult.Stored
                }

                existing.mediaType == mediaType && existing.bytes.contentEquals(bytes) ->
                    EvidenceStageResult.AlreadyStored

                else -> EvidenceStageResult.IdCollision
            }
        }

        override suspend fun readBounded(
            payloadId: PayloadId,
            maxBytes: Long,
        ): EvidenceReadResult {
            val payload = payloads[payloadId] ?: return EvidenceReadResult.NotFound
            return EvidenceReadResult.Found(EvidenceInput(payload.mediaType, payload.bytes.copyOf()))
        }
    }

    private class InMemoryCommitStore : ParseCommitStore {
        data class Commit(
            val attempt: ParseAttempt,
            val proposal: DraftProposal?,
        )

        val commits = linkedMapOf<ParseAttemptId, Commit>()

        override suspend fun commit(
            attempt: ParseAttempt,
            draftProposal: DraftProposal?,
        ): ParseCommitResult {
            val existing = commits[attempt.id]
            if (existing != null) {
                return if (existing.attempt.rawEventId == attempt.rawEventId && existing.proposal == draftProposal) {
                    ParseCommitResult.AlreadyCommitted
                } else {
                    ParseCommitResult.KeyCollision
                }
            }
            commits[attempt.id] = Commit(attempt, draftProposal)
            return ParseCommitResult.Committed
        }
    }

    private class TrackingAdmission(
        private val reservation: EvidenceStagingReservation,
    ) : EvidenceStorageAdmission {
        val discardedPayloadIds = mutableListOf<PayloadId>()

        override suspend fun admit(
            rawEventId: RawEventId,
            payloadId: PayloadId,
            contentHash: EvidenceHash,
            payloadSizeBytes: Long,
        ): EvidenceAdmissionResult = EvidenceAdmissionResult.Allowed(
            alreadyTracked = false,
            stagingReservation = reservation,
        )

        override suspend fun discardUncommitted(
            payloadId: PayloadId,
            reservation: EvidenceStagingReservation?,
            deletePayload: Boolean,
        ): Boolean {
            discardedPayloadIds += payloadId
            return true
        }
    }
}
