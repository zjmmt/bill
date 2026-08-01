package dev.bill.application

import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.domain.ObservedChannel
import dev.bill.source.contract.SourceFamily
import java.time.Instant
import java.math.BigDecimal

data class AccountSummary(
    val id: String,
    val name: String,
    val type: AccountType,
    val displayBalance: Money,
    val isLiability: Boolean,
    val latestBalanceSnapshot: BalanceSnapshotSummary? = null,
)

enum class BalanceSnapshotStatus {
    RECONCILED,
    NEEDS_EXPLANATION,
}

data class BalanceSnapshotSummary(
    val observedBalance: Money,
    val ledgerBalance: Money,
    val difference: Money,
    val asOf: Instant,
    val recordedAt: Instant,
    val note: String?,
    val status: BalanceSnapshotStatus,
)

data class InvestmentPositionSummary(
    val id: String,
    val accountId: String,
    val instrumentCode: String?,
    val name: String,
    val currentValue: Money,
    val units: BigDecimal?,
    val costBasis: Money?,
    val asOf: Instant,
    val wasOcrPrefilled: Boolean,
)

enum class DraftSummaryKind {
    EXPENSE,
    INCOME,
    INVEST_BUY,
}

data class DraftSummary(
    val id: String,
    val kind: DraftSummaryKind,
    val amount: Money,
    val counterparty: String,
    val note: String?,
    val fundingAccountId: String?,
    val investmentAccountId: String? = null,
    val occurredAt: Instant,
    val sourceMode: TransactionSourceMode = TransactionSourceMode.MANUAL,
    val observedChannel: ObservedChannel = ObservedChannel.UNKNOWN,
)

enum class SourceReviewKind {
    SHARED_TEXT,
    SELECTED_TEXT_FILE,
    DELIMITED_STATEMENT_ROW,
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
    val allowedDraftKinds: Set<DraftSummaryKind> = setOf(
        DraftSummaryKind.EXPENSE,
        DraftSummaryKind.INCOME,
    ),
    val suggestedCounterparty: String?,
    val diagnosticCode: String?,
    val isPossibleDuplicate: Boolean,
    val suggestedOccurredAt: Instant? = null,
    val suggestedObservedChannel: ObservedChannel = ObservedChannel.UNKNOWN,
    /** A locally bundled safe label for a notification route, never package/channel/body text. */
    val notificationRouteLabel: String? = null,
)

enum class ReconciliationCaseKind {
    TRANSFER,
    REFUND,
    LIABILITY_REPAYMENT,
    FUNDED_BY,
}

data class ReconciliationCaseSummary(
    val id: String,
    val kind: ReconciliationCaseKind,
    val amount: Money,
    val occurredAt: Instant,
    val title: String,
    val draftIds: List<String>,
    val sourceAccountId: String?,
    val destinationAccountId: String,
    val relatedTransactionId: String?,
) {
    init {
        require(id.isNotBlank())
        require(amount.minorUnits > 0L)
        require(title.isNotBlank())
        require(draftIds.isNotEmpty())
        require(draftIds.distinct().size == draftIds.size)
        when (kind) {
            ReconciliationCaseKind.TRANSFER -> {
                require(draftIds.size == 2)
                require(sourceAccountId != null)
                require(relatedTransactionId == null)
            }

            ReconciliationCaseKind.LIABILITY_REPAYMENT -> {
                require(draftIds.size == 1)
                require(sourceAccountId != null)
                require(relatedTransactionId == null)
            }

            ReconciliationCaseKind.REFUND -> {
                require(draftIds.size == 1)
                require(sourceAccountId == null)
                require(relatedTransactionId != null)
            }

            ReconciliationCaseKind.FUNDED_BY -> {
                require(draftIds.size == 2)
                require(sourceAccountId != null)
                require(sourceAccountId == destinationAccountId)
                require(relatedTransactionId == null)
            }
        }
    }
}

data class BillSnapshot(
    val overview: OverviewSnapshot,
    val accounts: List<AccountSummary>,
    val pendingDrafts: List<DraftSummary>,
    val pendingSourceReviews: List<SourceReviewSummary> = emptyList(),
    val reconciliationCases: List<ReconciliationCaseSummary> = emptyList(),
    val investmentPositions: List<InvestmentPositionSummary> = emptyList(),
)

fun AccountSummary.canFund(kind: DraftSummaryKind): Boolean = when (kind) {
    DraftSummaryKind.EXPENSE -> type == AccountType.ASSET_CASH ||
        type == AccountType.ASSET_BANK ||
        type == AccountType.ASSET_EWALLET_BALANCE ||
        type == AccountType.LIABILITY_CC

    DraftSummaryKind.INCOME -> type == AccountType.ASSET_CASH ||
        type == AccountType.ASSET_BANK ||
        type == AccountType.ASSET_EWALLET_BALANCE

    DraftSummaryKind.INVEST_BUY -> type == AccountType.ASSET_CASH ||
        type == AccountType.ASSET_BANK ||
        type == AccountType.ASSET_EWALLET_BALANCE
}

fun AccountSummary.canFund(draft: DraftSummary): Boolean =
    displayBalance.currency == draft.amount.currency && canFund(draft.kind)
