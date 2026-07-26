package dev.bill.application

import dev.bill.core.model.Money
import dev.bill.core.model.CurrencyCode

enum class SourceHealthState {
    HEALTHY,
    NEEDS_ATTENTION,
    FALLBACK_REQUIRED,
}

enum class SourceKind {
    ALIPAY,
    WECHAT,
    BANK,
}

enum class TransactionSummaryKind {
    EXPENSE,
    INCOME,
    MONEY_MOVEMENT,
}

data class SourceHealthSummary(
    val id: String,
    val kind: SourceKind,
    val state: SourceHealthState,
)

data class TransactionSummary(
    val id: String,
    val title: String,
    val supportingText: String,
    val amount: Money,
    val kind: TransactionSummaryKind,
    val canUndo: Boolean = false,
)

fun TransactionSummary.signedDisplayAmount(): Money = when (kind) {
    TransactionSummaryKind.EXPENSE -> amount.copy(
        minorUnits = Math.negateExact(amount.minorUnits),
    )

    TransactionSummaryKind.INCOME,
    TransactionSummaryKind.MONEY_MOVEMENT,
    -> amount
}

/**
 * Totals are intentionally kept per currency. A sum across these values would imply an exchange
 * rate that this app neither stores nor verifies.
 */
data class CurrencyBalanceSummary(
    val currency: CurrencyCode,
    val netWorth: Money,
    val assets: Money,
    val liabilities: Money,
) {
    init {
        require(netWorth.currency == currency) { "Net worth currency must match its group" }
        require(assets.currency == currency) { "Asset currency must match its group" }
        require(liabilities.currency == currency) { "Liability currency must match its group" }
    }
}

data class OverviewSnapshot(
    val currencyBalances: List<CurrencyBalanceSummary>,
    val pendingDraftCount: Int,
    val hardBlockCount: Int,
    val possibleDuplicateCount: Int,
    val accountCount: Int = 0,
    val sourceHealth: List<SourceHealthSummary>,
    val recentTransactions: List<TransactionSummary>,
)
