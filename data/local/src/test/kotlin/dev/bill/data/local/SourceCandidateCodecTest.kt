package dev.bill.data.local

import dev.bill.core.model.Money
import dev.bill.source.contract.DateTimePrecision
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceLocator
import dev.bill.source.contract.FieldCandidate
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.ObservedEconomicEvent
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.ObservedTime
import dev.bill.source.contract.ProviderId
import dev.bill.source.contract.ScopedExternalReference
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SourceCandidateCodecTest {
    @Test
    fun `round trip preserves every candidate field and locator`() {
        val candidate = NormalizedCandidate(
            amount = FieldCandidate(
                value = Money.cny(12_345),
                confidence = 0.91,
                evidenceLocator = EvidenceLocator.ByteRange(2, 8),
            ),
            moneyDirection = FieldCandidate(
                value = ObservedMoneyDirection.OUTBOUND,
                confidence = 0.82,
                evidenceLocator = EvidenceLocator.TextRange(3, 9),
            ),
            occurredAt = FieldCandidate(
                value = ObservedTime.DateTime(
                    localDateTime = LocalDateTime.parse("2026-07-19T12:34:56"),
                    precision = DateTimePrecision.SECOND,
                    offset = ZoneOffset.ofHours(8),
                    zoneId = null,
                    resolvedInstant = Instant.parse("2026-07-19T04:34:56Z"),
                ),
                confidence = 0.73,
                evidenceLocator = EvidenceLocator.TableCell(1, 2),
            ),
            counterparty = FieldCandidate(
                value = "示例商户",
                confidence = 0.64,
                evidenceLocator = EvidenceLocator.WholePayload,
            ),
            fundingHint = FieldCandidate(
                value = "尾号 1234",
                confidence = 0.55,
                evidenceLocator = EvidenceLocator.TextRange(10, 17),
            ),
            economicEvent = FieldCandidate(
                value = ObservedEconomicEvent.INVEST_BUY,
                confidence = 0.97,
                evidenceLocator = EvidenceLocator.WholePayload,
            ),
            externalReferences = setOf(
                ScopedExternalReference(
                    providerId = ProviderId("fixture-provider"),
                    accountScopeHash = EvidenceHash("a".repeat(64)),
                    referenceType = "order-id",
                    valueHash = EvidenceHash("b".repeat(64)),
                ),
            ),
        )

        val encoded = SourceCandidateCodec.encode(candidate)

        assertEquals(candidate, SourceCandidateCodec.decode(encoded))
        assertNull(SourceCandidateCodec.encode(null))
        assertNull(SourceCandidateCodec.decode(null))
    }

    @Test
    fun `legacy version one candidate remains readable without an economic event`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(0x42494C4C)
            output.writeInt(1)
            repeat(3) { output.writeBoolean(false) }
            output.writeBoolean(true)
            val value = "legacy".toByteArray(Charsets.UTF_8)
            output.writeInt(value.size)
            output.write(value)
            output.writeDouble(0.8)
            output.writeByte(1)
            output.writeBoolean(false)
            output.writeInt(0)
        }

        val decoded = requireNotNull(SourceCandidateCodec.decode(buffer.toByteArray()))

        assertNull(decoded.economicEvent)
        assertEquals("legacy", decoded.counterparty?.value)
        assertTrue(decoded.externalReferences.isEmpty())
    }

    @Test
    fun `malformed utf8 fails closed instead of substituting characters`() {
        val encoded = requireNotNull(
            SourceCandidateCodec.encode(
                NormalizedCandidate(
                    counterparty = FieldCandidate(
                        value = "A",
                        confidence = 1.0,
                        evidenceLocator = EvidenceLocator.WholePayload,
                    ),
                ),
            ),
        )
        encoded[16] = 0xC3.toByte()

        try {
            SourceCandidateCodec.decode(encoded)
            fail("Expected malformed UTF-8 to be rejected")
        } catch (_: CandidateCodecException) {
            // Expected.
        }
    }
}
