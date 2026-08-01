package dev.bill.core.domain

import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceSnapshotDomainTest {
    @Test
    fun `only directly checkable money accounts support balance snapshots`() {
        val supported = AccountType.entries.filter(AccountType::supportsBalanceSnapshots)

        assertEquals(
            listOf(
                AccountType.ASSET_CASH,
                AccountType.ASSET_BANK,
                AccountType.ASSET_EWALLET_BALANCE,
                AccountType.LIABILITY_CC,
            ),
            supported,
        )
    }

    @Test
    fun `credit card converts signed ledger liability to positive user debt`() {
        assertEquals(
            Money(12_345L, CurrencyCode.CNY),
            AccountType.LIABILITY_CC.toUserViewBalance(Money(-12_345L, CurrencyCode.CNY)),
        )
        assertEquals(
            Money(12_345L, CurrencyCode.CNY),
            AccountType.ASSET_BANK.toUserViewBalance(Money(12_345L, CurrencyCode.CNY)),
        )
    }

    @Test
    fun `comparison exposes exact reconciled and needs explanation states`() {
        val snapshot = snapshot(observedMinorUnits = 12_345L)

        val reconciled = BalanceSnapshotComparison(
            snapshot = snapshot,
            ledgerBalance = Money(12_345L, CurrencyCode.CNY),
            difference = Money(0L, CurrencyCode.CNY),
        )
        val needsExplanation = BalanceSnapshotComparison(
            snapshot = snapshot,
            ledgerBalance = Money(10_000L, CurrencyCode.CNY),
            difference = Money(2_345L, CurrencyCode.CNY),
        )

        assertTrue(reconciled.isReconciled)
        assertFalse(needsExplanation.isReconciled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `comparison rejects a difference that does not match the evidence`() {
        BalanceSnapshotComparison(
            snapshot = snapshot(observedMinorUnits = 12_345L),
            ledgerBalance = Money(10_000L, CurrencyCode.CNY),
            difference = Money(2_344L, CurrencyCode.CNY),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `snapshot rejects notes beyond the persisted limit`() {
        snapshot(observedMinorUnits = 12_345L).copy(
            note = "x".repeat(MAX_BALANCE_SNAPSHOT_NOTE_LENGTH + 1),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `snapshot rejects control characters in notes`() {
        snapshot(observedMinorUnits = 12_345L).copy(note = "line one\nline two")
    }

    private fun snapshot(observedMinorUnits: Long): BalanceSnapshot = BalanceSnapshot(
        id = BalanceSnapshotId("balance-snapshot:test"),
        accountId = AccountId("account:test"),
        observedBalance = Money(observedMinorUnits, CurrencyCode.CNY),
        asOf = Instant.parse("2026-07-19T07:55:00Z"),
        recordedAt = Instant.parse("2026-07-19T08:00:00Z"),
        note = "manual check",
        sourceMode = BalanceSnapshotSourceMode.MANUAL,
        creationCommandId = CommandId("snapshot-command"),
    )
}
