package dev.bill.core.ledger

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostingFactoryTest {
    private val now = Instant.parse("2026-07-19T08:00:00Z")

    @Test
    fun `asset opening balance creates balanced equity adjustment`() {
        val result = PostingFactory.openingBalance(
            account = account(AccountType.ASSET_BANK),
            visibleBalance = Money.cny(12_345L),
            transactionId = TransactionId("tx-opening"),
            occurredAt = now,
        )

        assertTrue(result is PostingBuildResult.Valid)
        val transaction = (result as PostingBuildResult.Valid).transaction
        assertEquals(TransactionType.ADJUSTMENT, transaction.type)
        assertEquals(12_345L, transaction.entries[0].amount.minorUnits)
        assertEquals(EntryRole.ASSET, transaction.entries[0].role)
        assertEquals(-12_345L, transaction.entries[1].amount.minorUnits)
        assertEquals(SystemAccountIds.OpeningEquity, transaction.entries[1].accountId)
    }

    @Test
    fun `credit card opening balance is stored as a negative liability`() {
        val result = PostingFactory.openingBalance(
            account = account(AccountType.LIABILITY_CC),
            visibleBalance = Money.cny(6_700L),
            transactionId = TransactionId("tx-opening"),
            occurredAt = now,
        )

        assertTrue(result is PostingBuildResult.Valid)
        val transaction = (result as PostingBuildResult.Valid).transaction
        assertEquals(-6_700L, transaction.entries[0].amount.minorUnits)
        assertEquals(EntryRole.LIABILITY, transaction.entries[0].role)
        assertEquals(6_700L, transaction.entries[1].amount.minorUnits)
    }

    @Test
    fun `expense paid by bank decreases the asset`() {
        val result = PostingFactory.manualDraft(
            draft = draft(TransactionType.EXPENSE),
            fundingAccount = account(AccountType.ASSET_BANK),
            transactionId = TransactionId("tx-expense"),
            confirmedAt = now,
        )

        assertTrue(result is PostingBuildResult.Valid)
        val transaction = (result as PostingBuildResult.Valid).transaction
        assertEquals(2_500L, transaction.entries[0].amount.minorUnits)
        assertEquals(-2_500L, transaction.entries[1].amount.minorUnits)
        assertEquals(EntryRole.FUNDING, transaction.entries[1].role)
    }

    @Test
    fun `credit card expense increases the liability`() {
        val result = PostingFactory.manualDraft(
            draft = draft(TransactionType.EXPENSE),
            fundingAccount = account(AccountType.LIABILITY_CC),
            transactionId = TransactionId("tx-expense"),
            confirmedAt = now,
        )

        assertTrue(result is PostingBuildResult.Valid)
        val transaction = (result as PostingBuildResult.Valid).transaction
        assertEquals(-2_500L, transaction.entries[1].amount.minorUnits)
        assertEquals(EntryRole.LIABILITY, transaction.entries[1].role)
    }

    @Test
    fun `income received by bank increases the asset`() {
        val result = PostingFactory.manualDraft(
            draft = draft(TransactionType.INCOME),
            fundingAccount = account(AccountType.ASSET_BANK),
            transactionId = TransactionId("tx-income"),
            confirmedAt = now,
        )

        assertTrue(result is PostingBuildResult.Valid)
        val transaction = (result as PostingBuildResult.Valid).transaction
        assertEquals(2_500L, transaction.entries[0].amount.minorUnits)
        assertEquals(-2_500L, transaction.entries[1].amount.minorUnits)
        assertEquals(EntryRole.INCOME, transaction.entries[1].role)
    }

    @Test
    fun `income cannot be posted into a credit card account`() {
        val result = PostingFactory.manualDraft(
            draft = draft(TransactionType.INCOME),
            fundingAccount = account(AccountType.LIABILITY_CC),
            transactionId = TransactionId("tx-income"),
            confirmedAt = now,
        )

        assertTrue(result is PostingBuildResult.InvalidAccountType)
    }

    @Test
    fun `fund purchase moves an asset into the selected investment account`() {
        val bank = account(AccountType.ASSET_BANK, id = "bank")
        val investment = account(AccountType.INVESTMENT_SECURITY, id = "investment")
        val result = PostingFactory.manualDraft(
            draft = draft(
                type = TransactionType.INVEST_BUY,
                fundingAccountId = bank.id,
                investmentAccountId = investment.id,
            ),
            fundingAccount = bank,
            investmentAccount = investment,
            transactionId = TransactionId("tx-invest-buy"),
            confirmedAt = now,
        )

        assertTrue(result is PostingBuildResult.Valid)
        val transaction = (result as PostingBuildResult.Valid).transaction
        assertEquals(TransactionType.INVEST_BUY, transaction.type)
        assertEquals(-2_500L, transaction.entries[0].amount.minorUnits)
        assertEquals(EntryRole.FUNDING, transaction.entries[0].role)
        assertEquals(2_500L, transaction.entries[1].amount.minorUnits)
        assertEquals(EntryRole.INVESTMENT, transaction.entries[1].role)
        assertEquals(investment.id, transaction.entries[1].accountId)
    }

    @Test
    fun `USD bank opening balance uses USD equity`() {
        val result = PostingFactory.openingBalance(
            account = account(AccountType.ASSET_BANK, CurrencyCode.USD),
            visibleBalance = Money(100L, CurrencyCode.USD),
            transactionId = TransactionId("tx-opening"),
            occurredAt = now,
        )

        assertTrue(result is PostingBuildResult.Valid)
        val transaction = (result as PostingBuildResult.Valid).transaction
        assertEquals(SystemAccountIds.openingEquity(CurrencyCode.USD), transaction.entries[1].accountId)
        assertEquals(CurrencyCode.USD, transaction.entries[0].amount.currency)
    }

    @Test
    fun `unsupported foreign currency is rejected`() {
        val euro = CurrencyCode("EUR")
        val result = PostingFactory.openingBalance(
            account = account(AccountType.ASSET_BANK, euro),
            visibleBalance = Money(100L, euro),
            transactionId = TransactionId("tx-opening-eur"),
            occurredAt = now,
        )

        assertTrue(result is PostingBuildResult.UnsupportedCurrency)
    }

    @Test
    fun `transfer pair moves money between distinct own assets without income or expense`() {
        val source = account(AccountType.ASSET_BANK, id = "bank-source")
        val destination = account(AccountType.ASSET_EWALLET_BALANCE, id = "wallet-destination")
        val result = PostingFactory.transferPair(
            outboundDraft = draft(
                TransactionType.EXPENSE,
                id = "outbound",
                fundingAccountId = source.id,
            ),
            inboundDraft = draft(
                TransactionType.INCOME,
                id = "inbound",
                fundingAccountId = destination.id,
            ),
            sourceAccount = source,
            destinationAccount = destination,
            transactionId = TransactionId("tx-transfer"),
            confirmedAt = now,
        )

        val transaction = (result as PostingBuildResult.Valid).transaction
        assertEquals(TransactionType.TRANSFER, transaction.type)
        assertEquals(-2_500L, transaction.entries[0].amount.minorUnits)
        assertEquals(2_500L, transaction.entries[1].amount.minorUnits)
        assertEquals(
            setOf(EntryRole.FUNDING, EntryRole.ASSET),
            transaction.entries.mapTo(mutableSetOf(), LedgerEntry::role),
        )
    }

    @Test
    fun `transfer pair rejects the same account and mismatched amounts`() {
        val account = account(AccountType.ASSET_BANK, id = "same")
        assertEquals(
            PostingBuildResult.SameAccount,
            PostingFactory.transferPair(
                outboundDraft = draft(
                    TransactionType.EXPENSE,
                    id = "out",
                    fundingAccountId = account.id,
                ),
                inboundDraft = draft(
                    TransactionType.INCOME,
                    id = "in",
                    fundingAccountId = account.id,
                ),
                sourceAccount = account,
                destinationAccount = account,
                transactionId = TransactionId("tx-same"),
                confirmedAt = now,
            ),
        )

        val destination = account(AccountType.ASSET_BANK, id = "destination")
        val mismatch = PostingFactory.transferPair(
            outboundDraft = draft(
                TransactionType.EXPENSE,
                id = "out",
                fundingAccountId = account.id,
            ),
            inboundDraft = draft(
                TransactionType.INCOME,
                id = "in",
                fundingAccountId = destination.id,
                amount = Money.cny(2_400),
            ),
            sourceAccount = account,
            destinationAccount = destination,
            transactionId = TransactionId("tx-mismatch"),
            confirmedAt = now,
        )
        assertEquals(PostingBuildResult.InvalidDraftPair, mismatch)
    }

    @Test
    fun `liability repayment reduces bank asset and credit card debt`() {
        val bank = account(AccountType.ASSET_BANK, id = "bank")
        val card = account(AccountType.LIABILITY_CC, id = "card")
        val result = PostingFactory.liabilityRepayment(
            outboundDraft = draft(
                TransactionType.EXPENSE,
                id = "repay-draft",
                fundingAccountId = bank.id,
            ),
            sourceAccount = bank,
            liabilityAccount = card,
            transactionId = TransactionId("tx-repayment"),
            confirmedAt = now,
        )

        val transaction = (result as PostingBuildResult.Valid).transaction
        assertEquals(TransactionType.LIABILITY_REPAY, transaction.type)
        assertEquals(-2_500L, transaction.entries[0].amount.minorUnits)
        assertEquals(EntryRole.FUNDING, transaction.entries[0].role)
        assertEquals(2_500L, transaction.entries[1].amount.minorUnits)
        assertEquals(EntryRole.LIABILITY, transaction.entries[1].role)
    }

    @Test
    fun `partial refund reverses expense and cannot exceed remaining amount`() {
        val bank = account(AccountType.ASSET_BANK, id = "bank")
        val original = expenseTransaction(bank, amount = 5_000)
        val inbound = draft(
            TransactionType.INCOME,
            id = "refund-draft",
            fundingAccountId = bank.id,
            amount = Money.cny(2_000),
        )

        val result = PostingFactory.refund(
            inboundDraft = inbound,
            destinationAccount = bank,
            originalExpense = original,
            alreadyRefundedMinorUnits = 1_000,
            transactionId = TransactionId("tx-refund"),
            confirmedAt = now,
        )
        val transaction = (result as PostingBuildResult.Valid).transaction
        assertEquals(TransactionType.REFUND, transaction.type)
        assertEquals(2_000L, transaction.entries[0].amount.minorUnits)
        assertEquals(-2_000L, transaction.entries[1].amount.minorUnits)
        assertEquals(EntryRole.EXPENSE, transaction.entries[1].role)

        assertEquals(
            PostingBuildResult.AmountExceedsRemaining,
            PostingFactory.refund(
                inboundDraft = inbound.copy(amount = Money.cny(4_001)),
                destinationAccount = bank,
                originalExpense = original,
                alreadyRefundedMinorUnits = 1_000,
                transactionId = TransactionId("tx-refund-too-large"),
                confirmedAt = now,
            ),
        )
    }

    private fun account(
        type: AccountType,
        currency: CurrencyCode = CurrencyCode.CNY,
        id: String = "account-1",
    ) = LedgerAccount(
        id = AccountId(id),
        name = "测试账户",
        normalizedName = "测试账户",
        type = type,
        currency = currency,
        isSystem = false,
        isArchived = false,
        createdAt = now,
        creationCommandId = CommandId("command-account"),
    )

    private fun draft(
        type: TransactionType,
        id: String = "draft-1",
        fundingAccountId: AccountId? = null,
        investmentAccountId: AccountId? = if (type == TransactionType.INVEST_BUY) {
            AccountId("investment")
        } else {
            null
        },
        amount: Money = Money.cny(2_500L),
    ) = ManualDraft(
        id = DraftId(id),
        state = DraftState.WAITING_USER,
        type = type,
        amount = amount,
        occurredAt = now,
        counterparty = "测试商户",
        note = null,
        fundingAccountId = fundingAccountId,
        investmentAccountId = investmentAccountId,
        createdAt = now,
        updatedAt = now,
        creationCommandId = CommandId("command-draft"),
    )

    private fun expenseTransaction(
        fundingAccount: LedgerAccount,
        amount: Long,
    ) = PostedTransaction(
        id = TransactionId("original-expense"),
        draftId = DraftId("original-draft"),
        type = TransactionType.EXPENSE,
        status = TransactionStatus.ACTIVE,
        sourceMode = TransactionSourceMode.EXTERNAL,
        occurredAt = now.minusSeconds(3_600),
        confirmedAt = now.minusSeconds(3_000),
        title = "原消费",
        note = null,
        commandId = CommandId("original-command"),
        entries = listOf(
            LedgerEntry(
                accountId = SystemAccountIds.uncategorizedExpense(
                    fundingAccount.currency,
                ),
                amount = Money(amount, fundingAccount.currency),
                role = EntryRole.EXPENSE,
            ),
            LedgerEntry(
                accountId = fundingAccount.id,
                amount = Money(-amount, fundingAccount.currency),
                role = EntryRole.FUNDING,
            ),
        ),
    )
}
