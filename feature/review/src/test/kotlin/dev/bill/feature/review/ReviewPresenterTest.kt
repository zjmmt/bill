package dev.bill.feature.review

import dev.bill.application.AccountSummary
import dev.bill.application.DraftSummary
import dev.bill.application.DraftSummaryKind
import dev.bill.core.model.AccountType
import dev.bill.core.model.Money
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewPresenterTest {
    @Test
    fun `income excludes credit cards from funding choices`() {
        val draft = draft(DraftSummaryKind.INCOME)
        val cash = account("cash", AccountType.ASSET_CASH)
        val creditCard = account("card", AccountType.LIABILITY_CC)

        val state = ReviewPresenter.present(
            draft = draft,
            accounts = listOf(cash, creditCard),
            isSaving = false,
            operationError = null,
        )

        assertEquals(listOf(cash), state.eligibleAccounts)
    }

    @Test
    fun `expense allows an asset or credit card funding account`() {
        val draft = draft(DraftSummaryKind.EXPENSE)
        val cash = account("cash", AccountType.ASSET_CASH)
        val creditCard = account("card", AccountType.LIABILITY_CC)

        val state = ReviewPresenter.present(
            draft = draft,
            accounts = listOf(cash, creditCard),
            isSaving = false,
            operationError = null,
        )

        assertEquals(listOf(cash, creditCard), state.eligibleAccounts)
    }

    private fun draft(kind: DraftSummaryKind) = DraftSummary(
        id = "draft",
        kind = kind,
        amount = Money.cny(100),
        counterparty = "Example",
        note = null,
        fundingAccountId = null,
        occurredAt = Instant.EPOCH,
    )

    private fun account(id: String, type: AccountType) = AccountSummary(
        id = id,
        name = id,
        type = type,
        displayBalance = Money.cny(0),
        isLiability = type == AccountType.LIABILITY_CC,
    )
}
