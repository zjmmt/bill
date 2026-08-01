package dev.bill.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bill.core.domain.AuditAction
import dev.bill.core.domain.AuditEventId
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.BalanceSnapshot
import dev.bill.core.domain.BalanceSnapshotId
import dev.bill.core.domain.BalanceSnapshotSourceMode
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.ObservedChannel
import dev.bill.core.domain.PostedTransaction
import dev.bill.core.domain.ReconciliationDraftLink
import dev.bill.core.domain.ReconciliationDraftRole
import dev.bill.core.domain.ReconciliationKind
import dev.bill.core.domain.ReconciliationResolution
import dev.bill.core.domain.RelationDecision
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.core.domain.TransactionRelation
import dev.bill.core.domain.TransactionRelationId
import dev.bill.core.domain.TransactionRelationType
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.domain.TransactionStatus
import dev.bill.core.ledger.PostingBuildResult
import dev.bill.core.ledger.PostingFactory
import dev.bill.core.ledger.ValidatedLedgerTransaction
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionId
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
        assertEquals(
            RepositoryWriteStatus.COMMAND_COLLISION,
            repository.createManualDraft(
                draft.copy(observedChannel = ObservedChannel.ALIPAY),
                draftAudit,
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
        val usdSnapshot = balanceSnapshot(
            id = "usd-bank",
            account = usdBank,
            observedBalance = Money(12_345L, CurrencyCode.USD),
            asOf = now,
            recordedAt = now,
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.createBalanceSnapshot(
                usdSnapshot,
                balanceSnapshotAudit(usdSnapshot),
            ).status,
        )
        assertEquals(
            CurrencyCode.USD,
            repository.observeState().first().balanceSnapshotComparisons.single()
                .snapshot.observedBalance.currency,
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
    fun transferReconciliationCanBeUndoneAndResolvedAgainWithoutLosingHistory() = runBlocking {
        val occurredAt = Instant.parse("2026-07-19T08:00:00Z")
        val confirmedAt = occurredAt.plusSeconds(120)
        val source = userAccount("source-bank", AccountType.ASSET_BANK, occurredAt)
        val destination = userAccount(
            "destination-wallet",
            AccountType.ASSET_EWALLET_BALANCE,
            occurredAt,
        )
        persistAccount(source, occurredAt)
        persistAccount(destination, occurredAt)
        val outbound = reviewDraft(
            id = "transfer-out",
            type = TransactionType.EXPENSE,
            accountId = source.id,
            occurredAt = occurredAt,
        )
        val inbound = reviewDraft(
            id = "transfer-in",
            type = TransactionType.INCOME,
            accountId = destination.id,
            occurredAt = occurredAt.plusSeconds(30),
        )
        persistDraft(outbound)
        persistDraft(inbound)

        val commandId = CommandId("reconcile-transfer")
        val validated = (
            PostingFactory.transferPair(
                outboundDraft = outbound,
                inboundDraft = inbound,
                sourceAccount = source,
                destinationAccount = destination,
                transactionId = TransactionId("transaction:reconcile-transfer"),
                confirmedAt = confirmedAt,
            ) as PostingBuildResult.Valid
            ).transaction
        val transaction = postedTransaction(
            validated = validated,
            draftId = null,
            commandId = commandId,
            confirmedAt = confirmedAt,
            title = "TEST TRANSFER",
        )
        val resolution = ReconciliationResolution(
            kind = ReconciliationKind.TRANSFER_PAIR,
            transaction = transaction,
            draftLinks = listOf(
                ReconciliationDraftLink(
                    outbound.id,
                    ReconciliationDraftRole.TRANSFER_OUTBOUND,
                ),
                ReconciliationDraftLink(
                    inbound.id,
                    ReconciliationDraftRole.TRANSFER_INBOUND,
                ),
            ),
            relations = emptyList(),
        )
        val reconcileAudit = audit(
            commandId,
            "reconciliation-confirmed",
            AuditAction.RECONCILIATION_CONFIRMED,
            "transaction",
            transaction.id.value,
            confirmedAt,
        )

        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.resolveReconciliation(resolution, reconcileAudit).status,
        )
        assertEquals(
            RepositoryWriteStatus.ALREADY_APPLIED,
            repository.resolveReconciliation(resolution, reconcileAudit).status,
        )
        val reconciled = repository.observeState().first()
        val balances = reconciled.accountBalances.associate { it.account.id to it.balance }
        assertEquals(Money.cny(-2_500L), balances[source.id])
        assertEquals(Money.cny(2_500L), balances[destination.id])
        assertTrue(reconciled.pendingDrafts.isEmpty())
        assertEquals(
            "LINKED",
            database.ledgerDao().findDraft(outbound.id.value)?.draft?.state,
        )
        assertEquals(
            "LINKED",
            database.ledgerDao().findDraft(inbound.id.value)?.draft?.state,
        )

        val voidedAt = confirmedAt.plusSeconds(60)
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.voidTransaction(
                transaction.id,
                audit(
                    CommandId("void-transfer"),
                    "transaction-voided",
                    AuditAction.TRANSACTION_VOIDED,
                    "transaction",
                    transaction.id.value,
                    voidedAt,
                ),
            ).status,
        )
        val restored = repository.observeState().first()
        val restoredBalances = restored.accountBalances.associate { it.account.id to it.balance }
        assertEquals(Money.cny(0L), restoredBalances[source.id])
        assertEquals(Money.cny(0L), restoredBalances[destination.id])
        assertEquals(
            setOf(outbound.id, inbound.id),
            restored.pendingDrafts.map { it.id }.toSet(),
        )

        val secondConfirmedAt = voidedAt.plusSeconds(60)
        val secondCommandId = CommandId("reconcile-transfer-again")
        val secondValidated = (
            PostingFactory.transferPair(
                outboundDraft = outbound,
                inboundDraft = inbound,
                sourceAccount = source,
                destinationAccount = destination,
                transactionId = TransactionId("transaction:reconcile-transfer-again"),
                confirmedAt = secondConfirmedAt,
            ) as PostingBuildResult.Valid
            ).transaction
        val secondTransaction = postedTransaction(
            validated = secondValidated,
            draftId = null,
            commandId = secondCommandId,
            confirmedAt = secondConfirmedAt,
            title = "TEST TRANSFER AGAIN",
        )
        val secondResolution = ReconciliationResolution(
            kind = ReconciliationKind.TRANSFER_PAIR,
            transaction = secondTransaction,
            draftLinks = resolution.draftLinks,
            relations = emptyList(),
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.resolveReconciliation(
                secondResolution,
                audit(
                    secondCommandId,
                    "reconciliation-confirmed",
                    AuditAction.RECONCILIATION_CONFIRMED,
                    "transaction",
                    secondTransaction.id.value,
                    secondConfirmedAt,
                ),
            ).status,
        )
        val reconciledAgain = repository.observeState().first()
        val reconciledAgainBalances = reconciledAgain.accountBalances.associate {
            it.account.id to it.balance
        }
        assertEquals(Money.cny(-2_500L), reconciledAgainBalances[source.id])
        assertEquals(Money.cny(2_500L), reconciledAgainBalances[destination.id])
        assertTrue(reconciledAgain.pendingDrafts.isEmpty())
        assertEquals(
            2,
            database.ledgerDao().findReconciliationDraftLinks(transaction.id.value).size,
        )
        assertEquals(
            2,
            database.ledgerDao().findReconciliationDraftLinks(
                secondTransaction.id.value,
            ).size,
        )
    }

    @Test
    fun refundCapBlocksOverRefundAndOriginalVoidUntilRefundIsUndone() = runBlocking {
        val createdAt = Instant.parse("2026-07-19T00:00:00Z")
        val bank = userAccount("refund-bank", AccountType.ASSET_BANK, createdAt)
        persistAccount(bank, createdAt)

        val originalDraft = reviewDraft(
            id = "original-expense",
            type = TransactionType.EXPENSE,
            accountId = bank.id,
            occurredAt = createdAt.plusSeconds(60),
            amount = Money.cny(5_000L),
        )
        persistDraft(originalDraft)
        val originalCommand = CommandId("confirm-original")
        val originalConfirmedAt = createdAt.plusSeconds(120)
        val originalValidated = (
            PostingFactory.manualDraft(
                draft = originalDraft,
                fundingAccount = bank,
                transactionId = TransactionId("transaction:original-expense"),
                confirmedAt = originalConfirmedAt,
            ) as PostingBuildResult.Valid
            ).transaction
        val original = postedTransaction(
            validated = originalValidated,
            draftId = originalDraft.id,
            commandId = originalCommand,
            confirmedAt = originalConfirmedAt,
            title = originalDraft.counterparty,
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.confirmDraft(
                originalDraft.id,
                original,
                audit(
                    originalCommand,
                    "draft-confirmed",
                    AuditAction.DRAFT_CONFIRMED,
                    "draft",
                    originalDraft.id.value,
                    originalConfirmedAt,
                ),
            ).status,
        )

        val firstRefundDraft = reviewDraft(
            id = "refund-one",
            type = TransactionType.INCOME,
            accountId = bank.id,
            occurredAt = createdAt.plusSeconds(180),
            amount = Money.cny(3_000L),
        )
        persistDraft(firstRefundDraft)
        val refundCommand = CommandId("reconcile-refund-one")
        val refundConfirmedAt = createdAt.plusSeconds(240)
        val refundValidated = (
            PostingFactory.refund(
                inboundDraft = firstRefundDraft,
                destinationAccount = bank,
                originalExpense = original,
                alreadyRefundedMinorUnits = 0L,
                transactionId = TransactionId("transaction:refund-one"),
                confirmedAt = refundConfirmedAt,
            ) as PostingBuildResult.Valid
            ).transaction
        val refund = postedTransaction(
            validated = refundValidated,
            draftId = null,
            commandId = refundCommand,
            confirmedAt = refundConfirmedAt,
            title = "TEST REFUND",
        )
        val refundResolution = refundResolution(
            transaction = refund,
            draft = firstRefundDraft,
            original = original,
            createdAt = refundConfirmedAt,
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.resolveReconciliation(
                refundResolution,
                audit(
                    refundCommand,
                    "reconciliation-confirmed",
                    AuditAction.RECONCILIATION_CONFIRMED,
                    "transaction",
                    refund.id.value,
                    refundConfirmedAt,
                ),
            ).status,
        )
        assertEquals(Money.cny(3_000L), repository.activeRefundTotal(original.id))

        val excessiveDraft = reviewDraft(
            id = "refund-too-large",
            type = TransactionType.INCOME,
            accountId = bank.id,
            occurredAt = createdAt.plusSeconds(300),
            amount = Money.cny(2_500L),
        )
        persistDraft(excessiveDraft)
        val excessiveCommand = CommandId("reconcile-refund-too-large")
        val excessiveConfirmedAt = createdAt.plusSeconds(360)
        val excessiveValidated = (
            PostingFactory.refund(
                inboundDraft = excessiveDraft,
                destinationAccount = bank,
                originalExpense = original,
                alreadyRefundedMinorUnits = 0L,
                transactionId = TransactionId("transaction:refund-too-large"),
                confirmedAt = excessiveConfirmedAt,
            ) as PostingBuildResult.Valid
            ).transaction
        val excessive = postedTransaction(
            validated = excessiveValidated,
            draftId = null,
            commandId = excessiveCommand,
            confirmedAt = excessiveConfirmedAt,
            title = "TEST EXCESSIVE REFUND",
        )
        assertEquals(
            RepositoryWriteStatus.INVALID_STATE,
            repository.resolveReconciliation(
                refundResolution(
                    transaction = excessive,
                    draft = excessiveDraft,
                    original = original,
                    createdAt = excessiveConfirmedAt,
                ),
                audit(
                    excessiveCommand,
                    "reconciliation-confirmed",
                    AuditAction.RECONCILIATION_CONFIRMED,
                    "transaction",
                    excessive.id.value,
                    excessiveConfirmedAt,
                ),
            ).status,
        )
        assertTrue(repository.findTransaction(excessive.id) == null)

        assertEquals(
            RepositoryWriteStatus.INVALID_STATE,
            repository.voidTransaction(
                original.id,
                audit(
                    CommandId("void-original-too-early"),
                    "transaction-voided",
                    AuditAction.TRANSACTION_VOIDED,
                    "transaction",
                    original.id.value,
                    excessiveConfirmedAt.plusSeconds(60),
                ),
            ).status,
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.voidTransaction(
                refund.id,
                audit(
                    CommandId("void-refund"),
                    "transaction-voided",
                    AuditAction.TRANSACTION_VOIDED,
                    "transaction",
                    refund.id.value,
                    excessiveConfirmedAt.plusSeconds(120),
                ),
            ).status,
        )
        assertEquals(Money.cny(0L), repository.activeRefundTotal(original.id))
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.voidTransaction(
                original.id,
                audit(
                    CommandId("void-original"),
                    "transaction-voided",
                    AuditAction.TRANSACTION_VOIDED,
                    "transaction",
                    original.id.value,
                    excessiveConfirmedAt.plusSeconds(180),
                ),
            ).status,
        )
    }

    @Test
    fun balanceSnapshotIsIdempotentAndTracksHistoricalLedgerChangesWithoutPosting() = runBlocking {
        val createdAt = Instant.parse("2026-07-19T00:00:00Z")
        val bank = userAccount("snapshot-bank", AccountType.ASSET_BANK, createdAt)
        persistAccount(bank, createdAt)
        persistTransaction(
            id = "income-before-snapshot",
            type = TransactionType.INCOME,
            account = bank,
            amount = Money.cny(10_000L),
            occurredAt = createdAt.plusSeconds(60),
            confirmedAt = createdAt.plusSeconds(120),
        )
        persistTransaction(
            id = "expense-after-snapshot",
            type = TransactionType.EXPENSE,
            account = bank,
            amount = Money.cny(2_000L),
            occurredAt = createdAt.plusSeconds(600),
            confirmedAt = createdAt.plusSeconds(660),
        )
        val snapshot = balanceSnapshot(
            id = "bank",
            account = bank,
            observedBalance = Money.cny(10_000L),
            asOf = createdAt.plusSeconds(300),
            recordedAt = createdAt.plusSeconds(900),
        )
        val snapshotAudit = balanceSnapshotAudit(snapshot)

        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.createBalanceSnapshot(snapshot, snapshotAudit).status,
        )
        assertEquals(
            RepositoryWriteStatus.ALREADY_APPLIED,
            repository.createBalanceSnapshot(
                snapshot.copy(recordedAt = snapshot.recordedAt.plusSeconds(60)),
                snapshotAudit.copy(occurredAt = snapshot.recordedAt.plusSeconds(60)),
            ).status,
        )
        assertEquals(
            RepositoryWriteStatus.COMMAND_COLLISION,
            repository.createBalanceSnapshot(
                snapshot.copy(observedBalance = Money.cny(9_999L)),
                snapshotAudit,
            ).status,
        )

        var state = repository.observeState().first()
        assertEquals(Money.cny(8_000L), state.accountBalances.single { it.account.id == bank.id }.balance)
        var comparison = state.balanceSnapshotComparisons.single()
        assertEquals(Money.cny(10_000L), comparison.ledgerBalance)
        assertEquals(Money.cny(0L), comparison.difference)
        assertTrue(comparison.isReconciled)
        assertEquals(2, state.recentTransactions.size)

        val backdated = persistTransaction(
            id = "backdated-expense",
            type = TransactionType.EXPENSE,
            account = bank,
            amount = Money.cny(1_000L),
            occurredAt = createdAt.plusSeconds(240),
            confirmedAt = createdAt.plusSeconds(1_020),
        )
        state = repository.observeState().first()
        comparison = state.balanceSnapshotComparisons.single()
        assertEquals(Money.cny(9_000L), comparison.ledgerBalance)
        assertEquals(Money.cny(1_000L), comparison.difference)

        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.voidTransaction(
                backdated.id,
                audit(
                    CommandId("void-backdated-expense"),
                    "transaction-voided",
                    AuditAction.TRANSACTION_VOIDED,
                    "transaction",
                    backdated.id.value,
                    createdAt.plusSeconds(1_080),
                ),
            ).status,
        )
        comparison = repository.observeState().first().balanceSnapshotComparisons.single()
        assertEquals(Money.cny(10_000L), comparison.ledgerBalance)
        assertTrue(comparison.isReconciled)

        val newerSnapshot = balanceSnapshot(
            id = "bank-newer",
            account = bank,
            observedBalance = Money.cny(9_500L),
            asOf = createdAt.plusSeconds(360),
            recordedAt = createdAt.plusSeconds(1_200),
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.createBalanceSnapshot(
                newerSnapshot,
                balanceSnapshotAudit(newerSnapshot),
            ).status,
        )
        comparison = repository.observeState().first().balanceSnapshotComparisons.single()
        assertEquals(newerSnapshot.id, comparison.snapshot.id)
        assertEquals(Money.cny(-500L), comparison.difference)
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM balance_snapshots WHERE accountId = ?",
            arrayOf(bank.id.value),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2L, cursor.getLong(0))
        }
    }

    @Test
    fun archivedAccountRetainsSnapshotHistoryWithoutPoisoningCurrentState() = runBlocking {
        val createdAt = Instant.parse("2026-07-19T00:00:00Z")
        val bank = userAccount("archived-snapshot-bank", AccountType.ASSET_BANK, createdAt)
        persistAccount(bank, createdAt)
        val snapshot = balanceSnapshot(
            id = "archived-bank",
            account = bank,
            observedBalance = Money.cny(1_000L),
            asOf = createdAt,
            recordedAt = createdAt.plusSeconds(60),
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.createBalanceSnapshot(snapshot, balanceSnapshotAudit(snapshot)).status,
        )
        assertEquals(1, repository.observeState().first().balanceSnapshotComparisons.size)

        database.openHelper.writableDatabase.execSQL(
            "UPDATE accounts SET isArchived = 1 WHERE id = ?",
            arrayOf(bank.id.value),
        )

        assertEquals(0L, database.ledgerDao().observeBalanceSnapshotIntegrityIssueCount().first())
        assertTrue(repository.observeState().first().balanceSnapshotComparisons.isEmpty())
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM balance_snapshots WHERE accountId = ?",
            arrayOf(bank.id.value),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
    }

    @Test
    fun creditCardSnapshotComparesPositiveUserDebtAgainstSignedLedgerLiability() = runBlocking {
        val createdAt = Instant.parse("2026-07-19T00:00:00Z")
        val card = userAccount("snapshot-card", AccountType.LIABILITY_CC, createdAt)
        persistAccount(card, createdAt)
        persistTransaction(
            id = "card-expense",
            type = TransactionType.EXPENSE,
            account = card,
            amount = Money.cny(5_000L),
            occurredAt = createdAt.plusSeconds(60),
            confirmedAt = createdAt.plusSeconds(120),
        )
        val snapshot = balanceSnapshot(
            id = "card",
            account = card,
            observedBalance = Money.cny(5_000L),
            asOf = createdAt.plusSeconds(120),
            recordedAt = createdAt.plusSeconds(180),
        )

        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.createBalanceSnapshot(snapshot, balanceSnapshotAudit(snapshot)).status,
        )

        val state = repository.observeState().first()
        assertEquals(Money.cny(-5_000L), state.accountBalances.single { it.account.id == card.id }.balance)
        val comparison = state.balanceSnapshotComparisons.single()
        assertEquals(Money.cny(5_000L), comparison.ledgerBalance)
        assertEquals(Money.cny(0L), comparison.difference)
    }

    @Test
    fun corruptBalanceSnapshotFailsClosedInsteadOfPublishingPartialState() = runBlocking {
        val createdAt = Instant.parse("2026-07-19T00:00:00Z")
        val bank = userAccount("corrupt-snapshot", AccountType.ASSET_BANK, createdAt)
        persistAccount(bank, createdAt)
        val snapshot = balanceSnapshot(
            id = "corrupt",
            account = bank,
            observedBalance = Money.cny(0L),
            asOf = createdAt,
            recordedAt = createdAt.plusSeconds(60),
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.createBalanceSnapshot(snapshot, balanceSnapshotAudit(snapshot)).status,
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE balance_snapshots SET sourceMode = 'REMOTE' WHERE id = ?",
            arrayOf(snapshot.id.value),
        )

        assertEquals(1L, database.ledgerDao().observeBalanceSnapshotIntegrityIssueCount().first())
        val failure = runCatching { repository.observeState().first() }.exceptionOrNull()
        assertTrue(failure is LocalDataIntegrityException)
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

    private fun userAccount(
        id: String,
        type: AccountType,
        createdAt: Instant,
    ) = LedgerAccount(
        id = AccountId("account:$id"),
        name = "TEST $id",
        normalizedName = "test $id",
        type = type,
        currency = CurrencyCode.CNY,
        isSystem = false,
        isArchived = false,
        createdAt = createdAt,
        creationCommandId = CommandId("create-account:$id"),
    )

    private fun reviewDraft(
        id: String,
        type: TransactionType,
        accountId: AccountId,
        occurredAt: Instant,
        amount: Money = Money.cny(2_500L),
    ) = ManualDraft(
        id = DraftId("draft:$id"),
        state = DraftState.EDITED,
        type = type,
        amount = amount,
        occurredAt = occurredAt,
        counterparty = "TEST $id",
        note = null,
        fundingAccountId = accountId,
        createdAt = occurredAt,
        updatedAt = occurredAt,
        creationCommandId = CommandId("create-draft:$id"),
        sourceMode = TransactionSourceMode.MANUAL,
    )

    private suspend fun persistAccount(account: LedgerAccount, occurredAt: Instant) {
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
                        occurredAt,
                    ),
                ),
            ).status,
        )
    }

    private suspend fun persistDraft(draft: ManualDraft) {
        val waitingDraft = draft.copy(
            state = DraftState.WAITING_USER,
            fundingAccountId = null,
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.createManualDraft(
                waitingDraft,
                audit(
                    draft.creationCommandId,
                    "draft-created",
                    AuditAction.MANUAL_DRAFT_CREATED,
                    "draft",
                    draft.id.value,
                    draft.createdAt,
                ),
            ).status,
        )
        val fundingAccountId = requireNotNull(draft.fundingAccountId)
        val selectionCommandId = CommandId("select-funding:${draft.id.value}")
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.selectFundingAccount(
                draft.id,
                fundingAccountId,
                audit(
                    selectionCommandId,
                    "funding-selected",
                    AuditAction.FUNDING_ACCOUNT_SELECTED,
                    "draft",
                    draft.id.value,
                    draft.updatedAt,
                ),
            ).status,
        )
    }

    private suspend fun persistTransaction(
        id: String,
        type: TransactionType,
        account: LedgerAccount,
        amount: Money,
        occurredAt: Instant,
        confirmedAt: Instant,
    ): PostedTransaction {
        val draft = reviewDraft(
            id = id,
            type = type,
            accountId = account.id,
            occurredAt = occurredAt,
            amount = amount,
        )
        persistDraft(draft)
        val commandId = CommandId("confirm:$id")
        val validated = (
            PostingFactory.manualDraft(
                draft = draft,
                fundingAccount = account,
                transactionId = TransactionId("transaction:$id"),
                confirmedAt = confirmedAt,
            ) as PostingBuildResult.Valid
            ).transaction
        val transaction = postedTransaction(
            validated = validated,
            draftId = draft.id,
            commandId = commandId,
            confirmedAt = confirmedAt,
            title = draft.counterparty,
        )
        assertEquals(
            RepositoryWriteStatus.APPLIED,
            repository.confirmDraft(
                draft.id,
                transaction,
                audit(
                    commandId,
                    "draft-confirmed",
                    AuditAction.DRAFT_CONFIRMED,
                    "draft",
                    draft.id.value,
                    confirmedAt,
                ),
            ).status,
        )
        return transaction
    }

    private fun balanceSnapshot(
        id: String,
        account: LedgerAccount,
        observedBalance: Money,
        asOf: Instant,
        recordedAt: Instant,
    ) = BalanceSnapshot(
        id = BalanceSnapshotId("balance-snapshot:$id"),
        accountId = account.id,
        observedBalance = observedBalance,
        asOf = asOf,
        recordedAt = recordedAt,
        note = "manual check",
        sourceMode = BalanceSnapshotSourceMode.MANUAL,
        creationCommandId = CommandId("create-balance-snapshot:$id"),
    )

    private fun balanceSnapshotAudit(snapshot: BalanceSnapshot) = audit(
        commandId = snapshot.creationCommandId,
        suffix = "balance-snapshot-recorded",
        action = AuditAction.BALANCE_SNAPSHOT_RECORDED,
        entityType = "balance_snapshot",
        entityId = snapshot.id.value,
        occurredAt = snapshot.recordedAt,
    )

    private fun postedTransaction(
        validated: ValidatedLedgerTransaction,
        draftId: DraftId?,
        commandId: CommandId,
        confirmedAt: Instant,
        title: String,
    ) = PostedTransaction(
        id = validated.id,
        draftId = draftId,
        type = validated.type,
        status = TransactionStatus.ACTIVE,
        sourceMode = TransactionSourceMode.MANUAL,
        occurredAt = validated.occurredAt,
        confirmedAt = confirmedAt,
        title = title,
        note = null,
        commandId = commandId,
        entries = validated.entries,
    )

    private fun refundResolution(
        transaction: PostedTransaction,
        draft: ManualDraft,
        original: PostedTransaction,
        createdAt: Instant,
    ) = ReconciliationResolution(
        kind = ReconciliationKind.REFUND,
        transaction = transaction,
        draftLinks = listOf(
            ReconciliationDraftLink(
                draft.id,
                ReconciliationDraftRole.REFUND_INBOUND,
            ),
        ),
        relations = listOf(
            TransactionRelation(
                id = TransactionRelationId("relation:${transaction.id.value}"),
                fromTransactionId = transaction.id,
                toTransactionId = original.id,
                type = TransactionRelationType.REFUNDS,
                decision = RelationDecision.USER_CONFIRMED,
                createdAt = createdAt,
            ),
        ),
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
