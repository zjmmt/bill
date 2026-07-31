package dev.bill.core.model

import java.time.Instant

@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "Account id cannot be blank" }
    }
}

@JvmInline
value class TransactionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Transaction id cannot be blank" }
    }
}

enum class AccountType {
    ASSET_CASH,
    ASSET_BANK,
    ASSET_EWALLET_BALANCE,
    ASSET_WRAPPER,
    LIABILITY_CC,
    LIABILITY_LOAN,
    INVESTMENT_CASH,
    INVESTMENT_SECURITY,
    EXPENSE_CATEGORY,
    INCOME_CATEGORY,
    EQUITY_ADJUSTMENT,
}

/**
 * User-created wallet and cash accounts are domestic-CNY only. USD is deliberately limited to
 * bank and credit-card accounts; this is enforced again by application and storage boundaries.
 */
fun AccountType.allowsUserAccountCurrency(currency: CurrencyCode): Boolean = when (this) {
    AccountType.ASSET_BANK,
    AccountType.LIABILITY_CC,
    -> currency == CurrencyCode.CNY || currency == CurrencyCode.USD

    AccountType.ASSET_CASH,
    AccountType.ASSET_EWALLET_BALANCE,
    AccountType.INVESTMENT_SECURITY,
    -> currency == CurrencyCode.CNY

    AccountType.ASSET_WRAPPER,
    AccountType.LIABILITY_LOAN,
    AccountType.INVESTMENT_CASH,
    AccountType.EXPENSE_CATEGORY,
    AccountType.INCOME_CATEGORY,
    AccountType.EQUITY_ADJUSTMENT,
    -> false
}

enum class TransactionType {
    EXPENSE,
    INCOME,
    TRANSFER,
    TOPUP,
    REFUND,
    LIABILITY_DRAW,
    LIABILITY_REPAY,
    INVEST_BUY,
    INVEST_SELL,
    FEE,
    ADJUSTMENT,
}

enum class EntryRole {
    FUNDING,
    EXPENSE,
    INCOME,
    ASSET,
    LIABILITY,
    INVESTMENT,
    EQUITY,
    FEE,
}

data class LedgerEntry(
    val accountId: AccountId,
    val amount: Money,
    val role: EntryRole,
)

data class LedgerPostingCandidate(
    val id: TransactionId,
    val type: TransactionType,
    val occurredAt: Instant,
    val entries: List<LedgerEntry>,
)
