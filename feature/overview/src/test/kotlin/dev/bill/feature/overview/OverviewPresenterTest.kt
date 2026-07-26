package dev.bill.feature.overview

import dev.bill.application.OverviewSnapshot
import dev.bill.application.CurrencyBalanceSummary
import dev.bill.application.SourceHealthState
import dev.bill.application.SourceHealthSummary
import dev.bill.application.SourceKind
import dev.bill.core.model.Money
import dev.bill.core.model.CurrencyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewPresenterTest {
    @Test
    fun `presents content with the selected privacy state`() {
        val result = OverviewPresenter.present(
            snapshot = snapshot(assets = Money.cny(100)),
            amountsMasked = true,
        )

        assertTrue(result is OverviewUiState.Content)
        assertTrue((result as OverviewUiState.Content).amountsMasked)
    }

    @Test
    fun `presents an empty state when no financial data exists`() {
        val emptySnapshot = snapshot()

        assertEquals(OverviewUiState.Empty, OverviewPresenter.present(emptySnapshot, false))
    }

    @Test
    fun `keeps the review entry visible when only drafts exist`() {
        val result = OverviewPresenter.present(
            snapshot = snapshot(pendingDraftCount = 1),
            amountsMasked = false,
        )

        assertTrue(result is OverviewUiState.Content)
    }

    @Test
    fun `keeps source recovery visible before ledger data exists`() {
        val result = OverviewPresenter.present(
            snapshot = snapshot(
                sourceHealth = listOf(
                    SourceHealthSummary(
                        id = "bank-synthetic",
                        kind = SourceKind.BANK,
                        state = SourceHealthState.FALLBACK_REQUIRED,
                    ),
                ),
            ),
            amountsMasked = false,
        )

        assertTrue(result is OverviewUiState.Content)
    }

    private fun snapshot(
        assets: Money = Money.cny(0),
        pendingDraftCount: Int = 0,
        sourceHealth: List<SourceHealthSummary> = emptyList(),
    ): OverviewSnapshot = OverviewSnapshot(
        currencyBalances = listOf(
            CurrencyBalanceSummary(
                currency = CurrencyCode.CNY,
                netWorth = assets,
                assets = assets,
                liabilities = Money.cny(0),
            ),
        ),
        pendingDraftCount = pendingDraftCount,
        hardBlockCount = 0,
        possibleDuplicateCount = 0,
        sourceHealth = sourceHealth,
        recentTransactions = emptyList(),
    )
}
