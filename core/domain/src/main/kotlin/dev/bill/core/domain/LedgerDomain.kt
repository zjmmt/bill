package dev.bill.core.domain

import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.LedgerEntry
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import java.time.Instant

@JvmInline
value class DraftId(val value: String) {
    init {
        require(value.isNotBlank()) { "Draft id cannot be blank" }
    }
}

@JvmInline
value class CommandId(val value: String) {
    init {
        require(value.isNotBlank()) { "Command id cannot be blank" }
    }
}

@JvmInline
value class AuditEventId(val value: String) {
    init {
        require(value.isNotBlank()) { "Audit event id cannot be blank" }
    }
}

enum class DraftState {
    WAITING_USER,
    EDITED,
    CONFIRMED,
    DISMISSED,
}

enum class TransactionStatus {
    ACTIVE,
    VOIDED,
}

enum class TransactionSourceMode {
    MANUAL,
    EXTERNAL,
}

enum class AuditAction {
    ACCOUNT_CREATED,
    OPENING_BALANCE_POSTED,
    MANUAL_DRAFT_CREATED,
    EXTERNAL_DRAFT_CREATED,
    SOURCE_PROPOSAL_DISMISSED,
    SOURCE_EVIDENCE_RETENTION_CHANGED,
    SOURCE_EVIDENCE_CLEAR_REQUESTED,
    SOURCE_EVIDENCE_CLEARED,
    FUNDING_ACCOUNT_SELECTED,
    DRAFT_CONFIRMED,
    DRAFT_DISMISSED,
    TRANSACTION_VOIDED,
}

data class LedgerAccount(
    val id: AccountId,
    val name: String,
    val normalizedName: String,
    val type: AccountType,
    val currency: CurrencyCode,
    val isSystem: Boolean,
    val isArchived: Boolean,
    val createdAt: Instant,
    val creationCommandId: CommandId,
) {
    init {
        require(name.isNotBlank()) { "Account name cannot be blank" }
        require(normalizedName.isNotBlank()) { "Normalized account name cannot be blank" }
    }
}

data class AccountBalance(
    val account: LedgerAccount,
    val balance: Money,
) {
    init {
        require(account.currency == balance.currency) {
            "Account balance currency must match its account"
        }
    }
}

data class ReviewDraft(
    val id: DraftId,
    val state: DraftState,
    val type: TransactionType,
    val amount: Money,
    val occurredAt: Instant,
    val counterparty: String,
    val note: String?,
    val fundingAccountId: AccountId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val creationCommandId: CommandId,
    val sourceMode: TransactionSourceMode = TransactionSourceMode.MANUAL,
) {
    init {
        require(type == TransactionType.EXPENSE || type == TransactionType.INCOME) {
            "The first review slice only accepts expense or income drafts"
        }
        require(amount.minorUnits > 0L) { "Draft amount must be positive" }
        require(counterparty.isNotBlank()) { "Draft counterparty cannot be blank" }
    }
}

/**
 * Compatibility name for the manual-entry UI. Domain and storage code should use [ReviewDraft]
 * because externally evidenced drafts enter the same review state machine.
 */
typealias ManualDraft = ReviewDraft

data class PostedTransaction(
    val id: TransactionId,
    val draftId: DraftId?,
    val type: TransactionType,
    val status: TransactionStatus,
    val sourceMode: TransactionSourceMode,
    val occurredAt: Instant,
    val confirmedAt: Instant,
    val title: String,
    val note: String?,
    val commandId: CommandId,
    val entries: List<LedgerEntry>,
) {
    init {
        require(title.isNotBlank()) { "Transaction title cannot be blank" }
        require(entries.isNotEmpty()) { "Posted transaction must contain entries" }
    }
}

data class AuditRecord(
    val id: AuditEventId,
    val commandId: CommandId,
    val action: AuditAction,
    val entityType: String,
    val entityId: String,
    val occurredAt: Instant,
) {
    init {
        require(entityType.isNotBlank()) { "Audit entity type cannot be blank" }
        require(entityId.isNotBlank()) { "Audit entity id cannot be blank" }
    }
}

data class LedgerState(
    val accountBalances: List<AccountBalance>,
    val pendingDrafts: List<ReviewDraft>,
    val recentTransactions: List<PostedTransaction>,
)

object SystemAccountIds {
    val UncategorizedExpense = AccountId("system:expense:uncategorized")
    val UncategorizedIncome = AccountId("system:income:uncategorized")
    val OpeningEquity = AccountId("system:equity:opening")

    fun uncategorizedExpense(currency: CurrencyCode): AccountId =
        currencyScoped(UncategorizedExpense, "system:expense:uncategorized", currency)

    fun uncategorizedIncome(currency: CurrencyCode): AccountId =
        currencyScoped(UncategorizedIncome, "system:income:uncategorized", currency)

    fun openingEquity(currency: CurrencyCode): AccountId =
        currencyScoped(OpeningEquity, "system:equity:opening", currency)

    fun allFor(currency: CurrencyCode): Set<AccountId> = setOf(
        uncategorizedExpense(currency),
        uncategorizedIncome(currency),
        openingEquity(currency),
    )

    fun isSystemAccount(id: AccountId): Boolean =
        id in allFor(CurrencyCode.CNY) || id in allFor(CurrencyCode.USD)

    private fun currencyScoped(
        cnyId: AccountId,
        baseId: String,
        currency: CurrencyCode,
    ): AccountId = when (currency) {
        CurrencyCode.CNY -> cnyId
        CurrencyCode.USD -> AccountId("$baseId:usd")
        else -> throw IllegalArgumentException("Unsupported system-account currency: ${currency.value}")
    }
}

fun AccountType.isUserSelectableFundingAccount(): Boolean = when (this) {
    AccountType.ASSET_CASH,
    AccountType.ASSET_BANK,
    AccountType.ASSET_EWALLET_BALANCE,
    AccountType.LIABILITY_CC,
    -> true

    AccountType.ASSET_WRAPPER,
    AccountType.LIABILITY_LOAN,
    AccountType.INVESTMENT_CASH,
    AccountType.INVESTMENT_SECURITY,
    AccountType.EXPENSE_CATEGORY,
    AccountType.INCOME_CATEGORY,
    AccountType.EQUITY_ADJUSTMENT,
    -> false
}
