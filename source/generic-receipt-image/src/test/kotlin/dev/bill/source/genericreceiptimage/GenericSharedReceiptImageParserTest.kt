package dev.bill.source.genericreceiptimage

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.ImageEvidenceMediaTypes
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import java.time.Instant
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericSharedReceiptImageParserTest {
    private val parser = GenericSharedReceiptImageParser()

    @Test
    fun `valid bounded PNG creates a manual review item without financial guesses`() {
        val bytes = png(width = 1440, height = 3120)

        val result = parser.parse(event(bytes), EvidenceInput(ImageEvidenceMediaTypes.PNG, bytes))

        assertTrue(result is ParseResult.NeedsUserReview)
        result as ParseResult.NeedsUserReview
        assertEquals(null, result.candidate)
        assertEquals(DiagnosticCode.INSUFFICIENT_FIELDS, result.diagnostic.code)
    }

    @Test
    fun `wrong type malformed header and oversized dimensions fail closed`() {
        val valid = png(width = 1440, height = 3120)
        val malformed = valid.copyOf().also { it[0] = 0 }
        val oversizedDimensions = png(width = 4097, height = 1)

        assertRejected(
            parser.parse(event(valid), EvidenceInput("image/jpeg", valid)),
            DiagnosticCode.UNSUPPORTED_MEDIA_TYPE,
        )
        assertRejected(
            parser.parse(event(malformed), EvidenceInput(ImageEvidenceMediaTypes.PNG, malformed)),
            DiagnosticCode.MALFORMED_EVIDENCE,
        )
        assertRejected(
            parser.parse(
                event(oversizedDimensions),
                EvidenceInput(ImageEvidenceMediaTypes.PNG, oversizedDimensions),
            ),
            DiagnosticCode.MALFORMED_EVIDENCE,
        )
    }

    @Test
    fun `corrupted chunks and trailing data do not count as a receipt screenshot`() {
        val valid = png(width = 1440, height = 3120)
        val badCrc = valid.copyOf().also { bytes -> bytes[bytes.lastIndex] = 1 }
        val trailingData = valid + byteArrayOf(0)

        assertRejected(
            parser.parse(event(badCrc), EvidenceInput(ImageEvidenceMediaTypes.PNG, badCrc)),
            DiagnosticCode.MALFORMED_EVIDENCE,
        )
        assertRejected(
            parser.parse(
                event(trailingData),
                EvidenceInput(ImageEvidenceMediaTypes.PNG, trailingData),
            ),
            DiagnosticCode.MALFORMED_EVIDENCE,
        )
    }

    @Test
    fun `different connector or capture method is rejected before inspecting image bytes`() {
        val bytes = png(width = 1440, height = 3120)

        assertRejected(
            parser.parse(
                event(bytes).copy(connectorId = ConnectorId("another-connector")),
                EvidenceInput(ImageEvidenceMediaTypes.PNG, bytes),
            ),
            DiagnosticCode.SOURCE_NOT_ACCEPTED,
        )
        assertRejected(
            parser.parse(
                event(bytes).copy(captureMethod = CaptureMethod.PHOTO_OCR),
                EvidenceInput(ImageEvidenceMediaTypes.PNG, bytes),
            ),
            DiagnosticCode.SOURCE_NOT_ACCEPTED,
        )
    }

    private fun assertRejected(result: ParseResult, code: DiagnosticCode) {
        assertTrue(result is ParseResult.Rejected)
        assertEquals(code, (result as ParseResult.Rejected).diagnostic.code)
    }

    private fun event(bytes: ByteArray) = RawEvent(
        id = RawEventId("receipt-event"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("android-share-receipt-image"),
        captureMethod = CaptureMethod.SHARE_FILE,
        captureScope = CaptureScopeId("local-install"),
        contentHash = EvidenceHash.fromBytes(bytes),
        capturedAt = Instant.parse("2026-07-26T00:00:00Z"),
        payloadId = PayloadId("receipt-payload"),
        payloadSizeBytes = bytes.size.toLong(),
    )

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
