package dev.bill.app.quickcapture

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bill.application.PhotoOcrTranscriptEvidence
import dev.bill.application.PhotoOcrTranscriptIngestionService
import dev.bill.application.SourceCaptureResult
import dev.bill.application.SourceEvidenceLifecycleService
import dev.bill.data.local.AppPrivateEvidenceStore
import dev.bill.data.local.BillDatabase
import dev.bill.data.local.BillDatabaseCallbacks
import dev.bill.data.local.RoomRawEventRepository
import dev.bill.data.local.RoomSourceEvidenceLifecycleRepository
import dev.bill.data.local.RoomSourceEvidenceStagingRepository
import dev.bill.data.local.RoomSourceRepository
import dev.bill.source.contract.PayloadId
import dev.bill.source.genericphotoocr.GenericPhotoOcrParser
import dev.bill.source.genericphotoocr.OcrTranscript
import dev.bill.source.pipeline.ParserRegistry
import dev.bill.source.pipeline.SourceIngestionService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoOcrPersistenceInstrumentedTest {
    @Test
    fun photoOcrPersistsDistinctBlankAndDuplicateCapturesForReview() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, BillDatabase::class.java)
            .addCallback(BillDatabaseCallbacks.EnsureSourceEvidencePolicy)
            .allowMainThreadQueries()
            .build()
        val evidenceStore = AppPrivateEvidenceStore(context)
        val rawEvents = RoomRawEventRepository(database)
        val sourceRepository = RoomSourceRepository(database)
        val clock = Clock.fixed(Instant.parse("2026-08-02T12:00:00Z"), ZoneOffset.UTC)
        val lifecycle = SourceEvidenceLifecycleService(
            repository = RoomSourceEvidenceLifecycleRepository(database),
            store = evidenceStore,
            stagingRepository = RoomSourceEvidenceStagingRepository(database),
            artifactStore = evidenceStore,
            clock = clock,
        )
        val pipeline = SourceIngestionService(
            rawEventRepository = rawEvents,
            evidenceReader = evidenceStore,
            parserRegistry = ParserRegistry(listOf(GenericPhotoOcrParser())),
            commitStore = sourceRepository,
            clock = clock,
            maxEvidenceBytes = OcrTranscript.MAX_EVIDENCE_BYTES.toLong(),
        )
        val capture = PhotoOcrTranscriptIngestionService(
            rawEventRepository = rawEvents,
            evidenceStore = evidenceStore,
            sourceIngestionService = pipeline,
            evidenceAdmission = lifecycle,
            clock = clock,
        )
        val suffix = UUID.randomUUID().toString()
        val firstCommand = "room-photo-a-$suffix"
        val secondCommand = "room-photo-b-$suffix"
        val duplicateCommand = "room-photo-c-$suffix"
        val blankCommand = "room-photo-empty-$suffix"
        val commands = listOf(firstCommand, secondCommand, duplicateCommand, blankCommand)

        try {
            val first = capture.ingest(firstCommand, transcript("支付成功", "¥13.70", "微信支付"))
            val second = capture.ingest(secondCommand, transcript("支付成功", "¥3.17", "支付宝"))
            val duplicate = capture.ingest(
                duplicateCommand,
                transcript("支付成功", "¥13.70", "微信支付"),
            )
            val blank = capture.ingest(
                blankCommand,
                PhotoOcrTranscriptEvidence(OcrTranscript.encodeEmpty()),
            )

            assertTrue(first is SourceCaptureResult.ReadyForReview)
            assertTrue(second is SourceCaptureResult.ReadyForReview)
            assertTrue(duplicate is SourceCaptureResult.ReadyForReview)
            assertTrue(blank is SourceCaptureResult.ReadyForReview)

            val records = sourceRepository.observePendingSourceProposals().first()
            assertEquals(4, records.size)
            assertEquals(
                commands.mapTo(linkedSetOf()) { "photo-ocr-$it" },
                records.mapTo(linkedSetOf()) { it.rawEventId },
            )
            val duplicateRecord = records.single {
                it.rawEventId == "photo-ocr-$duplicateCommand"
            }
            val secondRecord = records.single { it.rawEventId == "photo-ocr-$secondCommand" }
            val blankRecord = records.single { it.rawEventId == "photo-ocr-$blankCommand" }
            assertTrue(duplicateRecord.isPossibleDuplicate)
            assertFalse(secondRecord.isPossibleDuplicate)
            assertNull(blankRecord.candidate)
        } finally {
            commands.forEach { command ->
                evidenceStore.delete(PayloadId("photo-ocr-$command"))
            }
            database.close()
        }
    }

    private fun transcript(vararg lines: String): PhotoOcrTranscriptEvidence =
        PhotoOcrTranscriptEvidence(requireNotNull(OcrTranscript.encode(lines.toList())))
}
