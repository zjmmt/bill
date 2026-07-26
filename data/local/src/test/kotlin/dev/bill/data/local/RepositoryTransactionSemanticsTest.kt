package dev.bill.data.local

import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.PostedTransaction
import dev.bill.core.domain.SystemAccountIds
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.domain.TransactionStatus
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.EntryRole
import dev.bill.core.model.LedgerEntry
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryTransactionSemanticsTest {
    @Test
    fun validOpeningBalancePostsOnlyToNewAccountAndOpeningEquity() {
        val account = account()
        val transaction = openingTransaction(account.id)

        assertTrue(RepositoryTransactionSemantics.openingMatchesAccount(transaction, account))
    }

    @Test
    fun openingBalanceCannotPostToAnotherUserAccount() {
        val account = account()
        val transaction = openingTransaction(AccountId("another-account"))

        assertFalse(RepositoryTransactionSemantics.openingMatchesAccount(transaction, account))
    }

    @Test
    fun confirmedTransactionMustKeepTheDraftOccurrenceTime() {
        val draft = expenseDraft()
        val matching = expenseTransaction(draft)

        assertTrue(RepositoryTransactionSemantics.matchesDraft(matching, draft))
        assertFalse(
            RepositoryTransactionSemantics.matchesDraft(
                matching.copy(occurredAt = draft.occurredAt.minusSeconds(1)),
                draft,
            ),
        )
    }

    @Test
    fun confirmedTransactionMustPreserveExternalSourceMode() {
        val draft = expenseDraft(sourceMode = TransactionSourceMode.EXTERNAL)
        val matching = expenseTransaction(draft, sourceMode = TransactionSourceMode.EXTERNAL)

        assertTrue(RepositoryTransactionSemantics.matchesDraft(matching, draft))
        assertFalse(
            RepositoryTransactionSemantics.matchesDraft(
                matching.copy(sourceMode = TransactionSourceMode.MANUAL),
                draft,
            ),
        )
    }

    private fun account() = LedgerAccount(
        id = AccountId("new-account"),
        name = "测试账户",
        normalizedName = "测试账户",
        type = AccountType.ASSET_BANK,
        currency = CurrencyCode.CNY,
        isSystem = false,
        isArchived = false,
        createdAt = Instant.ofEpochMilli(1_000L),
        creationCommandId = CommandId("create-account"),
    )

    private fun openingTransaction(accountId: AccountId) = PostedTransaction(
        id = TransactionId("opening-transaction"),
        draftId = null,
        type = TransactionType.ADJUSTMENT,
        status = TransactionStatus.ACTIVE,
        sourceMode = TransactionSourceMode.MANUAL,
        occurredAt = Instant.ofEpochMilli(1_000L),
        confirmedAt = Instant.ofEpochMilli(1_000L),
        title = "测试期初余额",
        note = null,
        commandId = CommandId("create-account"),
        entries = listOf(
            LedgerEntry(
                accountId = accountId,
                amount = Money.cny(10_000L),
                role = EntryRole.ASSET,
            ),
            LedgerEntry(
                accountId = SystemAccountIds.OpeningEquity,
                amount = Money.cny(-10_000L),
                role = EntryRole.EQUITY,
            ),
        ),
    )

    private fun expenseDraft(
        sourceMode: TransactionSourceMode = TransactionSourceMode.MANUAL,
    ) = ManualDraft(
        id = DraftId("expense-draft"),
        state = DraftState.EDITED,
        type = TransactionType.EXPENSE,
        amount = Money.cny(2_500L),
        occurredAt = Instant.ofEpochMilli(2_000L),
        counterparty = "测试商户",
        note = "测试备注",
        fundingAccountId = AccountId("new-account"),
        createdAt = Instant.ofEpochMilli(1_000L),
        updatedAt = Instant.ofEpochMilli(1_500L),
        creationCommandId = CommandId("create-draft"),
        sourceMode = sourceMode,
    )

    private fun expenseTransaction(
        draft: ManualDraft,
        sourceMode: TransactionSourceMode = TransactionSourceMode.MANUAL,
    ) = PostedTransaction(
        id = TransactionId("confirmed-expense"),
        draftId = draft.id,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.ACTIVE,
        sourceMode = sourceMode,
        occurredAt = draft.occurredAt,
        confirmedAt = Instant.ofEpochMilli(3_000L),
        title = draft.counterparty,
        note = draft.note,
        commandId = CommandId("confirm-draft"),
        entries = listOf(
            LedgerEntry(
                accountId = SystemAccountIds.UncategorizedExpense,
                amount = draft.amount,
                role = EntryRole.EXPENSE,
            ),
            LedgerEntry(
                accountId = requireNotNull(draft.fundingAccountId),
                amount = Money.cny(-2_500L),
                role = EntryRole.FUNDING,
            ),
        ),
    )
}
