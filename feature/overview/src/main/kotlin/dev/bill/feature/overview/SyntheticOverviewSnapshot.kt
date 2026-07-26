package dev.bill.feature.overview

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.bill.application.OverviewSnapshot
import dev.bill.application.CurrencyBalanceSummary
import dev.bill.application.SourceHealthState
import dev.bill.application.SourceHealthSummary
import dev.bill.application.SourceKind
import dev.bill.application.TransactionSummary
import dev.bill.application.TransactionSummaryKind
import dev.bill.core.model.Money

@Composable
fun syntheticOverviewSnapshot(): OverviewSnapshot = OverviewSnapshot(
    currencyBalances = listOf(
        CurrencyBalanceSummary(
            currency = dev.bill.core.model.CurrencyCode.CNY,
            netWorth = Money.cny(2_468_000),
            assets = Money.cny(3_050_000),
            liabilities = Money.cny(582_000),
        ),
    ),
    pendingDraftCount = 1,
    hardBlockCount = 1,
    possibleDuplicateCount = 0,
    accountCount = 2,
    sourceHealth = listOf(
        SourceHealthSummary(
            id = "alipay-synthetic",
            kind = SourceKind.ALIPAY,
            state = SourceHealthState.HEALTHY,
        ),
        SourceHealthSummary(
            id = "wechat-synthetic",
            kind = SourceKind.WECHAT,
            state = SourceHealthState.NEEDS_ATTENTION,
        ),
        SourceHealthSummary(
            id = "bank-synthetic",
            kind = SourceKind.BANK,
            state = SourceHealthState.FALLBACK_REQUIRED,
        ),
    ),
    recentTransactions = listOf(
        TransactionSummary(
            id = "transaction-synthetic-lunch",
            title = stringResource(R.string.synthetic_lunch),
            supportingText = stringResource(R.string.synthetic_lunch_detail),
            amount = Money.cny(3_200),
            kind = TransactionSummaryKind.EXPENSE,
        ),
        TransactionSummary(
            id = "transaction-synthetic-repayment",
            title = stringResource(R.string.synthetic_repayment),
            supportingText = stringResource(R.string.synthetic_repayment_detail),
            amount = Money.cny(-80_000),
            kind = TransactionSummaryKind.MONEY_MOVEMENT,
        ),
    ),
)
