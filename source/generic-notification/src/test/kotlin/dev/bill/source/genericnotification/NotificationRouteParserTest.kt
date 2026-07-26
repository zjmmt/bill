package dev.bill.source.genericnotification

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.VersionId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRouteParserTest {
    private val route = VerifiedNotificationRoute(
        routeId = "fixture-payment",
        sourceIdentity = SourceIdentity(
            parserId = ParserId("fixture-payment"),
            providerId = ProviderId("fixture-provider"),
            sourceFamily = SourceFamily.BANK,
            connectorId = ConnectorId("fixture-payment"),
            capabilities = emptySet(),
            supportedCaptureMethods = setOf(CaptureMethod.NOTIFICATION),
            parserVersion = VersionId("parser-1"),
            ruleVersion = VersionId("rules-1"),
        ),
        template = NotificationTemplate(
            id = "fixture-payment",
            version = "v1",
            packageName = "fixture.payment",
            channelId = "transaction",
            category = "status",
            contentMatcher = { true },
        ),
        safeLabel = "Fixture bank notification",
    )

    @Test
    fun `matching route envelope reaches user review without inferring transaction fields`() {
        val result = NotificationRouteParser(route).parse(rawEvent(), evidence(envelope()))

        assertTrue(result is dev.bill.source.contract.ParseResult.NeedsUserReview)
        result as dev.bill.source.contract.ParseResult.NeedsUserReview
        assertEquals(null, result.candidate)
        assertEquals(DiagnosticCode.INSUFFICIENT_FIELDS, result.diagnostic.code)
    }

    @Test
    fun `wrong route version or raw source identity fails closed`() {
        val parser = NotificationRouteParser(route)

        val wrongVersion = parser.parse(
            rawEvent(),
            evidence(envelope(templateVersion = "v2")),
        )
        val wrongIdentity = parser.parse(
            rawEvent(connectorId = ConnectorId("other-route")),
            evidence(envelope()),
        )

        assertEquals(
            DiagnosticCode.SOURCE_NOT_ACCEPTED,
            (wrongVersion as dev.bill.source.contract.ParseResult.Rejected).diagnostic.code,
        )
        assertEquals(
            DiagnosticCode.SOURCE_NOT_ACCEPTED,
            (wrongIdentity as dev.bill.source.contract.ParseResult.Rejected).diagnostic.code,
        )
    }

    @Test
    fun `catalog rejects a route that would conflict with the transport parser`() {
        val conflictingRoute = VerifiedNotificationRoute(
            routeId = "android-notification",
            sourceIdentity = SourceIdentity(
                parserId = ParserId("android-notification"),
                providerId = ProviderId("fixture-provider"),
                sourceFamily = SourceFamily.GENERIC,
                connectorId = ConnectorId("android-notification"),
                capabilities = emptySet(),
                supportedCaptureMethods = setOf(CaptureMethod.NOTIFICATION),
                parserVersion = VersionId("parser-1"),
                ruleVersion = VersionId("rules-1"),
            ),
            template = NotificationTemplate(
                id = "android-notification",
                version = "v1",
                packageName = "fixture.payment",
                channelId = "transaction",
                category = "status",
                contentMatcher = { true },
            ),
            safeLabel = "Fixture conflicting notification",
        )

        try {
            NotificationRouteCatalog(listOf(conflictingRoute))
        } catch (_: IllegalArgumentException) {
            return
        }
        error("A route must not collide with the generic notification parser")
    }

    private fun rawEvent(connectorId: ConnectorId = route.sourceIdentity.connectorId) = RawEvent(
        id = RawEventId("raw-fixture"),
        sourceFamily = SourceFamily.BANK,
        connectorId = connectorId,
        captureMethod = CaptureMethod.NOTIFICATION,
        captureScope = CaptureScopeId("local-install"),
        contentHash = EvidenceHash.fromBytes("fixture".toByteArray()),
        capturedAt = Instant.parse("2026-07-26T00:00:00Z"),
        payloadId = PayloadId("payload-fixture"),
        payloadSizeBytes = 7L,
    )

    private fun envelope(templateVersion: String = "v1") = NotificationEnvelope(
        templateId = "fixture-payment",
        templateVersion = templateVersion,
        postedAtEpochMillis = 1_700_000_000_000L,
        content = checkNotNull(NotificationContent.from(mapOf(NotificationField.TEXT to "fixture paid"))),
    )

    private fun evidence(envelope: NotificationEnvelope): EvidenceInput {
        val encoded = checkNotNull(NotificationEnvelopeCodec.encode(envelope))
        return try {
            EvidenceInput(NotificationEvidenceMediaTypes.ENVELOPE, encoded)
        } finally {
            encoded.fill(0)
        }
    }
}
