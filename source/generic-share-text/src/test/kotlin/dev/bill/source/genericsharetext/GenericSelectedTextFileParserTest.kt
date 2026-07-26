package dev.bill.source.genericsharetext

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericSelectedTextFileParserTest {
    private val parser = GenericSelectedTextFileParser()

    @Test
    fun `identity accepts only the generic SAF text file envelope`() {
        val accepted = rawEvent()

        assertEquals("generic-selected-text-file", parser.identity.parserId.value)
        assertEquals("user-selected-text-file", parser.identity.providerId.value)
        assertEquals(SourceFamily.GENERIC, parser.identity.sourceFamily)
        assertEquals("android-saf-text-file", parser.identity.connectorId.value)
        assertEquals(setOf(CaptureMethod.STATEMENT_IMPORT), parser.identity.supportedCaptureMethods)
        assertTrue(parser.identity.capabilities.isEmpty())
        assertTrue(parser.identity.accepts(accepted))
        assertFalse(parser.identity.accepts(accepted.copy(captureMethod = CaptureMethod.SHARE_TEXT)))
        assertFalse(
            parser.identity.accepts(
                accepted.copy(connectorId = ConnectorId("different-file-connector")),
            ),
        )
    }

    @Test
    fun `allowed text transports require review and do not create a candidate`() {
        val mediaTypes = listOf(
            "text/plain",
            "text/csv",
            "text/tab-separated-values",
            "application/csv",
        )

        mediaTypes.forEach { mediaType ->
            val result = parser.parse(
                rawEvent(),
                EvidenceInput(mediaType, "date,amount\n2026-07-25,1".toByteArray()),
            )

            assertTrue("$mediaType must require review", result is ParseResult.NeedsUserReview)
            result as ParseResult.NeedsUserReview
            assertNull(result.candidate)
            assertEquals(DiagnosticCode.INSUFFICIENT_FIELDS, result.diagnostic.code)
        }
    }

    @Test
    fun `unsupported media type and malformed content fail closed`() {
        assertRejected(
            parser.parse(rawEvent(), EvidenceInput("application/json", "{}".toByteArray())),
            DiagnosticCode.UNSUPPORTED_MEDIA_TYPE,
        )
        assertRejected(
            parser.parse(rawEvent(), EvidenceInput("text/csv", byteArrayOf(0xc3.toByte(), 0x28))),
            DiagnosticCode.MALFORMED_EVIDENCE,
        )
        assertRejected(
            parser.parse(rawEvent(), EvidenceInput("text/plain", "left\u0000right".toByteArray())),
            DiagnosticCode.MALFORMED_EVIDENCE,
        )
    }

    private fun assertRejected(result: ParseResult, code: DiagnosticCode) {
        assertTrue(result is ParseResult.Rejected)
        result as ParseResult.Rejected
        assertEquals(code, result.diagnostic.code)
        assertFalse(result.diagnostic.recoverable)
    }

    private fun rawEvent(): RawEvent {
        val evidence = "fixture".toByteArray(StandardCharsets.UTF_8)
        return RawEvent(
            id = RawEventId("event-1"),
            sourceFamily = SourceFamily.GENERIC,
            connectorId = parser.identity.connectorId,
            captureMethod = CaptureMethod.STATEMENT_IMPORT,
            captureScope = CaptureScopeId("local-user"),
            contentHash = EvidenceHash.fromBytes(evidence),
            capturedAt = Instant.parse("2026-07-25T12:00:00Z"),
            payloadId = PayloadId("payload-1"),
        )
    }
}
