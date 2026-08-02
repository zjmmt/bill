package dev.bill.app.quickcapture

import dev.bill.application.PhotoOcrCommitLease
import dev.bill.application.PhotoOcrTranscriptCapture
import dev.bill.application.PhotoOcrTranscriptEvidence
import dev.bill.application.SourceCaptureResult
import dev.bill.source.genericphotoocr.OcrTranscript
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedPhotoOcrImporterTest {
    @Test
    fun `successful empty recognition is persisted as blank OCR evidence`() = runBlocking {
        val capture = RecordingCapture()
        val importer = ContentResolverSelectedPhotoOcrImporter(
            reader = object : SelectedImageOcrReader {
                override suspend fun read(uriString: String?): SelectedImageOcrReadResult =
                    SelectedImageOcrReadResult.Empty
            },
            capture = capture,
        )

        val result = importer.ingest("empty-photo", "content://test/empty-photo")

        assertTrue(result is SourceCaptureResult.ReadyForReview)
        assertEquals(0, capture.decodedLineCount)
        assertEquals("empty-photo", capture.commandId)
    }

    private class RecordingCapture : PhotoOcrTranscriptCapture {
        var commandId: String? = null
        var decodedLineCount: Int? = null

        override suspend fun ingest(
            commandId: String,
            evidence: PhotoOcrTranscriptEvidence,
        ): SourceCaptureResult {
            this.commandId = commandId
            decodedLineCount = OcrTranscript.decode(evidence.bytes)?.lines?.size
            evidence.bytes.fill(0)
            return SourceCaptureResult.ReadyForReview(
                proposalId = "blank-review",
                rawEventId = "blank-event",
                alreadyPresent = false,
            )
        }

        override suspend fun ingestWithCommitLease(
            commandId: String,
            evidence: PhotoOcrTranscriptEvidence,
            commitLease: PhotoOcrCommitLease,
        ): SourceCaptureResult = ingest(commandId, evidence)
    }
}
