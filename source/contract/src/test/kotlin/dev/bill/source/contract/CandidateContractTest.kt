package dev.bill.source.contract

import dev.bill.core.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CandidateContractTest {
    @Test
    fun `rejects confidence outside the supported range`() {
        assertThrows(IllegalArgumentException::class.java) {
            FieldCandidate(
                value = "synthetic",
                confidence = 1.1,
                evidenceLocator = EvidenceLocator.WholePayload,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FieldCandidate(
                value = "synthetic",
                confidence = Double.NaN,
                evidenceLocator = EvidenceLocator.WholePayload,
            )
        }
    }

    @Test
    fun `rejects blank string candidates`() {
        assertThrows(IllegalArgumentException::class.java) {
            FieldCandidate(
                value = "   ",
                confidence = 0.5,
                evidenceLocator = EvidenceLocator.WholePayload,
            )
        }
    }

    @Test
    fun `rejects a completely empty normalized candidate`() {
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedCandidate()
        }
    }

    @Test
    fun `money direction is sufficient observed evidence`() {
        val candidate = NormalizedCandidate(
            moneyDirection = FieldCandidate(
                value = ObservedMoneyDirection.OUTBOUND,
                confidence = 0.8,
                evidenceLocator = EvidenceLocator.TextRange(0, 3),
            ),
        )

        assertEquals(ObservedMoneyDirection.OUTBOUND, candidate.moneyDirection?.value)
    }

    @Test
    fun `amount is a positive magnitude and cannot encode direction by sign`() {
        listOf(0L, -1L).forEach { invalidMinorUnits ->
            assertThrows(IllegalArgumentException::class.java) {
                NormalizedCandidate(
                    amount = FieldCandidate(
                        value = Money.cny(invalidMinorUnits),
                        confidence = 0.8,
                        evidenceLocator = EvidenceLocator.WholePayload,
                    ),
                )
            }
        }

        val candidate = NormalizedCandidate(
            amount = FieldCandidate(
                value = Money.cny(1),
                confidence = 0.8,
                evidenceLocator = EvidenceLocator.WholePayload,
            ),
        )
        assertEquals(1L, candidate.amount?.value?.minorUnits)
    }

    @Test
    fun `date-only evidence does not invent an instant`() {
        val observed = ObservedTime.DateOnly(LocalDate.parse("2026-07-19"))

        assertNull(observed.resolvedInstant)
    }

    @Test
    fun `minute precision rejects invented seconds`() {
        assertThrows(IllegalArgumentException::class.java) {
            ObservedTime.DateTime(
                localDateTime = LocalDateTime.parse("2026-07-19T19:42:10"),
                precision = DateTimePrecision.MINUTE,
                offset = null,
                zoneId = null,
                resolvedInstant = null,
            )
        }
    }

    @Test
    fun `external references remain scoped by provider and account`() {
        val first = ScopedExternalReference(
            providerId = ProviderId("bank-a"),
            accountScopeHash = EvidenceHash.fromBytes("account-a".toByteArray()),
            referenceType = "transaction-id",
            valueHash = EvidenceHash.fromBytes("same-reference".toByteArray()),
        )
        val second = first.copy(providerId = ProviderId("bank-b"))

        assertNotEquals(first, second)
    }

    @Test
    fun `resolved instant must match the observed offset`() {
        val observed = ObservedTime.DateTime(
            localDateTime = LocalDateTime.parse("2026-07-19T19:42:00"),
            precision = DateTimePrecision.MINUTE,
            offset = ZoneOffset.ofHours(8),
            zoneId = null,
            resolvedInstant = Instant.parse("2026-07-19T11:42:00Z"),
        )

        assertEquals(Instant.parse("2026-07-19T11:42:00Z"), observed.resolvedInstant)
    }

    @Test
    fun `rejects a resolved instant that conflicts with the observed offset`() {
        assertThrows(IllegalArgumentException::class.java) {
            ObservedTime.DateTime(
                localDateTime = LocalDateTime.parse("2026-07-19T19:42:00"),
                precision = DateTimePrecision.MINUTE,
                offset = ZoneOffset.ofHours(8),
                zoneId = null,
                resolvedInstant = Instant.parse("2026-07-19T12:42:00Z"),
            )
        }
    }

    @Test
    fun `rejects an offset that conflicts with the observed zone`() {
        assertThrows(IllegalArgumentException::class.java) {
            ObservedTime.DateTime(
                localDateTime = LocalDateTime.parse("2026-07-19T19:42:00"),
                precision = DateTimePrecision.MINUTE,
                offset = ZoneOffset.UTC,
                zoneId = ZoneId.of("Asia/Shanghai"),
                resolvedInstant = null,
            )
        }
    }
}
