package dev.bill.source.genericnotification

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEnvelopeCodecTest {
    private val parser = GenericNotificationParser()

    @Test
    fun `codec round trips bounded envelope without exposing text through to string`() {
        val secret = "private-notification-marker"
        val envelope = envelope(secret)

        val encoded = checkNotNull(NotificationEnvelopeCodec.encode(envelope))
        val result = NotificationEnvelopeCodec.decode(encoded)
        encoded.fill(0)

        assertTrue(result is NotificationEnvelopeDecodeResult.Decoded)
        result as NotificationEnvelopeDecodeResult.Decoded
        assertEquals("fixture-payment", result.envelope.templateId)
        assertEquals("v1", result.envelope.templateVersion)
        assertEquals(1_700_000_000_000L, result.envelope.postedAtEpochMillis)
        assertEquals(secret, result.envelope.content.field(NotificationField.TEXT))
        assertFalse(result.envelope.toString().contains(secret))
    }

    @Test
    fun `codec rejects malformed base64 and does not return a partial envelope`() {
        assertEquals(
            NotificationEnvelopeDecodeResult.Malformed,
            NotificationEnvelopeCodec.decode("%%not-base64%%".toByteArray()),
        )
    }

    @Test
    fun `oversized multibyte notification is rejected before it can be staged`() {
        val fields = NotificationField.entries.associateWith { "账".repeat(1_024) }
        val content = checkNotNull(NotificationContent.from(fields))

        assertNull(
            NotificationEnvelopeCodec.encode(
                NotificationEnvelope("fixture-payment", "v1", 1L, content),
            ),
        )
    }

    @Test
    fun `codec canonicalizes notification field order before hashing evidence`() {
        val first = NotificationEnvelope(
            "fixture-payment",
            "v1",
            1L,
            checkNotNull(
                NotificationContent.from(
                    linkedMapOf(
                        NotificationField.TEXT to "text",
                        NotificationField.TITLE to "title",
                    ),
                ),
            ),
        )
        val second = NotificationEnvelope(
            "fixture-payment",
            "v1",
            1L,
            checkNotNull(
                NotificationContent.from(
                    linkedMapOf(
                        NotificationField.TITLE to "title",
                        NotificationField.TEXT to "text",
                    ),
                ),
            ),
        )

        val firstBytes = checkNotNull(NotificationEnvelopeCodec.encode(first))
        val secondBytes = checkNotNull(NotificationEnvelopeCodec.encode(second))
        try {
            assertTrue(firstBytes.contentEquals(secondBytes))
        } finally {
            firstBytes.fill(0)
            secondBytes.fill(0)
        }
    }

    @Test
    fun `generic parser accepts only a valid notification envelope and produces review work`() {
        val encoded = checkNotNull(NotificationEnvelopeCodec.encode(envelope("safe")))
        val accepted = parser.parse(
            rawEvent(encoded),
            EvidenceInput(NotificationEvidenceMediaTypes.ENVELOPE, encoded),
        )
        val malformed = parser.parse(
            rawEvent("bad".toByteArray()),
            EvidenceInput(NotificationEvidenceMediaTypes.ENVELOPE, "bad".toByteArray()),
        )
        encoded.fill(0)

        assertTrue(accepted is ParseResult.NeedsUserReview)
        accepted as ParseResult.NeedsUserReview
        assertEquals(DiagnosticCode.INSUFFICIENT_FIELDS, accepted.diagnostic.code)
        assertTrue(accepted.diagnostic.recoverable)
        assertTrue(malformed is ParseResult.Rejected)
        malformed as ParseResult.Rejected
        assertEquals(DiagnosticCode.MALFORMED_EVIDENCE, malformed.diagnostic.code)
        assertFalse(malformed.diagnostic.recoverable)
    }

    private fun envelope(text: String): NotificationEnvelope = NotificationEnvelope(
        templateId = "fixture-payment",
        templateVersion = "v1",
        postedAtEpochMillis = 1_700_000_000_000L,
        content = checkNotNull(NotificationContent.from(mapOf(NotificationField.TEXT to text))),
    )

    private fun rawEvent(bytes: ByteArray): RawEvent = RawEvent(
        id = RawEventId("event-1"),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("android-notification"),
        captureMethod = CaptureMethod.NOTIFICATION,
        captureScope = CaptureScopeId("local-user"),
        contentHash = EvidenceHash.fromBytes(bytes),
        capturedAt = Instant.parse("2026-07-25T12:00:00Z"),
        payloadId = PayloadId("payload-1"),
        payloadSizeBytes = bytes.size.toLong(),
    )
}
