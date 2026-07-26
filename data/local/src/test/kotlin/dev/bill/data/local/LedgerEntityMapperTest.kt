package dev.bill.data.local

import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.domain.TransactionStatus
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.EntryRole
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerEntityMapperTest {
    @Test
    fun accountRoundTripPreservesDomainFields() {
        val entity = validAccount()

        val domain = LedgerEntityMapper.accountToDomain(entity, MappingDiagnostics.None)

        assertEquals(entity, LedgerEntityMapper.accountToEntity(checkNotNull(domain)))
    }

    @Test
    fun unknownAccountTypeIsReportedAndSkipped() {
        val issues = mutableListOf<MappingIssue>()
        val entity = validAccount().copy(type = "NEW_SERVER_SIDE_ENUM")

        val domain = LedgerEntityMapper.accountToDomain(entity, issues::add)

        assertNull(domain)
        assertEquals(
            MappingIssue(
                entity = "accounts",
                field = "type",
                kind = MappingIssueKind.UNKNOWN_ENUM,
                rawSchemaValue = "NEW_SERVER_SIDE_ENUM",
            ),
            issues.single(),
        )
    }

    @Test
    fun invalidEntryRoleSkipsWholeTransactionWithoutThrowing() {
        val issues = mutableListOf<MappingIssue>()
        val relation = TransactionWithEntries(
            transaction = TransactionEntity(
                id = "transaction-1",
                draftId = "draft-1",
                type = TransactionType.EXPENSE.name,
                status = TransactionStatus.ACTIVE.name,
                sourceMode = TransactionSourceMode.MANUAL.name,
                occurredAtEpochMillis = 1_000L,
                confirmedAtEpochMillis = 2_000L,
                title = "redacted-test-title",
                note = null,
                commandId = "command-1",
            ),
            entries = listOf(
                EntryEntity(
                    transactionId = "transaction-1",
                    position = 0,
                    accountId = "account-1",
                    amountMinorUnits = 100L,
                    currency = CurrencyCode.CNY.value,
                    role = EntryRole.EXPENSE.name,
                ),
                EntryEntity(
                    transactionId = "transaction-1",
                    position = 1,
                    accountId = "account-2",
                    amountMinorUnits = -100L,
                    currency = CurrencyCode.CNY.value,
                    role = "UNKNOWN_ROLE",
                ),
            ),
        )

        val domain = LedgerEntityMapper.transactionToDomain(relation, issues::add)

        assertNull(domain)
        assertEquals(MappingIssueKind.UNKNOWN_ENUM, issues.single().kind)
        assertEquals("role", issues.single().field)
    }

    @Test
    fun invalidDraftCurrencyIsReportedAndSkipped() {
        val issues = mutableListOf<MappingIssue>()
        val draft = LedgerEntityMapper.draftToEntity(
            ManualDraft(
                id = DraftId("draft-1"),
                state = DraftState.WAITING_USER,
                type = TransactionType.INCOME,
                amount = Money.cny(123L),
                occurredAt = Instant.ofEpochMilli(1_000L),
                counterparty = "redacted-counterparty",
                note = null,
                fundingAccountId = null,
                createdAt = Instant.ofEpochMilli(2_000L),
                updatedAt = Instant.ofEpochMilli(2_000L),
                creationCommandId = CommandId("command-1"),
            ),
        ).copy(currency = "CN")

        val domain = LedgerEntityMapper.draftToDomain(draft, issues::add)

        assertNull(domain)
        assertEquals(MappingIssueKind.INVALID_CURRENCY, issues.single().kind)
    }

    @Test
    fun balanceCurrencyMismatchIsDiagnosedAndRejectedWithoutLeakingAmounts() {
        val issues = mutableListOf<MappingIssue>()

        val balance = LedgerEntityMapper.accountBalanceToDomain(
            AccountBalanceRow(
                account = validAccount(),
                balanceMinorUnits = 500L,
                currencyMismatchCount = 1L,
            ),
            issues::add,
        )

        assertNull(balance)
        assertEquals(
            MappingIssue(
                entity = "ledger_entries",
                field = "currency",
                kind = MappingIssueKind.CURRENCY_MISMATCH,
            ),
            issues.single(),
        )
    }

    private fun validAccount() = AccountEntity(
        id = "account-1",
        name = "测试账户",
        normalizedName = "测试账户",
        type = "ASSET_BANK",
        currency = CurrencyCode.CNY.value,
        isSystem = false,
        isArchived = false,
        createdAtEpochMillis = 1_000L,
        creationCommandId = "command-1",
    )
}
