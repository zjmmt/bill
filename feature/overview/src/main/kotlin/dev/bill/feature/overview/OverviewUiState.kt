package dev.bill.feature.overview

import dev.bill.application.OverviewSnapshot

sealed interface OverviewUiState {
    data object Loading : OverviewUiState

    data object Empty : OverviewUiState

    data class Content(
        val snapshot: OverviewSnapshot,
        val amountsMasked: Boolean,
    ) : OverviewUiState

    data object Error : OverviewUiState
}

sealed interface OverviewAction {
    data object ToggleAmounts : OverviewAction

    data object ReviewDrafts : OverviewAction

    data object AddAccount : OverviewAction

    data object AddManualDraft : OverviewAction

    data object Retry : OverviewAction
}

object OverviewPresenter {
    fun present(
        snapshot: OverviewSnapshot,
        amountsMasked: Boolean,
    ): OverviewUiState {
        val hasOverviewContent = snapshot.currencyBalances.any { balance ->
            balance.assets.minorUnits != 0L || balance.liabilities.minorUnits != 0L
        } ||
            snapshot.recentTransactions.isNotEmpty() ||
            snapshot.pendingDraftCount > 0 ||
            snapshot.sourceHealth.isNotEmpty()

        return if (hasOverviewContent) {
            OverviewUiState.Content(snapshot, amountsMasked)
        } else {
            OverviewUiState.Empty
        }
    }
}
