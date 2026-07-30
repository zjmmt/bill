package dev.bill.source.genericdelimited

import dev.bill.core.model.CurrencyCode
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.ObservedMoneyDirection
import dev.bill.source.contract.ObservedTime
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DelimitedStatementMapperTest {
    @Test
    fun `maps explicit chinese direction column to a cny candidate`() {
        val mapping = directionColumnMapping()
        val result = DelimitedStatementMapper.map(
            fileHash = fileHash(),
            mapping = mapping,
            row = DelimitedRow(
                tableRowIndex = 1,
                cells = listOf("2026-07-01", "12.34", "支出", "咖啡店", "REF-1"),
            ),
        )

        val candidate = (result as StatementRowMappingResult.Mapped).value.candidate
        assertEquals(1_234L, candidate.amount?.value?.minorUnits)
        assertEquals(CurrencyCode.CNY, candidate.amount?.value?.currency)
        assertEquals(ObservedMoneyDirection.OUTBOUND, candidate.moneyDirection?.value)
        assertEquals(
            ObservedTime.DateOnly(LocalDate.of(2026, 7, 1)),
            candidate.occurredAt?.value,
        )
        assertEquals("咖啡店", candidate.counterparty?.value)
        assertEquals(1, candidate.externalReferences.size)
    }

    @Test
    fun `maps signed usd amount and english date time without conversion`() {
        val mapping = DelimitedStatementMapping(
            delimiter = DelimitedDelimiter.COMMA,
            dateColumnIndex = 0,
            dateFormat = StatementDateFormat.ISO_DATE_TIME_MINUTE,
            amountColumnIndex = 1,
            amountFormat = StatementAmountFormat.COMMA_GROUP_DOT_DECIMAL,
            directionMapping = StatementDirectionMapping.SignedAmount(
                positiveDirection = StatementDirection.INBOUND,
            ),
            counterpartyColumnIndex = 2,
            referenceColumnIndex = null,
            currency = CurrencyCode.USD,
        )

        val result = DelimitedStatementMapper.map(
            fileHash(),
            mapping,
            DelimitedRow(7, listOf("2026-07-01T08:30", "-1,234.50", "Book store")),
        )

        val candidate = (result as StatementRowMappingResult.Mapped).value.candidate
        assertEquals(123_450L, candidate.amount?.value?.minorUnits)
        assertEquals(CurrencyCode.USD, candidate.amount?.value?.currency)
        assertEquals(ObservedMoneyDirection.OUTBOUND, candidate.moneyDirection?.value)
        val observed = candidate.occurredAt?.value as ObservedTime.DateTime
        assertEquals(LocalDateTime.of(2026, 7, 1, 8, 30), observed.localDateTime)
        assertEquals(null, observed.resolvedInstant)
    }

    @Test
    fun `maps japanese direction token case insensitively after trimming`() {
        val mapping = directionColumnMapping(
            inbound = setOf("受取"),
            outbound = setOf("支払い"),
        )

        val result = DelimitedStatementMapper.map(
            fileHash(),
            mapping,
            DelimitedRow(
                2,
                listOf("2026-07-02", "88.00", "  支払い ", "コンビニ", ""),
            ),
        )

        val candidate = (result as StatementRowMappingResult.Mapped).value.candidate
        assertEquals(ObservedMoneyDirection.OUTBOUND, candidate.moneyDirection?.value)
        assertEquals("コンビニ", candidate.counterparty?.value)
    }

    @Test
    fun `rejects signed amount when a separate direction column is authoritative`() {
        val result = DelimitedStatementMapper.map(
            fileHash(),
            directionColumnMapping(),
            DelimitedRow(
                1,
                listOf("2026-07-01", "-12.34", "支出", "咖啡店", ""),
            ),
        )

        assertEquals(
            StatementRowMappingResult.Invalid(
                StatementMappingError.SIGNED_AMOUNT_WITH_DIRECTION_COLUMN,
            ),
            result,
        )
    }

    @Test
    fun `rejects fractional precision and long overflow`() {
        val precision = DelimitedStatementMapper.map(
            fileHash(),
            directionColumnMapping(),
            DelimitedRow(
                1,
                listOf("2026-07-01", "1.001", "支出", "咖啡店", ""),
            ),
        )
        assertEquals(
            StatementRowMappingResult.Invalid(StatementMappingError.INVALID_AMOUNT),
            precision,
        )

        val overflow = DelimitedStatementMapper.map(
            fileHash(),
            directionColumnMapping(),
            DelimitedRow(
                1,
                listOf(
                    "2026-07-01",
                    "999999999999999999999999.99",
                    "支出",
                    "咖啡店",
                    "",
                ),
            ),
        )
        assertEquals(
            StatementRowMappingResult.Invalid(StatementMappingError.AMOUNT_OUT_OF_RANGE),
            overflow,
        )
    }

    @Test
    fun `requires distinct mappings and disjoint normalized direction tokens`() {
        assertThrows(IllegalArgumentException::class.java) {
            directionColumnMapping().copy(counterpartyColumnIndex = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StatementDirectionMapping.DirectionColumn(
                columnIndex = 2,
                inboundTokens = setOf("Credit"),
                outboundTokens = setOf(" credit "),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StatementDirectionMapping.DirectionColumn(
                columnIndex = 2,
                inboundTokens = setOf("Credit", " credit "),
                outboundTokens = setOf("Debit"),
            )
        }
    }

    @Test
    fun `rejects impossible strict calendar date`() {
        val result = DelimitedStatementMapper.map(
            fileHash(),
            directionColumnMapping(),
            DelimitedRow(
                1,
                listOf("2026-02-30", "1.00", "收入", "Test", ""),
            ),
        )

        assertTrue(result is StatementRowMappingResult.Invalid)
        assertEquals(
            StatementMappingError.INVALID_DATE,
            (result as StatementRowMappingResult.Invalid).error,
        )
    }

    private fun directionColumnMapping(
        inbound: Set<String> = setOf("收入"),
        outbound: Set<String> = setOf("支出"),
    ) = DelimitedStatementMapping(
        delimiter = DelimitedDelimiter.COMMA,
        dateColumnIndex = 0,
        dateFormat = StatementDateFormat.DATE_DASH,
        amountColumnIndex = 1,
        amountFormat = StatementAmountFormat.DOT_DECIMAL,
        directionMapping = StatementDirectionMapping.DirectionColumn(
            columnIndex = 2,
            inboundTokens = inbound,
            outboundTokens = outbound,
        ),
        counterpartyColumnIndex = 3,
        referenceColumnIndex = 4,
        currency = CurrencyCode.CNY,
    )

    private fun fileHash() = EvidenceHash.fromBytes(
        "synthetic-file".toByteArray(StandardCharsets.UTF_8),
    )
}
