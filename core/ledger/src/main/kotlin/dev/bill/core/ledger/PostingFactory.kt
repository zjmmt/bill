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

    data object InvalidDraftPair : PostingBuildResult

    data object SameAccount : PostingBuildResult

    data object InvalidRelatedTransaction : PostingBuildResult

    data object AmountExceedsRemaining : PostingBuildResult

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

            AccountType.INVESTMENT_SECURITY ->
                visibleBalance.minorUnits to EntryRole.INVESTMENT

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
        investmentAccount: LedgerAccount? = null,
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
            TransactionType.EXPENSE -> if (investmentAccount == null) {
                expenseEntries(draft, fundingAccount)
            } else {
                null
            }

            TransactionType.INCOME -> if (investmentAccount == null) {
                incomeEntries(draft, fundingAccount)
            } else {
                null
            }

            TransactionType.INVEST_BUY -> investmentAccount
                ?.let { investmentBuyEntries(draft, fundingAccount, it) }

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

    fun transferPair(
        outboundDraft: ManualDraft,
        inboundDraft: ManualDraft,
        sourceAccount: LedgerAccount,
        destinationAccount: LedgerAccount,
        transactionId: TransactionId,
        confirmedAt: Instant,
    ): PostingBuildResult {
        if (
            outboundDraft.type != TransactionType.EXPENSE ||
            inboundDraft.type != TransactionType.INCOME ||
            outboundDraft.amount != inboundDraft.amount ||
            outboundDraft.fundingAccountId != sourceAccount.id ||
            inboundDraft.fundingAccountId != destinationAccount.id
        ) {
            return PostingBuildResult.InvalidDraftPair
        }
        validateMovementAccounts(
            first = sourceAccount,
            second = destinationAccount,
            currency = outboundDraft.amount.currency,
            allowedFirstTypes = ASSET_MOVEMENT_ACCOUNT_TYPES,
            allowedSecondTypes = ASSET_MOVEMENT_ACCOUNT_TYPES,
        )?.let { return it }

        val amount = outboundDraft.amount
        return validate(
            LedgerPostingCandidate(
                id = transactionId,
                type = TransactionType.TRANSFER,
                occurredAt = minOf(outboundDraft.occurredAt, inboundDraft.occurredAt)
                    .coerceAtMost(confirmedAt),
                entries = listOf(
                    LedgerEntry(
                        accountId = sourceAccount.id,
                        amount = amount.copy(minorUnits = -amount.minorUnits),
                        role = EntryRole.FUNDING,
                    ),
                    LedgerEntry(
                        accountId = destinationAccount.id,
                        amount = amount,
                        role = EntryRole.ASSET,
                    ),
                ),
            ),
        )
    }

    fun liabilityRepayment(
        outboundDraft: ManualDraft,
        sourceAccount: LedgerAccount,
        liabilityAccount: LedgerAccount,
        transactionId: TransactionId,
        confirmedAt: Instant,
    ): PostingBuildResult {
        if (
            outboundDraft.type != TransactionType.EXPENSE ||
            outboundDraft.fundingAccountId != sourceAccount.id
        ) {
            return PostingBuildResult.InvalidDraftPair
        }
        validateMovementAccounts(
            first = sourceAccount,
            second = liabilityAccount,
            currency = outboundDraft.amount.currency,
            allowedFirstTypes = setOf(AccountType.ASSET_BANK),
            allowedSecondTypes = setOf(AccountType.LIABILITY_CC),
        )?.let { return it }

        val amount = outboundDraft.amount
        return validate(
            LedgerPostingCandidate(
                id = transactionId,
                type = TransactionType.LIABILITY_REPAY,
                occurredAt = outboundDraft.occurredAt.coerceAtMost(confirmedAt),
                entries = listOf(
                    LedgerEntry(
                        accountId = sourceAccount.id,
                        amount = amount.copy(minorUnits = -amount.minorUnits),
                        role = EntryRole.FUNDING,
                    ),
                    LedgerEntry(
                        accountId = liabilityAccount.id,
                        amount = amount,
                        role = EntryRole.LIABILITY,
                    ),
                ),
            ),
        )
    }

    fun refund(
        inboundDraft: ManualDraft,
        destinationAccount: LedgerAccount,
        originalExpense: dev.bill.core.domain.PostedTransaction,
        alreadyRefundedMinorUnits: Long,
        transactionId: TransactionId,
        confirmedAt: Instant,
    ): PostingBuildResult {
        if (
            inboundDraft.type != TransactionType.INCOME ||
            alreadyRefundedMinorUnits < 0L ||
            (
                inboundDraft.fundingAccountId != null &&
                    inboundDraft.fundingAccountId != destinationAccount.id
                )
        ) {
            return PostingBuildResult.InvalidDraftPair
        }
        if (
            originalExpense.status != dev.bill.core.domain.TransactionStatus.ACTIVE ||
            originalExpense.type != TransactionType.EXPENSE ||
            originalExpense.occurredAt.isAfter(inboundDraft.occurredAt)
        ) {
            return PostingBuildResult.InvalidRelatedTransaction
        }
        if (
            destinationAccount.isSystem ||
            destinationAccount.isArchived ||
            destinationAccount.currency != inboundDraft.amount.currency ||
            destinationAccount.type !in REFUND_DESTINATION_ACCOUNT_TYPES
        ) {
            return PostingBuildResult.InvalidAccountType(destinationAccount.type)
        }

        val expenseEntries = originalExpense.entries.filter {
            it.role == EntryRole.EXPENSE && it.amount.minorUnits > 0L
        }
        val expenseEntry = expenseEntries.singleOrNull()
            ?: return PostingBuildResult.InvalidRelatedTransaction
        if (expenseEntry.amount.currency != inboundDraft.amount.currency) {
            return PostingBuildResult.CurrencyMismatch(
                expenseEntry.amount.currency,
                inboundDraft.amount.currency,
            )
        }
        val originalCounterpart = originalExpense.entries.singleOrNull {
            it.accountId == destinationAccount.id &&
                it.amount.currency == inboundDraft.amount.currency &&
                it.amount.minorUnits < 0L &&
                it.role in setOf(EntryRole.FUNDING, EntryRole.LIABILITY)
        } ?: return PostingBuildResult.InvalidRelatedTransaction

        val remaining = try {
            Math.subtractExact(expenseEntry.amount.minorUnits, alreadyRefundedMinorUnits)
        } catch (_: ArithmeticException) {
            return PostingBuildResult.AmountExceedsRemaining
        }
        if (inboundDraft.amount.minorUnits > remaining) {
            return PostingBuildResult.AmountExceedsRemaining
        }

        return validate(
            LedgerPostingCandidate(
                id = transactionId,
                type = TransactionType.REFUND,
                occurredAt = inboundDraft.occurredAt.coerceAtMost(confirmedAt),
                entries = listOf(
                    LedgerEntry(
                        accountId = destinationAccount.id,
                        amount = inboundDraft.amount,
                        role = originalCounterpart.role,
                    ),
                    LedgerEntry(
                        accountId = expenseEntry.accountId,
                        amount = inboundDraft.amount.copy(
                            minorUnits = -inboundDraft.amount.minorUnits,
                        ),
                        role = EntryRole.EXPENSE,
                    ),
                ),
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

    private fun investmentBuyEntries(
        draft: ManualDraft,
        fundingAccount: LedgerAccount,
        investmentAccount: LedgerAccount,
    ): List<LedgerEntry>? {
        if (
            draft.fundingAccountId != fundingAccount.id ||
            draft.investmentAccountId != investmentAccount.id ||
            fundingAccount.id == investmentAccount.id ||
            fundingAccount.type !in ASSET_MOVEMENT_ACCOUNT_TYPES ||
            investmentAccount.type != AccountType.INVESTMENT_SECURITY ||
            investmentAccount.isSystem ||
            investmentAccount.isArchived ||
            investmentAccount.currency != draft.amount.currency
        ) {
            return null
        }
        return listOf(
            LedgerEntry(
                accountId = fundingAccount.id,
                amount = draft.amount.copy(minorUnits = -draft.amount.minorUnits),
                role = EntryRole.FUNDING,
            ),
            LedgerEntry(
                accountId = investmentAccount.id,
                amount = draft.amount,
                role = EntryRole.INVESTMENT,
            ),
        )
    }

    private fun validate(candidate: LedgerPostingCandidate): PostingBuildResult =
        when (val validation = LedgerValidator.validate(candidate)) {
            is LedgerValidation.Valid -> PostingBuildResult.Valid(validation.transaction)
            else -> PostingBuildResult.ValidationFailed(validation)
        }

    private fun validateMovementAccounts(
        first: LedgerAccount,
        second: LedgerAccount,
        currency: CurrencyCode,
        allowedFirstTypes: Set<AccountType>,
        allowedSecondTypes: Set<AccountType>,
    ): PostingBuildResult? {
        if (first.id == second.id) return PostingBuildResult.SameAccount
        if (first.isSystem || first.isArchived || first.type !in allowedFirstTypes) {
            return PostingBuildResult.InvalidAccountType(first.type)
        }
        if (second.isSystem || second.isArchived || second.type !in allowedSecondTypes) {
            return PostingBuildResult.InvalidAccountType(second.type)
        }
        if (first.currency != currency) {
            return PostingBuildResult.CurrencyMismatch(currency, first.currency)
        }
        if (second.currency != currency) {
            return PostingBuildResult.CurrencyMismatch(currency, second.currency)
        }
        if (!currency.isSupportedLedgerCurrency()) {
            return PostingBuildResult.UnsupportedCurrency(currency)
        }
        return null
    }

    private val ASSET_MOVEMENT_ACCOUNT_TYPES = setOf(
        AccountType.ASSET_CASH,
        AccountType.ASSET_BANK,
        AccountType.ASSET_EWALLET_BALANCE,
    )
    private val REFUND_DESTINATION_ACCOUNT_TYPES =
        ASSET_MOVEMENT_ACCOUNT_TYPES + AccountType.LIABILITY_CC
}
