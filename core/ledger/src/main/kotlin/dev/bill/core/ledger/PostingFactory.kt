package dev.bill.core.ledger

import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.SystemAccountIds
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.EntryRole
import dev.bill.core.model.LedgerEntry
import dev.bill.core.model.LedgerPostingCandidate
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import dev.bill.core.model.allowsUserAccountCurrency
import dev.bill.core.model.isSupportedLedgerCurrency
import java.time.Instant

sealed interface PostingBuildResult {
    data class Valid(val transaction: ValidatedLedgerTransaction) : PostingBuildResult

    data class InvalidAccountType(val type: AccountType) : PostingBuildResult

    data class CurrencyMismatch(
        val expected: CurrencyCode,
        val actual: CurrencyCode,
    ) : PostingBuildResult

    data class UnsupportedCurrency(val currency: CurrencyCode) : PostingBuildResult

    data object InvalidAmount : PostingBuildResult

    data class ValidationFailed(val validation: LedgerValidation) : PostingBuildResult
}

object PostingFactory {
    fun openingBalance(
        account: LedgerAccount,
        visibleBalance: Money,
        transactionId: TransactionId,
        occurredAt: Instant,
    ): PostingBuildResult {
        if (visibleBalance.minorUnits <= 0L) return PostingBuildResult.InvalidAmount
        if (!visibleBalance.currency.isSupportedLedgerCurrency()) {
            return PostingBuildResult.UnsupportedCurrency(visibleBalance.currency)
        }
        if (account.currency != visibleBalance.currency) {
            return PostingBuildResult.CurrencyMismatch(account.currency, visibleBalance.currency)
        }
        if (!account.type.allowsUserAccountCurrency(account.currency)) {
            return PostingBuildResult.InvalidAccountType(account.type)
        }

        val (accountAmount, accountRole) = when (account.type) {
            AccountType.ASSET_CASH,
            AccountType.ASSET_BANK,
            AccountType.ASSET_EWALLET_BALANCE,
            -> visibleBalance.minorUnits to EntryRole.ASSET

            AccountType.LIABILITY_CC -> -visibleBalance.minorUnits to EntryRole.LIABILITY

            else -> return PostingBuildResult.InvalidAccountType(account.type)
        }

        return validate(
            LedgerPostingCandidate(
                id = transactionId,
                type = TransactionType.ADJUSTMENT,
                occurredAt = occurredAt,
                entries = listOf(
                    LedgerEntry(
                        accountId = account.id,
                        amount = Money(accountAmount, visibleBalance.currency),
                        role = accountRole,
                    ),
                    LedgerEntry(
                        accountId = SystemAccountIds.openingEquity(visibleBalance.currency),
                        amount = Money(-accountAmount, visibleBalance.currency),
                        role = EntryRole.EQUITY,
                    ),
                ),
            ),
        )
    }

    fun manualDraft(
        draft: ManualDraft,
        fundingAccount: LedgerAccount,
        transactionId: TransactionId,
        confirmedAt: Instant,
    ): PostingBuildResult {
        if (!draft.amount.currency.isSupportedLedgerCurrency()) {
            return PostingBuildResult.UnsupportedCurrency(draft.amount.currency)
        }
        if (fundingAccount.currency != draft.amount.currency) {
            return PostingBuildResult.CurrencyMismatch(fundingAccount.currency, draft.amount.currency)
        }
        if (fundingAccount.isSystem || fundingAccount.isArchived) {
            return PostingBuildResult.InvalidAccountType(fundingAccount.type)
        }
        if (!fundingAccount.type.allowsUserAccountCurrency(fundingAccount.currency)) {
            return PostingBuildResult.InvalidAccountType(fundingAccount.type)
        }

        val entries = when (draft.type) {
            TransactionType.EXPENSE -> expenseEntries(draft, fundingAccount)
            TransactionType.INCOME -> incomeEntries(draft, fundingAccount)
            else -> null
        } ?: return PostingBuildResult.InvalidAccountType(fundingAccount.type)

        return validate(
            LedgerPostingCandidate(
                id = transactionId,
                type = draft.type,
                occurredAt = draft.occurredAt.coerceAtMost(confirmedAt),
                entries = entries,
            ),
        )
    }

    private fun expenseEntries(
        draft: ManualDraft,
        fundingAccount: LedgerAccount,
    ): List<LedgerEntry>? {
        val fundingRole = when (fundingAccount.type) {
            AccountType.ASSET_CASH,
            AccountType.ASSET_BANK,
            AccountType.ASSET_EWALLET_BALANCE,
            -> EntryRole.FUNDING

            AccountType.LIABILITY_CC -> EntryRole.LIABILITY
            else -> return null
        }
        return listOf(
            LedgerEntry(
                accountId = SystemAccountIds.uncategorizedExpense(draft.amount.currency),
                amount = draft.amount,
                role = EntryRole.EXPENSE,
            ),
            LedgerEntry(
                accountId = fundingAccount.id,
                amount = draft.amount.copy(minorUnits = -draft.amount.minorUnits),
                role = fundingRole,
            ),
        )
    }

    private fun incomeEntries(
        draft: ManualDraft,
        fundingAccount: LedgerAccount,
    ): List<LedgerEntry>? {
        if (
            fundingAccount.type != AccountType.ASSET_CASH &&
            fundingAccount.type != AccountType.ASSET_BANK &&
            fundingAccount.type != AccountType.ASSET_EWALLET_BALANCE
        ) {
            return null
        }
        return listOf(
            LedgerEntry(
                accountId = fundingAccount.id,
                amount = draft.amount,
                role = EntryRole.FUNDING,
            ),
            LedgerEntry(
                accountId = SystemAccountIds.uncategorizedIncome(draft.amount.currency),
                amount = draft.amount.copy(minorUnits = -draft.amount.minorUnits),
                role = EntryRole.INCOME,
            ),
        )
    }

    private fun validate(candidate: LedgerPostingCandidate): PostingBuildResult =
        when (val validation = LedgerValidator.validate(candidate)) {
            is LedgerValidation.Valid -> PostingBuildResult.Valid(validation.transaction)
            else -> PostingBuildResult.ValidationFailed(validation)
        }
}
