package dev.bill.core.model

@JvmInline
value class CurrencyCode(val value: String) {
    init {
        require(value.matches(Regex("[A-Z]{3}"))) {
            "Currency code must contain three uppercase ASCII letters"
        }
    }

    companion object {
        val CNY = CurrencyCode("CNY")
        val USD = CurrencyCode("USD")
    }
}

/**
 * The first multi-currency slice deliberately supports only currencies that can be represented
 * without a conversion rate. Keep this narrow: adding a code here is an accounting decision,
 * not a display-only change.
 */
fun CurrencyCode.isSupportedLedgerCurrency(): Boolean =
    this == CurrencyCode.CNY || this == CurrencyCode.USD

data class Money(
    val minorUnits: Long,
    val currency: CurrencyCode,
) {
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Cannot add money in different currencies" }
        return copy(minorUnits = Math.addExact(minorUnits, other.minorUnits))
    }

    companion object {
        fun cny(minorUnits: Long): Money = Money(minorUnits, CurrencyCode.CNY)
    }
}
