package dev.bill.core.ledger

import dev.bill.core.model.AccountId
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.EntryRole
import dev.bill.core.model.LedgerEntry
import dev.bill.core.model.LedgerPostingCandidate
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerValidatorTest {
    @Test
    fun `accepts a balanced expense`() {
        val transaction = transaction(
            LedgerEntry(AccountId("expense-food"), Money.cny(3_200), EntryRole.EXPENSE),
            LedgerEntry(AccountId("asset-bank"), Money.cny(-3_200), EntryRole.FUNDING),
        )

        assertTrue(LedgerValidator.validate(transaction) is LedgerValidation.Valid)
    }

    @Test
    fun `rejects an unbalanced transaction`() {
        val transaction = transaction(
            LedgerEntry(AccountId("expense-food"), Money.cny(3_200), EntryRole.EXPENSE),
            LedgerEntry(AccountId("asset-bank"), Money.cny(-3_100), EntryRole.FUNDING),
        )

        assertTrue(LedgerValidator.validate(transaction) is LedgerValidation.Unbalanced)
    }

    @Test
    fun `requires at least two meaningful entries`() {
        val transaction = transaction(
            LedgerEntry(AccountId("asset-bank"), Money.cny(100), EntryRole.ASSET),
        )

        assertEquals(LedgerValidation.TooFewEntries(1), LedgerValidator.validate(transaction))
    }

    @Test
    fun `rejects zero amount entries`() {
        val transaction = transaction(
            LedgerEntry(AccountId("expense-food"), Money.cny(0), EntryRole.EXPENSE),
            LedgerEntry(AccountId("asset-bank"), Money.cny(0), EntryRole.FUNDING),
        )

        assertEquals(
            LedgerValidation.ZeroAmountEntries(listOf(0, 1)),
            LedgerValidator.validate(transaction),
        )
    }

    @Test
    fun `rejects separately balanced currencies in one transaction`() {
        val transaction = transaction(
            LedgerEntry(AccountId("expense-cny"), Money.cny(3_200), EntryRole.EXPENSE),
            LedgerEntry(AccountId("bank-cny"), Money.cny(-3_200), EntryRole.FUNDING),
            LedgerEntry(
                AccountId("expense-usd"),
                Money(3_200, CurrencyCode.USD),
                EntryRole.EXPENSE,
            ),
            LedgerEntry(
                AccountId("bank-usd"),
                Money(-3_200, CurrencyCode.USD),
                EntryRole.FUNDING,
            ),
        )

        assertEquals(
            LedgerValidation.MixedCurrencies(setOf(CurrencyCode.CNY, CurrencyCode.USD)),
            LedgerValidator.validate(transaction),
        )
    }

    @Test
    fun `rejects a balanced transfer mislabeled as an expense`() {
        val transaction = transaction(
            LedgerEntry(AccountId("asset-bank-a"), Money.cny(-10_000), EntryRole.FUNDING),
            LedgerEntry(AccountId("asset-bank-b"), Money.cny(10_000), EntryRole.FUNDING),
        )

        assertTrue(LedgerValidator.validate(transaction) is LedgerValidation.InvalidEntryRoles)
    }

    @Test
    fun `accepts repayment roles without classifying them as an expense`() {
        val transaction = transaction(
            LedgerEntry(AccountId("asset-bank"), Money.cny(-80_000), EntryRole.FUNDING),
            LedgerEntry(AccountId("liability-card"), Money.cny(80_000), EntryRole.LIABILITY),
            type = TransactionType.LIABILITY_REPAY,
        )

        assertTrue(LedgerValidator.validate(transaction) is LedgerValidation.Valid)
    }

    @Test
    fun `rejects expense directions mislabeled as a refund`() {
        val transaction = transaction(
            LedgerEntry(AccountId("expense-food"), Money.cny(3_200), EntryRole.EXPENSE),
            LedgerEntry(AccountId("asset-bank"), Money.cny(-3_200), EntryRole.FUNDING),
            type = TransactionType.REFUND,
        )

        assertTrue(LedgerValidator.validate(transaction) is LedgerValidation.InvalidEntryRoles)
    }

    @Test
    fun `accepts a refund with reversed expense directions`() {
        val transaction = transaction(
            LedgerEntry(AccountId("expense-food"), Money.cny(-3_200), EntryRole.EXPENSE),
            LedgerEntry(AccountId("asset-bank"), Money.cny(3_200), EntryRole.FUNDING),
            type = TransactionType.REFUND,
        )

        assertTrue(LedgerValidator.validate(transaction) is LedgerValidation.Valid)
    }

    @Test
    fun `rejects an investment purchase mislabeled as a sale`() {
        val transaction = transaction(
            LedgerEntry(AccountId("investment-fund"), Money.cny(50_000), EntryRole.INVESTMENT),
            LedgerEntry(AccountId("asset-bank"), Money.cny(-50_000), EntryRole.FUNDING),
            type = TransactionType.INVEST_SELL,
        )

        assertTrue(LedgerValidator.validate(transaction) is LedgerValidation.InvalidEntryRoles)
    }

    @Test
    fun `accepts an investment sale with reversed purchase directions`() {
        val transaction = transaction(
            LedgerEntry(AccountId("investment-fund"), Money.cny(-50_000), EntryRole.INVESTMENT),
            LedgerEntry(AccountId("asset-bank"), Money.cny(50_000), EntryRole.FUNDING),
            type = TransactionType.INVEST_SELL,
        )

        assertTrue(LedgerValidator.validate(transaction) is LedgerValidation.Valid)
    }

    private fun transaction(
        vararg entries: LedgerEntry,
        type: TransactionType = TransactionType.EXPENSE,
    ): LedgerPostingCandidate = LedgerPostingCandidate(
        id = TransactionId("synthetic-transaction"),
        type = type,
        occurredAt = Instant.parse("2026-07-19T08:00:00Z"),
        entries = entries.toList(),
    )
}
