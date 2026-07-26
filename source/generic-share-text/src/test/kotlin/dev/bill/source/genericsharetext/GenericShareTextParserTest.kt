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

class GenericShareTextParserTest {
    private val parser = GenericShareTextParser()

    @Test
    fun `identity accepts only the generic Android text share envelope`() {
        val accepted = rawEvent()

        assertEquals("generic-share-text", parser.identity.parserId.value)
        assertEquals("user-shared-text", parser.identity.providerId.value)
        assertEquals(SourceFamily.GENERIC, parser.identity.sourceFamily)
        assertEquals("android-share-text", parser.identity.connectorId.value)
        assertEquals(setOf(CaptureMethod.SHARE_TEXT), parser.identity.supportedCaptureMethods)
        assertTrue(parser.identity.capabilities.isEmpty())
        assertEquals("parser-1", parser.identity.parserVersion.value)
        assertEquals("rules-1", parser.identity.ruleVersion.value)
        assertTrue(parser.identity.accepts(accepted))
        assertFalse(parser.identity.accepts(accepted.copy(sourceFamily = SourceFamily.ALIPAY)))
        assertFalse(parser.identity.accepts(accepted.copy(captureMethod = CaptureMethod.SHARE_FILE)))
        assertFalse(
            parser.identity.accepts(
                accepted.copy(connectorId = ConnectorId("different-share-connector")),
            ),
        )

        val rejected = parser.parse(
            accepted.copy(sourceFamily = SourceFamily.BANK),
            EvidenceInput("text/plain", "valid".toByteArray()),
        )
        assertRejected(rejected, DiagnosticCode.SOURCE_NOT_ACCEPTED)
    }

    @Test
    fun `valid non-empty UTF-8 text always requires user review without a candidate`() {
        val result = parser.parse(
            rawEvent(),
            EvidenceInput("text/plain", "ordinary shared text".toByteArray()),
        )

        assertTrue(result is ParseResult.NeedsUserReview)
        result as ParseResult.NeedsUserReview
        assertNull(result.candidate)
        assertEquals(DiagnosticCode.INSUFFICIENT_FIELDS, result.diagnostic.code)
        assertTrue(result.diagnostic.recoverable)
    }

    @Test
    fun `malformed UTF-8 is rejected`() {
        val result = parser.parse(
            rawEvent(),
            EvidenceInput("text/plain", byteArrayOf(0xc3.toByte(), 0x28)),
        )

        assertRejected(result, DiagnosticCode.MALFORMED_EVIDENCE)
    }

    @Test
    fun `blank or NUL-bearing text is rejected`() {
        val blank = parser.parse(
            rawEvent(),
            EvidenceInput("text/plain", " \r\n\t".toByteArray()),
        )
        val withNul = parser.parse(
            rawEvent(),
            EvidenceInput("text/plain", "left\u0000right".toByteArray()),
        )

        assertRejected(blank, DiagnosticCode.MALFORMED_EVIDENCE)
        assertRejected(withNul, DiagnosticCode.MALFORMED_EVIDENCE)
    }

    @Test
    fun `media types other than exact text plain are rejected`() {
        val wrongType = parser.parse(
            rawEvent(),
            EvidenceInput("application/json", "{}".toByteArray()),
        )
        val parameterizedType = parser.parse(
            rawEvent(),
            EvidenceInput("text/plain; charset=utf-8", "valid".toByteArray()),
        )

        assertRejected(wrongType, DiagnosticCode.UNSUPPORTED_MEDIA_TYPE)
        assertRejected(parameterizedType, DiagnosticCode.UNSUPPORTED_MEDIA_TYPE)
    }

    @Test
    fun `evidence content never enters parser results or thrown errors`() {
        val sensitiveMarker = listOf("private", "payload", "marker").joinToString("-")
        val parseAttempt = runCatching {
            parser.parse(
                rawEvent(),
                EvidenceInput("text/plain", sensitiveMarker.toByteArray()),
            )
        }

        assertTrue(parseAttempt.isSuccess)
        val renderedResult = parseAttempt.getOrThrow().toString()
        val renderedError = parseAttempt.exceptionOrNull()?.toString().orEmpty()
        assertFalse(renderedResult.contains(sensitiveMarker))
        assertFalse(renderedError.contains(sensitiveMarker))
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
            captureMethod = CaptureMethod.SHARE_TEXT,
            captureScope = CaptureScopeId("local-user"),
            contentHash = EvidenceHash.fromBytes(evidence),
            capturedAt = Instant.parse("2026-07-19T12:00:00Z"),
            payloadId = PayloadId("payload-1"),
        )
    }
}
