package dev.bill.core.model

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Android-compatible exact conversion for code that must still run below API 31.
 *
 * The platform's `longValueExact()` methods are unavailable on older supported devices even
 * though the equivalent range and fractional checks can be expressed with API 26 primitives.
 */
fun BigInteger.toLongExactCompat(): Long {
    if (this < LONG_MIN_VALUE || this > LONG_MAX_VALUE) {
        throw ArithmeticException("Value does not fit in Long")
    }
    return toLong()
}

fun BigDecimal.toLongExactCompat(): Long =
    setScale(0, RoundingMode.UNNECESSARY)
        .unscaledValue()
        .toLongExactCompat()

private val LONG_MIN_VALUE = BigInteger.valueOf(Long.MIN_VALUE)
private val LONG_MAX_VALUE = BigInteger.valueOf(Long.MAX_VALUE)
