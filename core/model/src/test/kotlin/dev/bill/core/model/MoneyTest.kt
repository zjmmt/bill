package dev.bill.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    @Test
    fun `adds amounts with the same currency`() {
        assertEquals(Money.cny(350), Money.cny(200) + Money.cny(150))
    }

    @Test
    fun `rejects addition across currencies`() {
        val usd = Money(100, CurrencyCode("USD"))

        assertThrows(IllegalArgumentException::class.java) {
            Money.cny(100) + usd
        }
    }
}
