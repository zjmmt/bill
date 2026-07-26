package dev.bill.application

import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.source.contract.SourceFamily
import java.time.Instant

data class AccountSummary(
    val id: String,
    val name: String,
    val type: AccountType,
    val displayBalance: Money,
    val isLiability: Boolean,
)

enum class DraftSummaryKind {
    EXPENSE,
    INCOME,
}

data class DraftSummary(
    val id: String,
    val kind: DraftSummaryKind,
    val amount: Money,
    val counterparty: String,
    val note: String?,
    val fundingAccountId: String?,
    val occurredAt: Instant,
    val sourceMode: TransactionSourceMode = TransactionSourceMode.MANUAL,
)

enum class SourceReviewKind {
    SHARED_TEXT,
    SELECTED_TEXT_FILE,
    SHARED_RECEIPT_IMAGE,
    PHOTO_OCR,
    NOTIFICATION,
}

data class SourceReviewSummary(
    val id: String,
    val kind: SourceReviewKind,
    val sourceFamily: SourceFamily,
    val capturedAt: Instant,
    val suggestedAmount: Money?,
    val allowedDraftCurrencies: Set<CurrencyCode>,
    val suggestedKind: DraftSummaryKind?,
    val suggestedCounterparty: String?,
    val diagnosticCode: String?,
    val isPossibleDuplicate: Boolean,
)

data class BillSnapshot(
    val overview: OverviewSnapshot,
    val accounts: List<AccountSummary>,
    val pendingDrafts: List<DraftSummary>,
    val pendingSourceReviews: List<SourceReviewSummary> = emptyList(),
)

fun AccountSummary.canFund(kind: DraftSummaryKind): Boolean = when (kind) {
    DraftSummaryKind.EXPENSE -> type == AccountType.ASSET_CASH ||
        type == AccountType.ASSET_BANK ||
        type == AccountType.ASSET_EWALLET_BALANCE ||
        type == AccountType.LIABILITY_CC

    DraftSummaryKind.INCOME -> type == AccountType.ASSET_CASH ||
        type == AccountType.ASSET_BANK ||
        type == AccountType.ASSET_EWALLET_BALANCE
}

fun AccountSummary.canFund(draft: DraftSummary): Boolean =
    displayBalance.currency == draft.amount.currency && canFund(draft.kind)
