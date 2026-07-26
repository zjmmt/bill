package dev.bill.source.contract

import dev.bill.core.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

data class FieldCandidate<T>(
    val value: T,
    val confidence: Double,
    val evidenceLocator: EvidenceLocator,
) {
    init {
        require(confidence in 0.0..1.0) { "Confidence must be between zero and one" }
        require(value !is CharSequence || value.isNotBlank()) {
            "String candidates cannot be blank"
        }
    }
}

enum class DateTimePrecision {
    MINUTE,
    SECOND,
    MILLISECOND,
}

sealed interface ObservedTime {
    val resolvedInstant: Instant?

    data class DateOnly(val date: LocalDate) : ObservedTime {
        override val resolvedInstant: Instant? = null
    }

    data class DateTime(
        val localDateTime: LocalDateTime,
        val precision: DateTimePrecision,
        val offset: ZoneOffset?,
        val zoneId: ZoneId?,
        override val resolvedInstant: Instant?,
    ) : ObservedTime {
        init {
            when (precision) {
                DateTimePrecision.MINUTE -> require(
                    localDateTime.second == 0 && localDateTime.nano == 0,
                ) { "Minute-precision time cannot contain seconds" }

                DateTimePrecision.SECOND -> require(localDateTime.nano == 0) {
                    "Second-precision time cannot contain fractional seconds"
                }

                DateTimePrecision.MILLISECOND -> require(localDateTime.nano % 1_000_000 == 0) {
                    "Millisecond-precision time cannot contain sub-millisecond values"
                }
            }

            require(resolvedInstant == null || offset != null || zoneId != null) {
                "A resolved instant requires an observed offset or zone"
            }

            val zoneOffsets = zoneId?.rules?.getValidOffsets(localDateTime).orEmpty()
            if (offset != null && zoneId != null) {
                require(offset in zoneOffsets) {
                    "The observed offset is not valid for the local time and zone"
                }
            }

            if (resolvedInstant != null) {
                val validInstants = if (offset != null) {
                    listOf(localDateTime.toInstant(offset))
                } else {
                    zoneOffsets.map(localDateTime::toInstant)
                }
                require(resolvedInstant in validInstants) {
                    "The resolved instant conflicts with the observed local time"
                }
            }
        }
    }
}

enum class ObservedMoneyDirection {
    INBOUND,
    OUTBOUND,
}

data class ScopedExternalReference(
    val providerId: ProviderId,
    val accountScopeHash: EvidenceHash,
    val referenceType: String,
    val valueHash: EvidenceHash,
) {
    init {
        require(referenceType.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            "Reference type must be an opaque ASCII token"
        }
    }
}

data class NormalizedCandidate(
    val amount: FieldCandidate<Money>? = null,
    val moneyDirection: FieldCandidate<ObservedMoneyDirection>? = null,
    val occurredAt: FieldCandidate<ObservedTime>? = null,
    val counterparty: FieldCandidate<String>? = null,
    val fundingHint: FieldCandidate<String>? = null,
    val externalReferences: Set<ScopedExternalReference> = emptySet(),
) {
    init {
        require(amount == null || amount.value.minorUnits > 0) {
            "Observed amount must be a positive magnitude; direction is a separate field"
        }
        require(
            amount != null ||
                moneyDirection != null ||
                occurredAt != null ||
                counterparty != null ||
                fundingHint != null ||
                externalReferences.isNotEmpty(),
        ) { "A normalized candidate must contain at least one observed field" }
    }
}
