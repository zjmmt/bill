package dev.bill.source.pipeline

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SafeDiagnostic
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.SourceParser
import dev.bill.source.contract.VersionId
import java.time.Instant
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserRegistryTest {
    @Test
    fun `resolve returns no match when no parser accepts the event`() {
        val registry = ParserRegistry(
            listOf(parser(identity(connectorId = "other-connector"))),
        )

        assertSame(ParserResolution.NoMatch, registry.resolve(rawEvent()))
    }

    @Test
    fun `resolve returns the sole accepting parser`() {
        val selectedParser = parser(identity())
        val registry = ParserRegistry(
            listOf(
                parser(identity(parserId = "other", connectorId = "other-connector")),
                selectedParser,
            ),
        )

        val resolution = registry.resolve(rawEvent())

        assertTrue(resolution is ParserResolution.Selected)
        assertSame(selectedParser, (resolution as ParserResolution.Selected).parser)
    }

    @Test
    fun `resolve fails closed when multiple parsers accept the same event`() {
        val registry = ParserRegistry(
            listOf(
                parser(identity(parserId = "parser-one")),
                parser(identity(parserId = "parser-two")),
            ),
        )

        assertSame(ParserResolution.Ambiguous, registry.resolve(rawEvent()))
    }

    @Test
    fun `registry snapshots its parser collection`() {
        val selectedParser = parser(identity())
        val mutableParsers = mutableListOf<SourceParser>(selectedParser)
        val registry = ParserRegistry(mutableParsers)
        mutableParsers.clear()

        val resolution = registry.resolve(rawEvent())

        assertTrue(resolution is ParserResolution.Selected)
        assertSame(selectedParser, (resolution as ParserResolution.Selected).parser)
    }

    private fun parser(identity: SourceIdentity): SourceParser =
        object : SourceParser {
            override val identity = identity

            override fun parse(
                rawEvent: RawEvent,
                evidenceInput: EvidenceInput,
            ): ParseResult = ParseResult.Rejected(
                SafeDiagnostic(
                    code = DiagnosticCode.INSUFFICIENT_FIELDS,
                    recoverable = true,
                ),
            )
        }

    private fun identity(
        parserId: String = "test-parser",
        connectorId: String = "test-connector",
    ): SourceIdentity = SourceIdentity(
        parserId = ParserId(parserId),
        providerId = ProviderId("test-provider"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId(connectorId),
        capabilities = emptySet(),
        supportedCaptureMethods = setOf(CaptureMethod.SHARE_TEXT),
        parserVersion = VersionId("parser-1"),
        ruleVersion = VersionId("rules-1"),
    )

    private fun rawEvent(): RawEvent = RawEvent(
        id = RawEventId("event-1"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("test-connector"),
        captureMethod = CaptureMethod.SHARE_TEXT,
        captureScope = CaptureScopeId("local-user"),
        contentHash = EvidenceHash.fromBytes("evidence".toByteArray()),
        capturedAt = Instant.parse("2026-07-19T12:00:00Z"),
        payloadId = PayloadId("payload-1"),
    )
}
