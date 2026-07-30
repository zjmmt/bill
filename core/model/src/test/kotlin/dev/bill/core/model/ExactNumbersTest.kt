package dev.bill.core.model

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExactNumbersTest {
    @Test
    fun acceptsLongBoundsAndWholeDecimals() {
        assertEquals(Long.MIN_VALUE, BigInteger.valueOf(Long.MIN_VALUE).toLongExactCompat())
        assertEquals(Long.MAX_VALUE, BigInteger.valueOf(Long.MAX_VALUE).toLongExactCompat())
        assertEquals(123L, BigDecimal("123.000").toLongExactCompat())
    }

    @Test
    fun rejectsOverflowAndFractionalDecimals() {
        assertThrows(ArithmeticException::class.java) {
            BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toLongExactCompat()
        }
        assertThrows(ArithmeticException::class.java) {
            BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE).toLongExactCompat()
        }
        assertThrows(ArithmeticException::class.java) {
            BigDecimal("1.01").toLongExactCompat()
        }
    }
}
