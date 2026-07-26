package dev.bill.source.genericnotification

import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.ParserId
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.SourceIdentity
import dev.bill.source.contract.VersionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTemplateGateTest {
    private val metadata = NotificationMetadata(
        packageName = "fixture.payment",
        channelId = "transaction",
        category = "status",
    )

    @Test
    fun `empty catalog never invokes the notification body reader`() {
        val decision = NotificationTemplateGate(NotificationRouteCatalog.empty()).evaluate(metadata) {
            error("Notification extras must not be read for an empty catalog")
        }

        assertEquals(NotificationGateDecision.IgnoredMetadata, decision)
    }

    @Test
    fun `nonmatching metadata never invokes the notification body reader`() {
        val gate = enabledGate(fixtureRoute())

        val decision = gate.evaluate(
            NotificationMetadata(
                packageName = "fixture.chat",
                channelId = "messages",
                category = "message",
            ),
        ) {
            error("Notification extras must not be read for a nonmatching package/channel")
        }

        assertEquals(NotificationGateDecision.IgnoredMetadata, decision)
    }

    @Test
    fun `disabled route never invokes the notification body reader`() {
        val route = fixtureRoute()
        val gate = NotificationTemplateGate(
            NotificationRouteCatalog(listOf(route)),
            isRouteEnabled = { false },
        )
        var reads = 0

        val decision = gate.evaluate(metadata) {
            reads += 1
            content("fixture paid")
        }

        assertEquals(NotificationGateDecision.IgnoredMetadata, decision)
        assertEquals(0, reads)
        assertFalse(gate.hasMetadataCandidate(metadata))
    }

    @Test
    fun `catalog route stays closed until its owner supplies explicit enablement`() {
        val gate = NotificationTemplateGate(NotificationRouteCatalog(listOf(fixtureRoute())))
        var reads = 0

        val decision = gate.evaluate(metadata) {
            reads += 1
            content("fixture paid")
        }

        assertEquals(NotificationGateDecision.IgnoredMetadata, decision)
        assertEquals(0, reads)
    }

    @Test
    fun `candidate metadata reads content once and requires a content template match`() {
        val gate = enabledGate(fixtureRoute())
        var reads = 0

        val rejected = gate.evaluate(metadata) {
            reads += 1
            content("ordinary chat copy")
        }
        assertEquals(NotificationGateDecision.IgnoredContent, rejected)
        assertEquals(1, reads)

        val accepted = gate.evaluate(metadata) {
            reads += 1
            content("fixture paid")
        }
        assertTrue(accepted is NotificationGateDecision.Accepted)
        accepted as NotificationGateDecision.Accepted
        assertEquals("fixture-payment", accepted.route.routeId)
        assertEquals("v1", accepted.route.template.version)
        assertEquals("fixture paid", accepted.content.field(NotificationField.TEXT))
        assertEquals(2, reads)
    }

    @Test
    fun `content and metadata do not appear in diagnostic to strings`() {
        val secret = "notifiable-secret-marker"
        val content = content(secret)
        val decision = enabledGate(fixtureRoute())
            .evaluate(metadata) { content }

        assertFalse(metadata.toString().contains("fixture.payment"))
        assertFalse(content.toString().contains(secret))
        assertFalse(decision.toString().contains(secret))
    }

    @Test
    fun `content validation rejects blank NUL and oversized fields`() {
        assertNull(NotificationContent.from(mapOf(NotificationField.TEXT to " \n")))
        assertNull(NotificationContent.from(mapOf(NotificationField.TEXT to "left\u0000right")))
        assertNull(NotificationContent.from(mapOf(NotificationField.TEXT to "bad\uD800")))
        assertNull(
            NotificationContent.from(
                mapOf(NotificationField.TEXT to "x".repeat(NotificationContent.MAX_FIELD_CHARACTERS + 1)),
            ),
        )
    }

    @Test
    fun `templates require a concrete Android channel before body content can be selected`() {
        try {
            NotificationTemplate(
                id = "too-broad",
                version = "v1",
                packageName = "fixture.payment",
                contentMatcher = { true },
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        error("A package-only notification template must be rejected")
    }

    @Test
    fun `null category matches only a null category and never becomes a wildcard`() {
        val gate = enabledGate(
            fixtureRoute(
                template = fixtureTemplate(category = null),
            ),
        )
        var reads = 0

        val mismatched = gate.evaluate(metadata) {
            reads += 1
            content("fixture paid")
        }
        assertEquals(NotificationGateDecision.IgnoredMetadata, mismatched)
        assertEquals(0, reads)

        val matched = gate.evaluate(
            NotificationMetadata(
                packageName = "fixture.payment",
                channelId = "transaction",
                category = null,
            ),
        ) {
            reads += 1
            content("fixture paid")
        }
        assertTrue(matched is NotificationGateDecision.Accepted)
        assertEquals(1, reads)
    }

    private fun fixtureRoute(
        template: NotificationTemplate = fixtureTemplate(),
    ) = VerifiedNotificationRoute(
        routeId = "fixture-payment",
        sourceIdentity = SourceIdentity(
            parserId = ParserId("fixture-payment"),
            providerId = ProviderId("fixture-provider"),
            sourceFamily = SourceFamily.GENERIC,
            connectorId = ConnectorId("fixture-payment"),
            capabilities = emptySet(),
            supportedCaptureMethods = setOf(CaptureMethod.NOTIFICATION),
            parserVersion = VersionId("parser-1"),
            ruleVersion = VersionId("rules-1"),
        ),
        template = template,
        safeLabel = "Fixture notification",
    )

    private fun fixtureTemplate(category: String? = "status") = NotificationTemplate(
        id = "fixture-payment",
        version = "v1",
        packageName = "fixture.payment",
        channelId = "transaction",
        category = category,
        contentMatcher = { candidate -> candidate.field(NotificationField.TEXT) == "fixture paid" },
    )

    private fun enabledGate(route: VerifiedNotificationRoute) = NotificationTemplateGate(
        catalog = NotificationRouteCatalog(listOf(route)),
        isRouteEnabled = { true },
    )

    private fun content(text: String): NotificationContent = checkNotNull(
        NotificationContent.from(mapOf(NotificationField.TEXT to text)),
    )
}
