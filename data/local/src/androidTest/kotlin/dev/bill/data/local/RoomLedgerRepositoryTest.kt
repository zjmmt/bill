package dev.bill.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bill.core.domain.AuditAction
import dev.bill.core.domain.AuditEventId
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionType
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLedgerRepositoryTest {
    private lateinit var database: BillDatabase
    private lateinit var repository: RoomLedgerRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BillDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomLedgerRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun stableCommandReplaysAccountAndDraftWhenServiceTimestampsChange() = runBlocking {
        val firstTime = Instant.parse("2026-07-19T00:00:00Z")
        val retryTime = Instant.parse("2026-07-20T00:00:00Z")
        val account = account(firstTime)
        val accountAudit = audit(
            commandId = account.creationCommandId,
            suffix = "account-created",
            action = AuditAction.ACCOUNT_CREATED,
            entityType = "account",
            entityId = account.id.value,
            occurredAt = firstTime,
        )

        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.createAccount(account, null, listOf(accountAudit)).status,
        )
        assertEquals(
            RepositoryWriteStatus.ALREADY_APPLIED,
            repository.createAccount(
                account.copy(createdAt = retryTime),
                null,
                listOf(accountAudit.copy(occurredAt = retryTime)),
            ).status,
        )

        val draft = draft(firstTime)
        val draftAudit = audit(
            commandId = draft.creationCommandId,
            suffix = "draft-created",
            action = AuditAction.MANUAL_DRAFT_CREATED,
            entityType = "draft",
            entityId = draft.id.value,
            occurredAt = firstTime,
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.createManualDraft(draft, draftAudit).status,
        )
        assertEquals(
            RepositoryWriteStatus.ALREADY_APPLIED,
            repository.createManualDraft(
                draft.copy(
                    occurredAt = retryTime,
                    createdAt = retryTime,
                    updatedAt = retryTime,
                ),
                draftAudit.copy(occurredAt = retryTime),
            ).status,
        )
    }

    @Test
    fun differentCommandWithAnExistingNormalizedNameReportsDuplicateName() = runBlocking {
        val now = Instant.parse("2026-07-19T00:00:00Z")
        val first = account(now)
        val second = first.copy(
            id = AccountId("account:second-command"),
            creationCommandId = CommandId("second-command"),
        )

        repository.createAccount(
            first,
            null,
            listOf(
                audit(
                    first.creationCommandId,
                    "account-created",
                    AuditAction.ACCOUNT_CREATED,
                    "account",
                    first.id.value,
                    now,
                ),
            ),
        )
        val result = repository.createAccount(
            second,
            null,
            listOf(
                audit(
                    second.creationCommandId,
                    "account-created",
                    AuditAction.ACCOUNT_CREATED,
                    "account",
                    second.id.value,
                    now,
                ),
            ),
        )

        assertEquals(RepositoryWriteStatus.DUPLICATE_NAME, result.status)
    }

    @Test
    fun USD_is_allowed_for_bank_accounts_but_rejected_for_wallets() = runBlocking {
        val now = Instant.parse("2026-07-19T00:00:00Z")
        val usdBank = account(now).copy(
            id = AccountId("account:usd-bank"),
            name = "USD BANK",
            normalizedName = "usd bank",
            currency = CurrencyCode.USD,
            creationCommandId = CommandId("usd-bank-command"),
        )
        val bankAudit = audit(
            usdBank.creationCommandId,
            "account-created",
            AuditAction.ACCOUNT_CREATED,
            "account",
            usdBank.id.value,
            now,
        )

        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.createAccount(usdBank, null, listOf(bankAudit)).status,
        )
        assertEquals(
            CurrencyCode.USD,
            repository.observeState().first().accountBalances.single {
                it.account.id == usdBank.id
            }.account.currency,
        )

        val usdWallet = usdBank.copy(
            id = AccountId("account:usd-wallet"),
            name = "USD WALLET",
            normalizedName = "usd wallet",
            type = AccountType.ASSET_EWALLET_BALANCE,
            creationCommandId = CommandId("usd-wallet-command"),
        )
        val walletAudit = audit(
            usdWallet.creationCommandId,
            "account-created",
            AuditAction.ACCOUNT_CREATED,
            "account",
            usdWallet.id.value,
            now,
        )
        assertEquals(
            RepositoryWriteStatus.INVALID_STATE,
            repository.createAccount(usdWallet, null, listOf(walletAudit)).status,
        )
    }

    @Test
    fun invalidActiveLedgerStateFailsClosedInsteadOfPublishingAPartialSnapshot() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO ledger_transactions (
                id, draftId, type, status, sourceMode,
                occurredAtEpochMillis, confirmedAtEpochMillis,
                title, note, commandId
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "corrupt-transaction",
                null,
                "EXPENSE",
                "UNKNOWN_STATUS",
                "MANUAL",
                1_000L,
                1_000L,
                "REDACTED FIXTURE",
                null,
                "corrupt-command",
            ),
        )

        val failure = runCatching { repository.observeState().first() }.exceptionOrNull()

        assertTrue(failure is LocalDataIntegrityException)
    }

    @Test
    fun mixedCurrencyEntriesFailThePersistedLedgerIntegrityGate() = runBlocking {
        val now = Instant.parse("2026-07-19T00:00:00Z")
        val cnyBank = account(now).copy(
            id = AccountId("account:cny-integrity"),
            name = "CNY INTEGRITY",
            normalizedName = "cny integrity",
            creationCommandId = CommandId("cny-integrity-command"),
        )
        val usdBank = cnyBank.copy(
            id = AccountId("account:usd-integrity"),
            name = "USD INTEGRITY",
            normalizedName = "usd integrity",
            currency = CurrencyCode.USD,
            creationCommandId = CommandId("usd-integrity-command"),
        )
        listOf(cnyBank, usdBank).forEach { account ->
            assertEquals(
                RepositoryWriteStatus.APPLIED,
                repository.createAccount(
                    account,
                    null,
                    listOf(
                        audit(
                            account.creationCommandId,
                            "account-created",
                            AuditAction.ACCOUNT_CREATED,
                            "account",
                            account.id.value,
                            now,
                        ),
                    ),
                ).status,
            )
        }

        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO ledger_transactions (
                id, draftId, type, status, sourceMode,
                occurredAtEpochMillis, confirmedAtEpochMillis,
                title, note, commandId
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "mixed-currency-transaction",
                null,
                "EXPENSE",
                "ACTIVE",
                "MANUAL",
                now.toEpochMilli(),
                now.toEpochMilli(),
                "REDACTED FIXTURE",
                null,
                "mixed-currency-command",
            ),
        )
        listOf(
            arrayOf<Any?>(0, cnyBank.id.value, 100L, "CNY", "EXPENSE"),
            arrayOf<Any?>(1, cnyBank.id.value, -100L, "CNY", "FUNDING"),
            arrayOf<Any?>(2, usdBank.id.value, 100L, "USD", "EXPENSE"),
            arrayOf<Any?>(3, usdBank.id.value, -100L, "USD", "FUNDING"),
        ).forEach { entry ->
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO ledger_entries (
                    transactionId, position, accountId, amountMinorUnits, currency, role
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "mixed-currency-transaction",
                    entry[0],
                    entry[1],
                    entry[2],
                    entry[3],
                    entry[4],
                ),
            )
        }

        assertEquals(1L, database.ledgerDao().observeLedgerIntegrityIssueCount().first())
        val failure = runCatching { repository.observeState().first() }.exceptionOrNull()
        assertTrue(failure is LocalDataIntegrityException)
    }

    private fun account(createdAt: Instant) = LedgerAccount(
        id = AccountId("account:stable-command"),
        name = "TEST ACCOUNT",
        normalizedName = "test account",
        type = AccountType.ASSET_BANK,
        currency = CurrencyCode.CNY,
        isSystem = false,
        isArchived = false,
        createdAt = createdAt,
        creationCommandId = CommandId("stable-command"),
    )

    private fun draft(occurredAt: Instant) = ManualDraft(
        id = DraftId("draft:stable-draft-command"),
        state = DraftState.WAITING_USER,
        type = TransactionType.EXPENSE,
        amount = Money.cny(2_500L),
        occurredAt = occurredAt,
        counterparty = "TEST COUNTERPARTY",
        note = "fixture",
        fundingAccountId = null,
        createdAt = occurredAt,
        updatedAt = occurredAt,
        creationCommandId = CommandId("stable-draft-command"),
    )

    private fun audit(
        commandId: CommandId,
        suffix: String,
        action: AuditAction,
        entityType: String,
        entityId: String,
        occurredAt: Instant,
    ) = AuditRecord(
        id = AuditEventId("audit:${commandId.value}:$suffix"),
        commandId = commandId,
        action = action,
        entityType = entityType,
        entityId = entityId,
        occurredAt = occurredAt,
    )
}
