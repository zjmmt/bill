package dev.bill.core.domain

import dev.bill.core.model.AccountId
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import java.math.BigDecimal
import java.time.Instant

@JvmInline
value class InvestmentPositionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Investment position id cannot be blank" }
    }
}

enum class InvestmentPositionSourceMode {
    MANUAL,
    OCR,
}

/**
 * A user-confirmed valuation snapshot for one investment account.
 *
 * [currentValue] is deliberately separate from optional units and cost. Missing source fields stay
 * null rather than being invented as zero, and no instance claims to be a live provider price.
 */
data class InvestmentPosition(
    val id: InvestmentPositionId,
    val accountId: AccountId,
    val instrumentCode: String?,
    val name: String,
    val currentValue: Money,
    val units: BigDecimal?,
    val costBasis: Money?,
    val asOf: Instant,
    val sourceMode: InvestmentPositionSourceMode,
    val createdAt: Instant,
    val updatedAt: Instant,
    val creationCommandId: CommandId,
) {
    init {
        require(name.isNotBlank()) { "Investment name cannot be blank" }
        require(instrumentCode == null || instrumentCode.isNotBlank()) {
            "Investment code cannot be blank when present"
        }
        require(currentValue.currency == CurrencyCode.CNY) {
            "The first investment slice is CNY only"
        }
        require(currentValue.minorUnits > 0L) { "Investment value must be positive" }
        require(
            units == null ||
                (
                    units.signum() > 0 &&
                        units.precision() <= MAX_UNITS_PRECISION &&
                        units.scale() in 0..MAX_UNITS_SCALE
                ),
        ) { "Investment units must be a bounded positive decimal" }
        require(
            costBasis == null ||
                (costBasis.currency == currentValue.currency && costBasis.minorUnits > 0L),
        ) { "Investment cost must be positive and match the position currency" }
        require(!asOf.isAfter(updatedAt)) { "Investment valuation cannot be in the future" }
        require(!createdAt.isAfter(updatedAt)) { "Investment lifecycle timestamps are invalid" }
    }

    private companion object {
        const val MAX_UNITS_PRECISION = 24
        const val MAX_UNITS_SCALE = 8
    }
}
