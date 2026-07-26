package dev.bill.source.genericnotification

import dev.bill.source.contract.NotificationField
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
        val decision = NotificationTemplateGate(emptyList()).evaluate(metadata) {
            error("Notification extras must not be read for an empty catalog")
        }

        assertEquals(NotificationGateDecision.IgnoredMetadata, decision)
    }

    @Test
    fun `nonmatching metadata never invokes the notification body reader`() {
        val gate = NotificationTemplateGate(listOf(fixtureTemplate()))

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
    fun `candidate metadata reads content once and requires a content template match`() {
        val gate = NotificationTemplateGate(listOf(fixtureTemplate()))
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
        assertEquals("fixture-payment", accepted.template.id)
        assertEquals("v1", accepted.template.version)
        assertEquals("fixture paid", accepted.content.field(NotificationField.TEXT))
        assertEquals(2, reads)
    }

    @Test
    fun `content and metadata do not appear in diagnostic to strings`() {
        val secret = "notifiable-secret-marker"
        val content = content(secret)
        val decision = NotificationTemplateGate(listOf(fixtureTemplate())).evaluate(metadata) { content }

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

    private fun fixtureTemplate() = NotificationTemplate(
        id = "fixture-payment",
        version = "v1",
        packageName = "fixture.payment",
        channelId = "transaction",
        category = "status",
        contentMatcher = { candidate -> candidate.field(NotificationField.TEXT) == "fixture paid" },
    )

    private fun content(text: String): NotificationContent = checkNotNull(
        NotificationContent.from(mapOf(NotificationField.TEXT to text)),
    )
}
