package dev.bill.feature.review

import dev.bill.application.AccountSummary
import dev.bill.application.DraftSummary
import dev.bill.application.DraftSummaryKind
import dev.bill.application.OperationError
import dev.bill.application.canFund
import dev.bill.core.model.CurrencyCode

data class ReviewUiState(
    val draft: DraftSummary,
    val eligibleAccounts: List<AccountSummary>,
    val investmentAccount: AccountSummary?,
    val isSaving: Boolean,
    val operationError: OperationError?,
)

sealed interface ReviewAction {
    data class SelectFundingAccount(val accountId: String) : ReviewAction

    data object Confirm : ReviewAction

    data object SaveForLater : ReviewAction

    data object Ignore : ReviewAction

    data object Dismiss : ReviewAction
}

data class ManualDraftInput(
    val kind: DraftSummaryKind,
    val amount: String,
    val counterparty: String,
    val note: String,
    val currency: CurrencyCode = CurrencyCode.CNY,
    val investmentAccountId: String? = null,
)

object ReviewPresenter {
    fun present(
        draft: DraftSummary,
        accounts: List<AccountSummary>,
        isSaving: Boolean,
        operationError: OperationError?,
    ): ReviewUiState = ReviewUiState(
        draft = draft,
        eligibleAccounts = accounts.filter { account -> account.canFund(draft) },
        investmentAccount = draft.investmentAccountId?.let { investmentAccountId ->
            accounts.firstOrNull { account -> account.id == investmentAccountId }
        },
        isSaving = isSaving,
        operationError = operationError,
    )
}
