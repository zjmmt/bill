package dev.bill.core.ledger

import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.SystemAccountIds
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.EntryRole
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

    private fun account(
        type: AccountType,
        currency: CurrencyCode = CurrencyCode.CNY,
    ) = LedgerAccount(
        id = AccountId("account-1"),
        name = "测试账户",
        normalizedName = "测试账户",
        type = type,
        currency = currency,
        isSystem = false,
        isArchived = false,
        createdAt = now,
        creationCommandId = CommandId("command-account"),
    )

    private fun draft(type: TransactionType) = ManualDraft(
        id = DraftId("draft-1"),
        state = DraftState.WAITING_USER,
        type = type,
        amount = Money.cny(2_500L),
        occurredAt = now,
        counterparty = "测试商户",
        note = null,
        fundingAccountId = null,
        createdAt = now,
        updatedAt = now,
        creationCommandId = CommandId("command-draft"),
    )
}
