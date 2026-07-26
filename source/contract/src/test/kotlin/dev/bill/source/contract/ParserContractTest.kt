package dev.bill.source.contract

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserContractTest {
    private val identity = SourceIdentity(
        parserId = ParserId("synthetic-parser"),
        providerId = ProviderId("synthetic-provider"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("share-text"),
        capabilities = setOf(SourceCapability.AMOUNT),
        supportedCaptureMethods = setOf(CaptureMethod.SHARE_TEXT),
        parserVersion = VersionId("1.0.0"),
        ruleVersion = VersionId("rules-1"),
    )

    @Test
    fun `source identity accepts only exact family connector and method`() {
        val accepted = rawEvent()

        assertTrue(identity.accepts(accepted))
        assertFalse(identity.accepts(accepted.copy(sourceFamily = SourceFamily.BANK)))
        assertFalse(identity.accepts(accepted.copy(connectorId = ConnectorId("other-connector"))))
        assertFalse(identity.accepts(accepted.copy(captureMethod = CaptureMethod.SHARE_FILE)))
    }

    @Test
    fun `source identity declarations cannot be empty`() {
        identity.copy(capabilities = emptySet())
        assertThrows(IllegalArgumentException::class.java) {
            identity.copy(supportedCaptureMethods = emptySet())
        }
    }

    @Test
    fun `diagnostic code is closed to unknown values`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticCode.valueOf("provider raw error text")
        }

        val result: ParseResult = ParseResult.NeedsUserReview(
            candidate = null,
            diagnostic = SafeDiagnostic(
                code = DiagnosticCode.INSUFFICIENT_FIELDS,
                recoverable = true,
            ),
        )
        assertTrue(result is ParseResult.NeedsUserReview)
    }

    private fun rawEvent() = RawEvent(
        id = RawEventId("event-1"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("share-text"),
        captureMethod = CaptureMethod.SHARE_TEXT,
        captureScope = CaptureScopeId("local-user"),
        contentHash = EvidenceHash.fromBytes("evidence".toByteArray()),
        capturedAt = Instant.parse("2026-07-19T12:00:00Z"),
        payloadId = PayloadId("payload-1"),
    )
}
