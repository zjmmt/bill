package dev.bill.application

import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.EvidenceReadResult
import dev.bill.source.contract.ImageEvidenceMediaTypes
import dev.bill.source.contract.EvidenceReader
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventAppendResult
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.RawEventRepository
import dev.bill.source.genericphotoocr.GenericPhotoOcrParser
import dev.bill.source.genericphotoocr.OcrTranscript
import dev.bill.source.genericsharetext.GenericShareTextParser
import dev.bill.source.genericsharetext.GenericSelectedTextFileParser
import dev.bill.source.genericreceiptimage.GenericSharedReceiptImageParser
import dev.bill.source.pipeline.DraftProposal
import dev.bill.source.pipeline.ParseAttempt
import dev.bill.source.pipeline.ParseAttemptId
import dev.bill.source.pipeline.ParseCommitResult
import dev.bill.source.pipeline.ParseCommitStore
import dev.bill.source.pipeline.ParserRegistry
import dev.bill.source.pipeline.SourceIngestionService
import dev.bill.source.review.EvidenceStageResult
import dev.bill.source.review.EvidenceStagingLeaseId
import dev.bill.source.review.EvidenceStagingStore
import dev.bill.source.review.EvidenceStagingReservation
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.CRC32
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextIngestionServiceTest {
    @Test
    fun `explicit text share stages immutable evidence and creates a review work item`() =
        runBlocking {
            val fixture = Fixture()

            val result = fixture.service().ingest(
                commandId = "command-one",
                sharedText = "merchant supplied text without stable fields",
            )

            assertTrue(result is SourceCaptureResult.ReadyForReview)
            result as SourceCaptureResult.ReadyForReview
            assertFalse(result.alreadyPresent)
            assertEquals("share-command-one", result.rawEventId)
            assertNotNull(fixture.rawEvents.findById(RawEventId(result.rawEventId)))
            assertEquals(1, fixture.evidenceStore.payloads.size)
            assertEquals(1, fixture.commitStore.persisted.size)
            assertNotNull(fixture.commitStore.persisted.values.single().proposal)
        }

    @Test
    fun `selected text file stages generic statement evidence and always wipes caller bytes`() =
        runBlocking {
            val fixture = Fixture()
            val bytes = "date,amount\n2026-07-25,1".toByteArray()

            val result = fixture.selectedFileService().ingest(
                commandId = "file-command",
                evidence = SelectedTextFileEvidence(mediaType = "text/csv", bytes = bytes),
            )

            assertTrue(result is SourceCaptureResult.ReadyForReview)
            result as SourceCaptureResult.ReadyForReview
            assertEquals("file-file-command", result.rawEventId)
            val rawEvent = fixture.rawEvents.findById(RawEventId(result.rawEventId))
            assertEquals(CaptureMethod.STATEMENT_IMPORT, rawEvent?.captureMethod)
            assertEquals("android-saf-text-file", rawEvent?.connectorId?.value)
            assertEquals("text/csv", fixture.evidenceStore.payloads.getValue(PayloadId("file-file-command")).mediaType)
            assertTrue(bytes.all { it == 0.toByte() })
        }

    @Test
    fun `selected text file rejects unsupported MIME without retaining its bytes`() = runBlocking {
        val fixture = Fixture()
        val bytes = "{}".toByteArray()

        val result = fixture.selectedFileService().ingest(
            commandId = "unsupported-file",
            evidence = SelectedTextFileEvidence(mediaType = "application/json", bytes = bytes),
        )

        assertEquals(
            SharedTextCaptureError.PARSE_REJECTED,
            (result as SourceCaptureResult.Failure).error,
        )
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(fixture.rawEvents.events.isEmpty())
    }

    @Test
    fun `retry with the same command and text reuses original capture time`() = runBlocking {
        val fixture = Fixture()
        val firstService = fixture.service("2026-07-19T12:00:00Z")
        val retryService = fixture.service("2026-07-20T12:00:00Z")

        val first = firstService.ingest("stable-command", "same shared text")
        val originalEvent = fixture.rawEvents.findById(RawEventId("share-stable-command"))
        val retry = retryService.ingest("stable-command", "same shared text")
        val retriedEvent = fixture.rawEvents.findById(RawEventId("share-stable-command"))

        assertTrue(first is SourceCaptureResult.ReadyForReview)
        assertTrue(retry is SourceCaptureResult.ReadyForReview)
        assertTrue((retry as SourceCaptureResult.ReadyForReview).alreadyPresent)
        assertEquals(Instant.parse("2026-07-19T12:00:00Z"), originalEvent?.capturedAt)
        assertEquals(originalEvent, retriedEvent)
        assertEquals(1, fixture.rawEvents.events.size)
        assertEquals(1, fixture.commitStore.persisted.size)
    }

    @Test
    fun `same command with different text fails closed before replacing evidence`() = runBlocking {
        val fixture = Fixture()
        fixture.service().ingest("stable-command", "original text")

        val result = fixture.service().ingest("stable-command", "substituted text")

        assertEquals(
            SharedTextCaptureError.RAW_EVENT_COLLISION,
            (result as SourceCaptureResult.Failure).error,
        )
        assertEquals(
            "original text",
            fixture.evidenceStore.payloads.getValue(PayloadId("share-stable-command"))
                .bytes.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `empty blank and oversized shares are rejected without staging evidence`() = runBlocking {
        val fixture = Fixture()

        val empty = fixture.service().ingest("empty-command", "")
        val blank = fixture.service().ingest("blank-command", " \n\t")
        val oversized = fixture.service().ingest(
            "large-command",
            "x".repeat(64 * 1024 + 1),
        )

        assertEquals(
            SharedTextCaptureError.EMPTY_CONTENT,
            (empty as SourceCaptureResult.Failure).error,
        )
        assertEquals(
            SharedTextCaptureError.EMPTY_CONTENT,
            (blank as SourceCaptureResult.Failure).error,
        )
        assertEquals(
            SharedTextCaptureError.CONTENT_TOO_LARGE,
            (oversized as SourceCaptureResult.Failure).error,
        )
        assertTrue(fixture.evidenceStore.payloads.isEmpty())
        assertTrue(fixture.rawEvents.events.isEmpty())
        assertTrue(fixture.commitStore.persisted.isEmpty())
    }

    @Test
    fun `malformed unicode is rejected before immutable evidence is staged`() = runBlocking {
        val fixture = Fixture()

        val malformedUnicode = fixture.service().ingest(
            commandId = "malformed-command",
            sharedText = "\uD800",
        )
        val nulBearing = fixture.service().ingest(
            commandId = "nul-command",
            sharedText = "safe\u0000unsafe",
        )

        assertEquals(
            SharedTextCaptureError.PARSE_REJECTED,
            (malformedUnicode as SourceCaptureResult.Failure).error,
        )
        assertEquals(DiagnosticCode.MALFORMED_EVIDENCE, malformedUnicode.diagnosticCode)
        assertEquals(
            SharedTextCaptureError.PARSE_REJECTED,
            (nulBearing as SourceCaptureResult.Failure).error,
        )
        assertEquals(DiagnosticCode.MALFORMED_EVIDENCE, nulBearing.diagnosticCode)
        assertTrue(fixture.evidenceStore.payloads.isEmpty())
        assertTrue(fixture.rawEvents.events.isEmpty())
    }

    @Test
    fun `storage admission rejects cleared or over-capacity shares before staging`() = runBlocking {
        val clearedFixture = Fixture()
        val cleared = clearedFixture.service(
            evidenceAdmission = FixedEvidenceAdmission(
                EvidenceAdmissionResult.PayloadAlreadyCleared,
            ),
        ).ingest("cleared-command", "shared text")
        val fullFixture = Fixture()
        val full = fullFixture.service(
            evidenceAdmission = FixedEvidenceAdmission(
                EvidenceAdmissionResult.StorageLimitReached,
            ),
        ).ingest("full-command", "shared text")

        assertEquals(
            SharedTextCaptureError.EVIDENCE_ALREADY_CLEARED,
            (cleared as SourceCaptureResult.Failure).error,
        )
        assertEquals(
            SharedTextCaptureError.STORAGE_LIMIT_REACHED,
            (full as SourceCaptureResult.Failure).error,
        )
        assertTrue(clearedFixture.evidenceStore.payloads.isEmpty())
        assertTrue(fullFixture.evidenceStore.payloads.isEmpty())
        assertTrue(clearedFixture.rawEvents.events.isEmpty())
        assertTrue(fullFixture.rawEvents.events.isEmpty())
    }

    @Test
    fun `active recovery rejects a share before staging any evidence`() = runBlocking {
        val fixture = Fixture()

        val result = fixture.service(
            evidenceAdmission = FixedEvidenceAdmission(
                EvidenceAdmissionResult.RecoveryInProgress,
            ),
        ).ingest("recovering-command", "shared text")

        assertEquals(
            SharedTextCaptureError.STAGING_RECOVERY_IN_PROGRESS,
            (result as SourceCaptureResult.Failure).error,
        )
        assertTrue(fixture.evidenceStore.payloads.isEmpty())
        assertTrue(fixture.rawEvents.events.isEmpty())
    }

    @Test
    fun `artifact collision releases reservation without deleting existing evidence`() =
        runBlocking {
            val fixture = Fixture()
            val commandId = "collision-command"
            val newBytes = "replacement text".toByteArray()
            val payloadId = PayloadId("share-$commandId")
            fixture.evidenceStore.payloads[payloadId] = InMemoryEvidenceStore.Payload(
                mediaType = "text/plain",
                bytes = "existing evidence".toByteArray(),
            )
            val reservation = EvidenceStagingReservation(
                rawEventId = RawEventId("share-$commandId"),
                payloadId = payloadId,
                contentHash = EvidenceHash.fromBytes(newBytes),
                payloadSizeBytes = newBytes.size.toLong(),
                leaseId = EvidenceStagingLeaseId("lease-collision"),
                createdAt = Instant.parse("2026-07-19T12:00:00Z"),
                expiresAt = Instant.parse("2026-07-19T12:05:00Z"),
            )
            val admission = FixedEvidenceAdmission(
                EvidenceAdmissionResult.Allowed(
                    alreadyTracked = false,
                    stagingReservation = reservation,
                ),
                discardFailure = IllegalStateException("simulated cleanup failure"),
            )

            val result = fixture.service(evidenceAdmission = admission)
                .ingest(commandId, newBytes.toString(Charsets.UTF_8))

            assertEquals(
                SharedTextCaptureError.EVIDENCE_COLLISION,
                (result as SourceCaptureResult.Failure).error,
            )
            assertEquals(
                "existing evidence",
                fixture.evidenceStore.payloads.getValue(payloadId)
                    .bytes.toString(Charsets.UTF_8),
            )
            assertEquals(
                listOf(FixedEvidenceAdmission.Discard(payloadId, reservation, false)),
                admission.discards,
            )
            assertTrue(fixture.rawEvents.events.isEmpty())
        }

    @Test
    fun `one explicitly shared PNG receipt opens manual review without financial inference`() =
        runBlocking {
            val fixture = Fixture()
            val bytes = png(width = 1440, height = 3120)

            val result = fixture.receiptImageService().ingest(
                commandId = "image-command",
                evidence = SharedReceiptImageEvidence(
                    mediaType = "image/png; charset=binary",
                    bytes = bytes,
                ),
            )

            assertTrue(result is SourceCaptureResult.ReadyForReview)
            result as SourceCaptureResult.ReadyForReview
            assertEquals("receipt-image-image-command", result.rawEventId)
            val rawEvent = fixture.rawEvents.findById(RawEventId(result.rawEventId))
            assertEquals(CaptureMethod.SHARE_FILE, rawEvent?.captureMethod)
            assertEquals("android-share-receipt-image", rawEvent?.connectorId?.value)
            assertEquals(
                ImageEvidenceMediaTypes.PNG,
                fixture.evidenceStore.payloads.getValue(PayloadId(result.rawEventId)).mediaType,
            )
            assertNotNull(fixture.commitStore.persisted.values.single().proposal)
            assertTrue(bytes.all { it == 0.toByte() })
        }

    @Test
    fun `malformed shared image is rejected and wiped before staging`() = runBlocking {
        val fixture = Fixture()
        val bytes = png(width = 1440, height = 3120).also { it[0] = 0 }

        val result = fixture.receiptImageService().ingest(
            commandId = "malformed-image",
            evidence = SharedReceiptImageEvidence(ImageEvidenceMediaTypes.PNG, bytes),
        )

        assertEquals(
            SourceCaptureError.PARSE_REJECTED,
            (result as SourceCaptureResult.Failure).error,
        )
        assertEquals(DiagnosticCode.MALFORMED_EVIDENCE, result.diagnosticCode)
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(fixture.evidenceStore.payloads.isEmpty())
        assertTrue(fixture.rawEvents.events.isEmpty())
    }

    @Test
    fun `local OCR transcript opens review with conservative field suggestions`() = runBlocking {
        val fixture = Fixture()
        val bytes = OcrTranscript.encode(
            listOf(
                "支付成功",
                "￥12.34",
                "商户：测试商店",
            ),
        )!!

        val result = fixture.photoOcrService().ingest(
            commandId = "ocr-command",
            evidence = PhotoOcrTranscriptEvidence(bytes),
        )

        assertTrue(result is SourceCaptureResult.ReadyForReview)
        result as SourceCaptureResult.ReadyForReview
        assertEquals("photo-ocr-ocr-command", result.rawEventId)
        val rawEvent = fixture.rawEvents.findById(RawEventId(result.rawEventId))
        assertEquals(CaptureMethod.PHOTO_OCR, rawEvent?.captureMethod)
        assertEquals(OcrTranscript.CONNECTOR_ID, rawEvent?.connectorId?.value)
        val proposal = fixture.commitStore.persisted.values.single().proposal
        assertEquals(1_234L, proposal?.candidate?.amount?.value?.minorUnits)
        assertEquals("测试商店", proposal?.candidate?.counterparty?.value)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `pending OCR page reaches review without proposing a displayed amount`() = runBlocking {
        val fixture = Fixture()
        val bytes = OcrTranscript.encodeSpatial(
            listOf(
                recognizedLine("Withdraw Balance", top = 600, bottom = 900),
                recognizedLine("Bank is processing", top = 1_000, bottom = 1_350),
                recognizedLine("Withdrawal completed", top = 1_600, bottom = 1_900),
                recognizedLine("￥0.01", top = 2_200, bottom = 3_100),
            ),
        )!!

        val result = fixture.photoOcrService().ingest(
            commandId = "pending-ocr",
            evidence = PhotoOcrTranscriptEvidence(bytes),
        )

        assertTrue(result is SourceCaptureResult.ReadyForReview)
        val proposal = fixture.commitStore.persisted.values.single().proposal
        assertNotNull(proposal)
        assertNull(proposal?.candidate?.amount)
        assertNull(proposal?.candidate?.moneyDirection)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `malformed OCR transcript is rejected and wiped before staging`() = runBlocking {
        val fixture = Fixture()
        val bytes = "not-an-ocr-envelope".toByteArray()

        val result = fixture.photoOcrService().ingest(
            commandId = "bad-ocr",
            evidence = PhotoOcrTranscriptEvidence(bytes),
        )

        assertEquals(
            SourceCaptureError.PARSE_REJECTED,
            (result as SourceCaptureResult.Failure).error,
        )
        assertEquals(DiagnosticCode.MALFORMED_EVIDENCE, result.diagnosticCode)
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(fixture.evidenceStore.payloads.isEmpty())
        assertTrue(fixture.rawEvents.events.isEmpty())
    }

    @Test
    fun `expired OCR lease is rejected and wiped before evidence staging`() = runBlocking {
        val fixture = Fixture()
        val bytes = OcrTranscript.encode(listOf("￥12.34"))!!
        val admission = FixedEvidenceAdmission(
            EvidenceAdmissionResult.Allowed(alreadyTracked = false),
        )

        val result = fixture.photoOcrService(evidenceAdmission = admission).ingestWithCommitLease(
            commandId = "expired-ocr",
            evidence = PhotoOcrTranscriptEvidence(bytes),
            commitLease = PhotoOcrCommitLease { false },
        )

        assertEquals(
            SourceCaptureError.COMMIT_FAILED,
            (result as SourceCaptureResult.Failure).error,
        )
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(fixture.evidenceStore.payloads.isEmpty())
        assertTrue(fixture.rawEvents.events.isEmpty())
        assertTrue(fixture.commitStore.persisted.isEmpty())
        assertEquals(0, admission.admitCalls)
    }

    @Test
    fun `OCR commit lease is claimed before admission and evidence staging`() = runBlocking {
        val fixture = Fixture()
        val bytes = OcrTranscript.encode(listOf("￥12.34"))!!
        val admission = FixedEvidenceAdmission(
            EvidenceAdmissionResult.Allowed(alreadyTracked = false),
        )
        var claims = 0
        var evidenceWasPresentAtClaim = true
        var rawEventWasPresentAtClaim = true

        val result = fixture.photoOcrService(evidenceAdmission = admission).ingestWithCommitLease(
            commandId = "claimed-ocr",
            evidence = PhotoOcrTranscriptEvidence(bytes),
            commitLease = PhotoOcrCommitLease {
                claims += 1
                evidenceWasPresentAtClaim = fixture.evidenceStore.payloads.isNotEmpty()
                rawEventWasPresentAtClaim = fixture.rawEvents.events.isNotEmpty()
                true
            },
        )

        assertTrue(result is SourceCaptureResult.ReadyForReview)
        assertEquals(1, claims)
        assertEquals(1, admission.admitCalls)
        assertFalse(evidenceWasPresentAtClaim)
        assertFalse(rawEventWasPresentAtClaim)
        assertTrue(bytes.all { it == 0.toByte() })
        assertEquals(1, fixture.evidenceStore.payloads.size)
        assertEquals(1, fixture.rawEvents.events.size)
        assertEquals(1, fixture.commitStore.persisted.size)
    }

    @Test
    fun `hung OCR local commit returns unknown and wipes transcript bytes`() = runBlocking {
        val fixture = Fixture()
        val bytes = OcrTranscript.encode(listOf("￥12.34"))!!
        val hangingStore = object : EvidenceStagingStore {
            override suspend fun stage(
                payloadId: PayloadId,
                mediaType: String,
                bytes: ByteArray,
                maxBytes: Long,
            ): EvidenceStageResult = awaitCancellation()
        }

        val result = fixture.photoOcrService(
            evidenceStoreOverride = hangingStore,
            localCommitTimeoutMillis = 25L,
        ).ingestWithCommitLease(
            commandId = "hung-ocr",
            evidence = PhotoOcrTranscriptEvidence(bytes),
            commitLease = PhotoOcrCommitLease { true },
        )

        assertEquals(
            SourceCaptureError.COMMIT_STATUS_UNKNOWN,
            (result as SourceCaptureResult.Failure).error,
        )
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(fixture.rawEvents.events.isEmpty())
        assertTrue(fixture.commitStore.persisted.isEmpty())
    }

    @Test
    fun `local commit exception after staging is reported as unknown and wiped`() = runBlocking {
        val fixture = Fixture()
        val bytes = OcrTranscript.encode(listOf("￥12.34"))!!
        val sideEffectingStore = object : EvidenceStagingStore {
            override suspend fun stage(
                payloadId: PayloadId,
                mediaType: String,
                bytes: ByteArray,
                maxBytes: Long,
            ): EvidenceStageResult {
                fixture.evidenceStore.stage(payloadId, mediaType, bytes, maxBytes)
                throw IllegalStateException("simulated failure after staging")
            }
        }

        val result = fixture.photoOcrService(
            evidenceStoreOverride = sideEffectingStore,
        ).ingestWithCommitLease(
            commandId = "partial-stage-ocr",
            evidence = PhotoOcrTranscriptEvidence(bytes),
            commitLease = PhotoOcrCommitLease { true },
        )

        assertEquals(
            SourceCaptureError.COMMIT_STATUS_UNKNOWN,
            (result as SourceCaptureResult.Failure).error,
        )
        assertTrue(bytes.all { it == 0.toByte() })
        assertEquals(1, fixture.evidenceStore.payloads.size)
        assertTrue(fixture.rawEvents.events.isEmpty())
        assertTrue(fixture.commitStore.persisted.isEmpty())
    }

    private fun recognizedLine(
        value: String,
        top: Int,
        bottom: Int,
    ) = OcrTranscript.RecognizedLine(
        value = value,
        bounds = OcrTranscript.Bounds(
            left = 1_000,
            top = top,
            right = 9_000,
            bottom = bottom,
        ),
    )

    private class Fixture {
        val rawEvents = InMemoryRawEventRepository()
        val evidenceStore = InMemoryEvidenceStore()
        val commitStore = InMemoryCommitStore()

        fun service(
            instant: String = "2026-07-19T12:00:00Z",
            evidenceAdmission: EvidenceStorageAdmission = EvidenceStorageAdmission.AllowAll,
        ): SharedTextIngestionService {
            val clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
            return SharedTextIngestionService(
                rawEventRepository = rawEvents,
                evidenceStore = evidenceStore,
                sourceIngestionService = SourceIngestionService(
                    rawEventRepository = rawEvents,
                    evidenceReader = evidenceStore,
                    parserRegistry = ParserRegistry(listOf(GenericShareTextParser())),
                    commitStore = commitStore,
                    clock = clock,
                    maxEvidenceBytes = 64L * 1024L,
                ),
                evidenceAdmission = evidenceAdmission,
                clock = clock,
            )
        }

        fun selectedFileService(
            instant: String = "2026-07-19T12:00:00Z",
            evidenceAdmission: EvidenceStorageAdmission = EvidenceStorageAdmission.AllowAll,
        ): SelectedTextFileIngestionService {
            val clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
            return SelectedTextFileIngestionService(
                rawEventRepository = rawEvents,
                evidenceStore = evidenceStore,
                sourceIngestionService = SourceIngestionService(
                    rawEventRepository = rawEvents,
                    evidenceReader = evidenceStore,
                    parserRegistry = ParserRegistry(
                        listOf(GenericShareTextParser(), GenericSelectedTextFileParser()),
                    ),
                    commitStore = commitStore,
                    clock = clock,
                    maxEvidenceBytes = 64L * 1024L,
                ),
                evidenceAdmission = evidenceAdmission,
                clock = clock,
            )
        }

        fun receiptImageService(
            instant: String = "2026-07-19T12:00:00Z",
            evidenceAdmission: EvidenceStorageAdmission = EvidenceStorageAdmission.AllowAll,
        ): SharedReceiptImageIngestionService {
            val clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
            return SharedReceiptImageIngestionService(
                rawEventRepository = rawEvents,
                evidenceStore = evidenceStore,
                sourceIngestionService = SourceIngestionService(
                    rawEventRepository = rawEvents,
                    evidenceReader = evidenceStore,
                    parserRegistry = ParserRegistry(listOf(GenericSharedReceiptImageParser())),
                    commitStore = commitStore,
                    clock = clock,
                    maxEvidenceBytes = SharedReceiptImageIngestionService.MAX_SHARED_RECEIPT_IMAGE_BYTES,
                ),
                evidenceAdmission = evidenceAdmission,
                clock = clock,
            )
        }

        fun photoOcrService(
            instant: String = "2026-07-19T12:00:00Z",
            evidenceAdmission: EvidenceStorageAdmission = EvidenceStorageAdmission.AllowAll,
            evidenceStoreOverride: EvidenceStagingStore = evidenceStore,
            localCommitTimeoutMillis: Long = 15_000L,
        ): PhotoOcrTranscriptIngestionService {
            val clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
            return PhotoOcrTranscriptIngestionService(
                rawEventRepository = rawEvents,
                evidenceStore = evidenceStoreOverride,
                sourceIngestionService = SourceIngestionService(
                    rawEventRepository = rawEvents,
                    evidenceReader = evidenceStore,
                    parserRegistry = ParserRegistry(listOf(GenericPhotoOcrParser())),
                    commitStore = commitStore,
                    clock = clock,
                    maxEvidenceBytes = OcrTranscript.MAX_EVIDENCE_BYTES.toLong(),
                ),
                evidenceAdmission = evidenceAdmission,
                clock = clock,
                localCommitTimeoutMillis = localCommitTimeoutMillis,
            )
        }
    }

    private fun png(width: Int, height: Int): ByteArray {
        val header = byteArrayOf(
            (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
            (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
            8, 6, 0, 0, 0,
        )
        return byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) +
            chunk("IHDR", header) +
            chunk("IDAT", byteArrayOf(0)) +
            chunk("IEND", byteArrayOf())
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val chunk = ByteArray(12 + data.size)
        writeInt(chunk, 0, data.size)
        type.forEachIndexed { index, character -> chunk[4 + index] = character.code.toByte() }
        data.copyInto(chunk, destinationOffset = 8)
        val crc = CRC32().apply {
            update(chunk, 4, 4)
            update(data)
        }.value
        writeInt(chunk, 8 + data.size, crc.toInt())
        return chunk
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}

private class FixedEvidenceAdmission(
    private val result: EvidenceAdmissionResult,
    private val discardFailure: RuntimeException? = null,
) : EvidenceStorageAdmission {
    data class Discard(
        val payloadId: PayloadId,
        val reservation: EvidenceStagingReservation?,
        val deletePayload: Boolean,
    )

    val discards = mutableListOf<Discard>()
    var admitCalls = 0
        private set

    override suspend fun admit(
        rawEventId: RawEventId,
        payloadId: PayloadId,
        contentHash: EvidenceHash,
        payloadSizeBytes: Long,
    ): EvidenceAdmissionResult {
        admitCalls += 1
        return result
    }

    override suspend fun discardUncommitted(
        payloadId: PayloadId,
        reservation: EvidenceStagingReservation?,
        deletePayload: Boolean,
    ): Boolean {
        discards += Discard(payloadId, reservation, deletePayload)
        discardFailure?.let { throw it }
        return true
    }
}

private class InMemoryRawEventRepository : RawEventRepository {
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
    data class Payload(val mediaType: String, val bytes: ByteArray)

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
        return EvidenceReadResult.Found(
            EvidenceInput(payload.mediaType, payload.bytes.copyOf()),
        )
    }
}

private class InMemoryCommitStore : ParseCommitStore {
    data class Commit(val attempt: ParseAttempt, val proposal: DraftProposal?)

    val persisted = linkedMapOf<ParseAttemptId, Commit>()

    override suspend fun commit(
        attempt: ParseAttempt,
        draftProposal: DraftProposal?,
    ): ParseCommitResult {
        val existing = persisted[attempt.id]
        if (existing != null) {
            return if (
                existing.attempt.rawEventId == attempt.rawEventId &&
                existing.proposal == draftProposal
            ) {
                ParseCommitResult.AlreadyCommitted
            } else {
                ParseCommitResult.KeyCollision
            }
        }
        persisted[attempt.id] = Commit(attempt, draftProposal)
        return ParseCommitResult.Committed
    }
}
