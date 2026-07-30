package dev.bill.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.bill.core.domain.AccountBalance
import dev.bill.core.domain.AuditAction
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.LedgerRepository
import dev.bill.core.domain.LedgerState
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.PostedTransaction
import dev.bill.core.domain.RepositoryWriteResult
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.core.domain.ReconciliationDraftRole
import dev.bill.core.domain.ReconciliationKind
import dev.bill.core.domain.ReconciliationResolution
import dev.bill.core.domain.RelationDecision
import dev.bill.core.domain.SystemAccountIds
import dev.bill.core.domain.TransactionRelationType
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.domain.TransactionStatus
import dev.bill.core.ledger.PostingBuildResult
import dev.bill.core.ledger.PostingFactory
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.EntryRole
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import dev.bill.core.model.allowsUserAccountCurrency
import dev.bill.core.model.isSupportedLedgerCurrency
import dev.bill.core.model.toLongExactCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class RoomLedgerRepository(
    private val database: BillDatabase,
    private val diagnostics: MappingDiagnostics = MappingDiagnostics.None,
    private val recentTransactionLimit: Int = 50,
) : LedgerRepository {
    private val dao: LedgerDao = database.ledgerDao()
    private val sourceDao: SourceDao = database.sourceDao()

    init {
        require(recentTransactionLimit > 0) { "Recent transaction limit must be positive" }
    }

    override fun observeState(): Flow<LedgerState> = combine(
        dao.observeAccountBalances(),
        dao.observePendingDrafts(),
        combine(
            dao.observeRecentTransactions(recentTransactionLimit),
            dao.observeActiveRefundTotals(),
        ) { transactions, refundTotals ->
            transactions to refundTotals
        },
        combine(
            dao.observeLedgerIntegrityIssueCount(),
            dao.observeReconciliationLinkIntegrityIssueCount(),
            dao.observeTransactionRelationIntegrityIssueCount(),
            dao.observeReconciliationTransactionIntegrityIssueCount(),
        ) { ledger, draftLinks, transactionRelations, reconciliationTransactions ->
            Math.addExact(
                Math.addExact(ledger, draftLinks),
                Math.addExact(transactionRelations, reconciliationTransactions),
            )
        },
        sourceDao.observeSourceIntegrityIssueCount(),
    ) { balanceRows, draftRelations, transactionAndRefunds, ledgerIssueCount, sourceIssueCount ->
        if (ledgerIssueCount > 0L || sourceIssueCount > 0L) {
            reportIntegrityFailure()
            throw LocalDataIntegrityException("active ledger")
        }
        val activeRefundTotals = linkedMapOf<TransactionId, Money>()
        transactionAndRefunds.second.forEach { row ->
            val id = try {
                TransactionId(row.originalTransactionId)
            } catch (_: IllegalArgumentException) {
                throw LocalDataIntegrityException("active refund total")
            }
            val currency = try {
                CurrencyCode(row.currency)
            } catch (_: IllegalArgumentException) {
                throw LocalDataIntegrityException("active refund total")
            }
            if (
                row.amountMinorUnits <= 0L ||
                activeRefundTotals.put(id, Money(row.amountMinorUnits, currency)) != null
            ) {
                throw LocalDataIntegrityException("active refund total")
            }
        }
        LedgerState(
            accountBalances = balanceRows.map { row ->
                mapAccountBalance(row)
            },
            pendingDrafts = draftRelations.map { relation ->
                mapDraft(relation)
            }.filter { draft ->
                draft.state == DraftState.WAITING_USER || draft.state == DraftState.EDITED
            },
            recentTransactions = transactionAndRefunds.first.map { relation ->
                mapTransaction(relation)
            },
            activeRefundTotals = activeRefundTotals,
        )
    }

    override suspend fun findAccount(id: AccountId): LedgerAccount? =
        dao.findAccount(id.value)?.let(::mapAccount)

    override suspend fun findDraft(id: DraftId): ManualDraft? =
        findDraftSafely(id.value)

    override suspend fun findTransaction(id: TransactionId): PostedTransaction? =
        dao.findTransactionWithEntries(id.value)?.let(::mapTransaction)

    override suspend fun createAccount(
        account: LedgerAccount,
        openingTransaction: PostedTransaction?,
        auditRecords: List<AuditRecord>,
    ): RepositoryWriteResult = safelyWrite(
        onConstraint = {
            if (dao.findAccountByNormalizedName(account.normalizedName) != null) {
                result(RepositoryWriteStatus.DUPLICATE_NAME)
            } else {
                collision()
            }
        },
    ) {
        val fingerprint = Fingerprints.createAccount(account, openingTransaction)
        when (
            val replay = inspectCommand(
                commandId = account.creationCommandId,
                operation = Operation.CREATE_ACCOUNT,
                targetId = account.id.value,
                fingerprint = fingerprint,
            )
        ) {
            is CommandInspection.Replay -> return@safelyWrite replay.result
            CommandInspection.Collision -> return@safelyWrite collision()
            CommandInspection.New -> Unit
        }

        if (
            account.isSystem ||
            account.isArchived ||
            !account.type.allowsUserAccountCurrency(account.currency)
        ) {
            return@safelyWrite invalidState()
        }
        if (dao.findAccount(account.id.value) != null) {
            return@safelyWrite collision()
        }
        if (dao.findAccountByNormalizedName(account.normalizedName) != null) {
            return@safelyWrite result(RepositoryWriteStatus.DUPLICATE_NAME)
        }
        if (systemAccountTemplates(0L).any { it.normalizedName == account.normalizedName }) {
            return@safelyWrite result(RepositoryWriteStatus.DUPLICATE_NAME)
        }
        if (!systemAccountsAreValidOrMissing()) {
            return@safelyWrite invalidState()
        }
        if (
            !auditsAreInsertable(
                audits = auditRecords,
                commandId = account.creationCommandId,
                expectedTargets = buildMap {
                    put(AuditAction.ACCOUNT_CREATED, AuditTarget("account", account.id.value))
                    if (openingTransaction != null) {
                        put(
                            AuditAction.OPENING_BALANCE_POSTED,
                            AuditTarget("transaction", openingTransaction.id.value),
                        )
                    }
                },
            )
        ) {
            return@safelyWrite collision()
        }
        if (openingTransaction != null) {
            if (
                openingTransaction.commandId != account.creationCommandId ||
                openingTransaction.draftId != null ||
                openingTransaction.type != dev.bill.core.model.TransactionType.ADJUSTMENT ||
                dao.findTransactionWithEntries(openingTransaction.id.value) != null
            ) {
                return@safelyWrite invalidState()
            }
            when (validateTransaction(openingTransaction, account)) {
                TransactionValidation.VALID -> Unit
                TransactionValidation.ACCOUNT_NOT_FOUND -> return@safelyWrite accountNotFound()
                TransactionValidation.INVALID -> return@safelyWrite invalidState()
            }
            if (!RepositoryTransactionSemantics.openingMatchesAccount(openingTransaction, account)) {
                return@safelyWrite invalidState()
            }
        }

        claimCommand(
            commandId = account.creationCommandId,
            operation = Operation.CREATE_ACCOUNT,
            targetId = account.id.value,
            resultEntityId = account.id.value,
            fingerprint = fingerprint,
            appliedAtEpochMillis = auditRecords.minOf { it.occurredAt.toEpochMilli() },
        )
        insertSystemAccountsIfMissing(account.createdAt.toEpochMilli())
        dao.insertAccount(LedgerEntityMapper.accountToEntity(account))
        if (openingTransaction != null) {
            insertTransaction(openingTransaction)
        }
        dao.insertAuditEvents(auditRecords.map(LedgerEntityMapper::auditToEntity))
        applied(account.id.value)
    }

    override suspend fun createManualDraft(
        draft: ManualDraft,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult = safelyWrite {
        val fingerprint = Fingerprints.createDraft(draft)
        when (
            val replay = inspectCommand(
                commandId = draft.creationCommandId,
                operation = Operation.CREATE_DRAFT,
                targetId = draft.id.value,
                fingerprint = fingerprint,
            )
        ) {
            is CommandInspection.Replay -> return@safelyWrite replay.result
            CommandInspection.Collision -> return@safelyWrite collision()
            CommandInspection.New -> Unit
        }

        if (
            draft.state != DraftState.WAITING_USER ||
            draft.sourceMode != TransactionSourceMode.MANUAL ||
            draft.fundingAccountId != null ||
            !draft.amount.currency.isSupportedLedgerCurrency() ||
            dao.findDraft(draft.id.value) != null
        ) {
            return@safelyWrite invalidState()
        }
        if (
            !auditsAreInsertable(
                audits = listOf(auditRecord),
                commandId = draft.creationCommandId,
                expectedTargets = mapOf(
                    AuditAction.MANUAL_DRAFT_CREATED to AuditTarget("draft", draft.id.value),
                ),
            )
        ) {
            return@safelyWrite collision()
        }

        claimCommand(
            commandId = draft.creationCommandId,
            operation = Operation.CREATE_DRAFT,
            targetId = draft.id.value,
            resultEntityId = draft.id.value,
            fingerprint = fingerprint,
            appliedAtEpochMillis = auditRecord.occurredAt.toEpochMilli(),
        )
        dao.insertDraft(LedgerEntityMapper.draftToEntity(draft))
        dao.insertAuditEvents(listOf(LedgerEntityMapper.auditToEntity(auditRecord)))
        applied(draft.id.value)
    }

    override suspend fun selectFundingAccount(
        draftId: DraftId,
        accountId: AccountId,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult = safelyWrite {
        val fingerprint = Fingerprints.selectFundingAccount(draftId, accountId)
        when (
            val replay = inspectCommand(
                commandId = auditRecord.commandId,
                operation = Operation.SELECT_FUNDING_ACCOUNT,
                targetId = draftId.value,
                fingerprint = fingerprint,
            )
        ) {
            is CommandInspection.Replay -> return@safelyWrite replay.result
            CommandInspection.Collision -> return@safelyWrite collision()
            CommandInspection.New -> Unit
        }

        val draft = findDraftSafely(draftId.value)
            ?: return@safelyWrite notFound()
        if (draft.state != DraftState.WAITING_USER && draft.state != DraftState.EDITED) {
            return@safelyWrite invalidState()
        }
        val account = dao.findAccount(accountId.value)?.let(::mapAccount)
            ?: return@safelyWrite accountNotFound()
        if (account.isSystem || account.isArchived || !account.canFund(draft)) {
            return@safelyWrite invalidState()
        }
        if (account.currency != draft.amount.currency) {
            return@safelyWrite invalidState()
        }
        if (
            !auditsAreInsertable(
                audits = listOf(auditRecord),
                commandId = auditRecord.commandId,
                expectedTargets = mapOf(
                    AuditAction.FUNDING_ACCOUNT_SELECTED to AuditTarget("draft", draftId.value),
                ),
            )
        ) {
            return@safelyWrite collision()
        }

        claimCommand(
            commandId = auditRecord.commandId,
            operation = Operation.SELECT_FUNDING_ACCOUNT,
            targetId = draftId.value,
            resultEntityId = draftId.value,
            fingerprint = fingerprint,
            appliedAtEpochMillis = auditRecord.occurredAt.toEpochMilli(),
        )
        if (
            dao.selectFundingAccount(
                draftId = draftId.value,
                accountId = accountId.value,
                updatedAtEpochMillis = auditRecord.occurredAt.toEpochMilli(),
            ) != 1
        ) {
            throw ConcurrentStateChangeException()
        }
        dao.insertAuditEvents(listOf(LedgerEntityMapper.auditToEntity(auditRecord)))
        applied(draftId.value)
    }

    override suspend fun confirmDraft(
        draftId: DraftId,
        transaction: PostedTransaction,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult = safelyWrite {
        val fingerprint = Fingerprints.confirmDraft(draftId, transaction)
        when (
            val replay = inspectCommand(
                commandId = transaction.commandId,
                operation = Operation.CONFIRM_DRAFT,
                targetId = draftId.value,
                fingerprint = fingerprint,
            )
        ) {
            is CommandInspection.Replay -> return@safelyWrite replay.result
            CommandInspection.Collision -> return@safelyWrite collision()
            CommandInspection.New -> Unit
        }

        val draft = findDraftSafely(draftId.value)
            ?: return@safelyWrite notFound()
        if (
            draft.state != DraftState.WAITING_USER && draft.state != DraftState.EDITED ||
            transaction.draftId != draftId ||
            transaction.commandId != auditRecord.commandId ||
            dao.findTransactionWithEntries(transaction.id.value) != null ||
            dao.countActiveTransactionsForDraft(draftId.value) != 0
        ) {
            return@safelyWrite invalidState()
        }
        val fundingAccountId = draft.fundingAccountId ?: return@safelyWrite invalidState()
        if (!systemAccountsAreValidOrMissing()) {
            return@safelyWrite invalidState()
        }
        val fundingAccount = dao.findAccount(fundingAccountId.value)?.let(::mapAccount)
            ?: return@safelyWrite accountNotFound()
        if (!fundingAccount.canFund(draft)) {
            return@safelyWrite invalidState()
        }
        when (validateTransaction(transaction, additionalAccount = null)) {
            TransactionValidation.VALID -> Unit
            TransactionValidation.ACCOUNT_NOT_FOUND -> return@safelyWrite accountNotFound()
            TransactionValidation.INVALID -> return@safelyWrite invalidState()
        }
        if (!RepositoryTransactionSemantics.matchesDraft(transaction, draft)) {
            return@safelyWrite invalidState()
        }
        if (
            !auditsAreInsertable(
                audits = listOf(auditRecord),
                commandId = transaction.commandId,
                expectedTargets = mapOf(
                    AuditAction.DRAFT_CONFIRMED to AuditTarget("draft", draftId.value),
                ),
            )
        ) {
            return@safelyWrite collision()
        }

        claimCommand(
            commandId = transaction.commandId,
            operation = Operation.CONFIRM_DRAFT,
            targetId = draftId.value,
            resultEntityId = transaction.id.value,
            fingerprint = fingerprint,
            appliedAtEpochMillis = auditRecord.occurredAt.toEpochMilli(),
        )
        insertSystemAccountsIfMissing(transaction.confirmedAt.toEpochMilli())
        insertTransaction(transaction)
        if (dao.markDraftConfirmed(draftId.value, transaction.confirmedAt.toEpochMilli()) != 1) {
            throw ConcurrentStateChangeException()
        }
        dao.insertAuditEvents(listOf(LedgerEntityMapper.auditToEntity(auditRecord)))
        applied(transaction.id.value)
    }

    override suspend fun dismissDraft(
        draftId: DraftId,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult = safelyWrite {
        val fingerprint = Fingerprints.targetOnly(Operation.DISMISS_DRAFT, draftId.value)
        when (
            val replay = inspectCommand(
                commandId = auditRecord.commandId,
                operation = Operation.DISMISS_DRAFT,
                targetId = draftId.value,
                fingerprint = fingerprint,
            )
        ) {
            is CommandInspection.Replay -> return@safelyWrite replay.result
            CommandInspection.Collision -> return@safelyWrite collision()
            CommandInspection.New -> Unit
        }
        val draft = findDraftSafely(draftId.value)
            ?: return@safelyWrite notFound()
        if (draft.state != DraftState.WAITING_USER && draft.state != DraftState.EDITED) {
            return@safelyWrite invalidState()
        }
        if (
            !auditsAreInsertable(
                listOf(auditRecord),
                auditRecord.commandId,
                mapOf(AuditAction.DRAFT_DISMISSED to AuditTarget("draft", draftId.value)),
            )
        ) {
            return@safelyWrite collision()
        }

        claimCommand(
            auditRecord.commandId,
            Operation.DISMISS_DRAFT,
            draftId.value,
            draftId.value,
            fingerprint,
            auditRecord.occurredAt.toEpochMilli(),
        )
        if (dao.dismissDraft(draftId.value, auditRecord.occurredAt.toEpochMilli()) != 1) {
            throw ConcurrentStateChangeException()
        }
        dao.insertAuditEvents(listOf(LedgerEntityMapper.auditToEntity(auditRecord)))
        applied(draftId.value)
    }

    override suspend fun activeRefundTotal(
        originalTransactionId: TransactionId,
    ): Money? = database.withTransaction {
        activeRefundTotalLocked(originalTransactionId)
    }

    override suspend fun resolveReconciliation(
        resolution: ReconciliationResolution,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult = safelyWrite {
        val transaction = resolution.transaction
        val fingerprint = Fingerprints.reconciliation(resolution)
        when (
            val replay = inspectCommand(
                commandId = transaction.commandId,
                operation = Operation.RESOLVE_RECONCILIATION,
                targetId = transaction.id.value,
                fingerprint = fingerprint,
            )
        ) {
            is CommandInspection.Replay -> return@safelyWrite replay.result
            CommandInspection.Collision -> return@safelyWrite collision()
            CommandInspection.New -> Unit
        }

        if (
            transaction.commandId != auditRecord.commandId ||
            transaction.draftId != null ||
            dao.findTransactionWithEntries(transaction.id.value) != null ||
            resolution.relations.any { it.createdAt != auditRecord.occurredAt } ||
            resolution.relations.map { it.id }.distinct().size != resolution.relations.size
        ) {
            return@safelyWrite invalidState()
        }
        val drafts = resolution.draftLinks.associate { link ->
            val draft = findDraftSafely(link.draftId.value)
                ?: return@safelyWrite notFound()
            if (
                draft.state != DraftState.WAITING_USER &&
                draft.state != DraftState.EDITED
            ) {
                return@safelyWrite invalidState()
            }
            if (dao.countActiveReconciliationsForDraft(link.draftId.value) != 0) {
                return@safelyWrite invalidState()
            }
            link.role to draft
        }
        if (drafts.size != resolution.draftLinks.size) {
            return@safelyWrite invalidState()
        }
        val expectedSourceMode = if (
            drafts.values.any { it.sourceMode == TransactionSourceMode.EXTERNAL }
        ) {
            TransactionSourceMode.EXTERNAL
        } else {
            TransactionSourceMode.MANUAL
        }
        if (
            transaction.sourceMode != expectedSourceMode ||
            transaction.occurredAt > transaction.confirmedAt ||
            transaction.confirmedAt > auditRecord.occurredAt
        ) {
            return@safelyWrite invalidState()
        }
        when (validateTransaction(transaction, additionalAccount = null)) {
            TransactionValidation.VALID -> Unit
            TransactionValidation.ACCOUNT_NOT_FOUND -> return@safelyWrite accountNotFound()
            TransactionValidation.INVALID -> return@safelyWrite invalidState()
        }
        if (!reconciliationMatches(resolution, drafts)) {
            return@safelyWrite invalidState()
        }
        if (
            !auditsAreInsertable(
                audits = listOf(auditRecord),
                commandId = transaction.commandId,
                expectedTargets = mapOf(
                    AuditAction.RECONCILIATION_CONFIRMED to
                        AuditTarget("transaction", transaction.id.value),
                ),
            )
        ) {
            return@safelyWrite collision()
        }

        claimCommand(
            commandId = transaction.commandId,
            operation = Operation.RESOLVE_RECONCILIATION,
            targetId = transaction.id.value,
            resultEntityId = transaction.id.value,
            fingerprint = fingerprint,
            appliedAtEpochMillis = auditRecord.occurredAt.toEpochMilli(),
        )
        insertTransaction(transaction)
        dao.insertReconciliationDraftLinks(
            resolution.draftLinks.map { link ->
                ReconciliationDraftLinkEntity(
                    transactionId = transaction.id.value,
                    draftId = link.draftId.value,
                    role = link.role.name,
                    linkedAtEpochMillis = auditRecord.occurredAt.toEpochMilli(),
                )
            },
        )
        if (resolution.relations.isNotEmpty()) {
            dao.insertTransactionRelations(
                resolution.relations.map { relation ->
                    TransactionRelationEntity(
                        id = relation.id.value,
                        fromTransactionId = relation.fromTransactionId.value,
                        toTransactionId = relation.toTransactionId.value,
                        type = relation.type.name,
                        decision = relation.decision.name,
                        createdAtEpochMillis = relation.createdAt.toEpochMilli(),
                    )
                },
            )
        }
        val draftIds = resolution.draftLinks.map { it.draftId.value }
        if (
            dao.markDraftsLinked(
                draftIds = draftIds,
                updatedAtEpochMillis = auditRecord.occurredAt.toEpochMilli(),
            ) != draftIds.size
        ) {
            throw ConcurrentStateChangeException()
        }
        dao.insertAuditEvents(listOf(LedgerEntityMapper.auditToEntity(auditRecord)))
        applied(transaction.id.value)
    }

    override suspend fun voidTransaction(
        transactionId: TransactionId,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult = safelyWrite {
        val fingerprint = Fingerprints.targetOnly(Operation.VOID_TRANSACTION, transactionId.value)
        when (
            val replay = inspectCommand(
                commandId = auditRecord.commandId,
                operation = Operation.VOID_TRANSACTION,
                targetId = transactionId.value,
                fingerprint = fingerprint,
            )
        ) {
            is CommandInspection.Replay -> return@safelyWrite replay.result
            CommandInspection.Collision -> return@safelyWrite collision()
            CommandInspection.New -> Unit
        }

        val relation = dao.findTransactionWithEntries(transactionId.value)
            ?: return@safelyWrite notFound()
        val transaction = mapTransaction(relation)
        val reconciliationLinks = dao.findReconciliationDraftLinks(transactionId.value)
        val directDraft = transaction.draftId?.let { draftId ->
            findDraftSafely(draftId.value) ?: return@safelyWrite invalidState()
        }
        val linkedDrafts = reconciliationLinks.map { link ->
            findDraftSafely(link.draftId) ?: return@safelyWrite invalidState()
        }
        if (transaction.status != TransactionStatus.ACTIVE) {
            return@safelyWrite invalidState()
        }
        if (dao.countActiveRefundRelationsTo(transactionId.value) != 0) {
            return@safelyWrite invalidState()
        }
        if (
            directDraft != null &&
            (directDraft.state != DraftState.CONFIRMED || reconciliationLinks.isNotEmpty())
        ) {
            return@safelyWrite invalidState()
        }
        if (
            directDraft == null &&
            (
                linkedDrafts.isEmpty() ||
                    linkedDrafts.any { it.state != DraftState.LINKED } ||
                    linkedDrafts.size != reconciliationLinks.size
                )
        ) {
            return@safelyWrite invalidState()
        }
        if (
            !auditsAreInsertable(
                listOf(auditRecord),
                auditRecord.commandId,
                mapOf(
                    AuditAction.TRANSACTION_VOIDED to
                        AuditTarget("transaction", transactionId.value),
                ),
            )
        ) {
            return@safelyWrite collision()
        }

        claimCommand(
            auditRecord.commandId,
            Operation.VOID_TRANSACTION,
            transactionId.value,
            transactionId.value,
            fingerprint,
            auditRecord.occurredAt.toEpochMilli(),
        )
        if (dao.markTransactionVoided(transactionId.value) != 1) {
            throw ConcurrentStateChangeException()
        }
        if (directDraft != null) {
            if (
                dao.restoreDraftForReview(
                    directDraft.id.value,
                    auditRecord.occurredAt.toEpochMilli(),
                ) != 1
            ) {
                throw ConcurrentStateChangeException()
            }
        } else {
            val linkedDraftIds = linkedDrafts.map { it.id.value }
            if (
                dao.restoreLinkedDraftsForReview(
                    linkedDraftIds,
                    auditRecord.occurredAt.toEpochMilli(),
                ) != linkedDraftIds.size
            ) {
                throw ConcurrentStateChangeException()
            }
        }
        dao.insertAuditEvents(listOf(LedgerEntityMapper.auditToEntity(auditRecord)))
        applied(transactionId.value)
    }

    private suspend fun activeRefundTotalLocked(
        originalTransactionId: TransactionId,
    ): Money? {
        val original = dao.findTransactionWithEntries(originalTransactionId.value)
            ?.let(::mapTransaction)
            ?: return null
        if (original.status != TransactionStatus.ACTIVE || original.type != TransactionType.EXPENSE) {
            return null
        }
        val expenseCurrency = original.entries.singleOrNull {
            it.role == EntryRole.EXPENSE && it.amount.minorUnits > 0L
        }?.amount?.currency ?: return null
        val total = try {
            dao.findActiveRefundExpenseLegs(originalTransactionId.value)
            .fold(BigInteger.ZERO) { sum, row ->
                if (
                    row.currency != expenseCurrency.value ||
                    row.amountMinorUnits >= 0L
                ) {
                    throw LocalDataIntegrityException("active refund relation")
                }
                sum + BigInteger.valueOf(row.amountMinorUnits).negate()
            }
            .toLongExactCompat()
        } catch (_: ArithmeticException) {
            throw LocalDataIntegrityException("active refund relation")
        }
        return Money(total, expenseCurrency)
    }

    private suspend fun reconciliationMatches(
        resolution: ReconciliationResolution,
        drafts: Map<ReconciliationDraftRole, ManualDraft>,
    ): Boolean {
        val transaction = resolution.transaction
        val expected = when (resolution.kind) {
            ReconciliationKind.TRANSFER_PAIR -> {
                val outbound = drafts[ReconciliationDraftRole.TRANSFER_OUTBOUND] ?: return false
                val inbound = drafts[ReconciliationDraftRole.TRANSFER_INBOUND] ?: return false
                val sourceId = outbound.fundingAccountId ?: return false
                val destinationId = inbound.fundingAccountId ?: return false
                val source = dao.findAccount(sourceId.value)?.let(::mapAccount) ?: return false
                val destination = dao.findAccount(destinationId.value)?.let(::mapAccount)
                    ?: return false
                PostingFactory.transferPair(
                    outboundDraft = outbound,
                    inboundDraft = inbound,
                    sourceAccount = source,
                    destinationAccount = destination,
                    transactionId = transaction.id,
                    confirmedAt = transaction.confirmedAt,
                )
            }

            ReconciliationKind.LIABILITY_REPAYMENT -> {
                val outbound = drafts[ReconciliationDraftRole.REPAYMENT_OUTBOUND] ?: return false
                val sourceId = outbound.fundingAccountId ?: return false
                val source = dao.findAccount(sourceId.value)?.let(::mapAccount) ?: return false
                val liabilityEntry = transaction.entries.singleOrNull {
                    it.role == EntryRole.LIABILITY && it.amount.minorUnits > 0L
                } ?: return false
                val liability = dao.findAccount(liabilityEntry.accountId.value)?.let(::mapAccount)
                    ?: return false
                PostingFactory.liabilityRepayment(
                    outboundDraft = outbound,
                    sourceAccount = source,
                    liabilityAccount = liability,
                    transactionId = transaction.id,
                    confirmedAt = transaction.confirmedAt,
                )
            }

            ReconciliationKind.REFUND -> {
                val inbound = drafts[ReconciliationDraftRole.REFUND_INBOUND] ?: return false
                val relation = resolution.relations.singleOrNull() ?: return false
                if (
                    relation.type != TransactionRelationType.REFUNDS ||
                    relation.decision != RelationDecision.USER_CONFIRMED
                ) {
                    return false
                }
                val original = dao.findTransactionWithEntries(relation.toTransactionId.value)
                    ?.let(::mapTransaction)
                    ?: return false
                val destinationEntry = transaction.entries.singleOrNull {
                    it.amount.minorUnits > 0L &&
                        it.role in setOf(EntryRole.FUNDING, EntryRole.LIABILITY)
                } ?: return false
                val destination = dao.findAccount(destinationEntry.accountId.value)
                    ?.let(::mapAccount)
                    ?: return false
                val alreadyRefunded = activeRefundTotalLocked(original.id)
                    ?: return false
                PostingFactory.refund(
                    inboundDraft = inbound,
                    destinationAccount = destination,
                    originalExpense = original,
                    alreadyRefundedMinorUnits = alreadyRefunded.minorUnits,
                    transactionId = transaction.id,
                    confirmedAt = transaction.confirmedAt,
                )
            }
        }
        val validated = (expected as? PostingBuildResult.Valid)?.transaction ?: return false
        return validated.id == transaction.id &&
            validated.type == transaction.type &&
            validated.occurredAt == transaction.occurredAt &&
            validated.entries == transaction.entries
    }

    private suspend fun inspectCommand(
        commandId: CommandId,
        operation: String,
        targetId: String,
        fingerprint: String,
    ): CommandInspection {
        val existing = dao.findCommandReceipt(commandId.value) ?: return CommandInspection.New
        return if (
            existing.operation == operation &&
            existing.targetId == targetId &&
            existing.payloadFingerprint == fingerprint
        ) {
            CommandInspection.Replay(
                RepositoryWriteResult(
                    status = RepositoryWriteStatus.ALREADY_APPLIED,
                    entityId = existing.resultEntityId,
                ),
            )
        } else {
            CommandInspection.Collision
        }
    }

    private suspend fun claimCommand(
        commandId: CommandId,
        operation: String,
        targetId: String,
        resultEntityId: String,
        fingerprint: String,
        appliedAtEpochMillis: Long,
    ) {
        dao.insertCommandReceipt(
            CommandReceiptEntity(
                commandId = commandId.value,
                operation = operation,
                targetId = targetId,
                resultEntityId = resultEntityId,
                payloadFingerprint = fingerprint,
                appliedAtEpochMillis = appliedAtEpochMillis,
            ),
        )
    }

    private suspend fun auditsAreInsertable(
        audits: List<AuditRecord>,
        commandId: CommandId,
        expectedTargets: Map<AuditAction, AuditTarget>,
    ): Boolean {
        if (
            audits.size != expectedTargets.size ||
            audits.any { it.commandId != commandId } ||
            !audits.map { it.id.value }.allUnique() ||
            audits.any { audit ->
                expectedTargets[audit.action] != AuditTarget(audit.entityType, audit.entityId)
            } ||
            audits.map { it.action }.toSet() != expectedTargets.keys
        ) {
            return false
        }
        return dao.countAuditEvents(audits.map { it.id.value }) == 0
    }

    private suspend fun validateTransaction(
        transaction: PostedTransaction,
        additionalAccount: LedgerAccount?,
    ): TransactionValidation {
        if (
            transaction.status != TransactionStatus.ACTIVE ||
            transaction.entries.size < 2 ||
            transaction.entries.any { it.amount.minorUnits == 0L } ||
            transaction.entries.map { it.amount.currency }.distinct().size != 1
        ) {
            return TransactionValidation.INVALID
        }
        val differences = transaction.entries
            .groupBy { it.amount.currency }
            .mapValues { (_, entries) ->
                entries.fold(BigInteger.ZERO) { sum, entry ->
                    sum + BigInteger.valueOf(entry.amount.minorUnits)
                }
            }
        if (differences.values.any { it != BigInteger.ZERO }) {
            return TransactionValidation.INVALID
        }

        val accountIds = transaction.entries.map { it.accountId.value }.distinct()
        val persistedAccounts = dao.findAccounts(accountIds).associateBy(AccountEntity::id)
        for (entry in transaction.entries) {
            val account = when {
                additionalAccount?.id == entry.accountId -> additionalAccount
                entry.accountId.value in SystemAccountIdValues -> {
                    val persisted = persistedAccounts[entry.accountId.value]
                    if (persisted == null) {
                        systemAccountDomain(entry.accountId.value, transaction.confirmedAt.toEpochMilli())
                    } else {
                        mapAccount(persisted)
                    }
                }
                else -> persistedAccounts[entry.accountId.value]?.let(::mapAccount)
                    ?: return TransactionValidation.ACCOUNT_NOT_FOUND
            }
            if (account.currency != entry.amount.currency) {
                return TransactionValidation.INVALID
            }
        }
        return TransactionValidation.VALID
    }

    private fun LedgerAccount.canFund(draft: ManualDraft): Boolean {
        if (
            isSystem ||
            isArchived ||
            currency != draft.amount.currency ||
            !type.allowsUserAccountCurrency(currency)
        ) return false
        return when (draft.type) {
            TransactionType.EXPENSE -> type == AccountType.ASSET_CASH ||
                type == AccountType.ASSET_BANK ||
                type == AccountType.ASSET_EWALLET_BALANCE ||
                type == AccountType.LIABILITY_CC

            TransactionType.INCOME -> type == AccountType.ASSET_CASH ||
                type == AccountType.ASSET_BANK ||
                type == AccountType.ASSET_EWALLET_BALANCE

            else -> false
        }
    }

    private suspend fun systemAccountsAreValidOrMissing(): Boolean {
        val templates = systemAccountTemplates(0L)
        for (template in templates) {
            val byId = dao.findAccount(template.id)
            if (byId != null && !byId.matchesSystemTemplate(template)) return false
            val byName = dao.findAccountByNormalizedName(template.normalizedName)
            if (byName != null && byName.id != template.id) return false
        }
        return true
    }

    private suspend fun insertSystemAccountsIfMissing(createdAtEpochMillis: Long) {
        dao.insertAccountsIfAbsent(systemAccountTemplates(createdAtEpochMillis))
    }

    private suspend fun insertTransaction(transaction: PostedTransaction) {
        dao.insertTransaction(LedgerEntityMapper.transactionToEntity(transaction))
        dao.insertEntries(LedgerEntityMapper.entriesToEntities(transaction))
    }

    private fun mapAccount(entity: AccountEntity): LedgerAccount =
        LedgerEntityMapper.accountToDomain(entity, diagnostics)
            ?: throw LocalDataIntegrityException("account")

    private fun mapAccountBalance(row: AccountBalanceRow): AccountBalance =
        LedgerEntityMapper.accountBalanceToDomain(row, diagnostics)
            ?: throw LocalDataIntegrityException("account balance")

    private suspend fun findDraftSafely(id: String): ManualDraft? {
        val relation = dao.findDraft(id) ?: return null
        if (sourceDao.countDraftSourceLinkIssues(id) > 0L) {
            reportIntegrityFailure()
            throw LocalDataIntegrityException("draft source evidence")
        }
        return mapDraft(relation)
    }

    private fun mapDraft(relation: DraftWithSourceEvidence): ManualDraft {
        val sourceMode = when (relation.sourceEvidence.size) {
            0 -> TransactionSourceMode.MANUAL
            1 -> TransactionSourceMode.EXTERNAL
            else -> throw LocalDataIntegrityException("draft source evidence")
        }
        if (relation.sourceEvidence.any { it.draftId != relation.draft.id }) {
            throw LocalDataIntegrityException("draft source evidence")
        }
        return LedgerEntityMapper.draftToDomain(relation.draft, diagnostics, sourceMode)
            ?: throw LocalDataIntegrityException("draft")
    }

    private fun mapTransaction(relation: TransactionWithEntries): PostedTransaction =
        LedgerEntityMapper.transactionToDomain(relation, diagnostics)
            ?: throw LocalDataIntegrityException("transaction")

    private fun reportIntegrityFailure() {
        try {
            diagnostics.report(
                MappingIssue(
                    entity = "ledger_transactions",
                    field = "record",
                    kind = MappingIssueKind.INVALID_DOMAIN_RECORD,
                ),
            )
        } catch (_: RuntimeException) {
            // Diagnostics cannot replace the deterministic integrity failure below.
        }
    }

    private suspend fun safelyWrite(
        onConstraint: suspend () -> RepositoryWriteResult = { collision() },
        operation: suspend () -> RepositoryWriteResult,
    ): RepositoryWriteResult = try {
        database.withTransaction { operation() }
    } catch (_: SQLiteConstraintException) {
        onConstraint()
    } catch (_: ConcurrentStateChangeException) {
        invalidState()
    }
}

internal class LocalDataIntegrityException(entity: String) :
    IllegalStateException("Local ledger data failed validation: $entity")

internal object RepositoryTransactionSemantics {
    fun openingMatchesAccount(
        transaction: PostedTransaction,
        account: LedgerAccount,
    ): Boolean {
        if (
            transaction.sourceMode != TransactionSourceMode.MANUAL ||
            transaction.occurredAt > transaction.confirmedAt ||
            transaction.entries.size != 2
        ) {
            return false
        }
        val accountEntry = transaction.entries.singleOrNull { it.accountId == account.id }
            ?: return false
        val equityEntry = transaction.entries.singleOrNull {
            it.accountId == SystemAccountIds.openingEquity(account.currency)
        } ?: return false
        if (
            accountEntry.amount.currency != account.currency ||
            equityEntry.amount.currency != account.currency ||
            equityEntry.role != EntryRole.EQUITY ||
            equityEntry.amount.minorUnits != -accountEntry.amount.minorUnits
        ) {
            return false
        }
        return when (account.type) {
            AccountType.ASSET_CASH,
            AccountType.ASSET_BANK,
            AccountType.ASSET_EWALLET_BALANCE,
            -> accountEntry.role == EntryRole.ASSET && accountEntry.amount.minorUnits > 0L

            AccountType.LIABILITY_CC ->
                accountEntry.role == EntryRole.LIABILITY && accountEntry.amount.minorUnits < 0L

            else -> false
        }
    }

    fun matchesDraft(
        transaction: PostedTransaction,
        draft: ManualDraft,
    ): Boolean {
        val fundingAccountId = draft.fundingAccountId ?: return false
        if (
            transaction.type != draft.type ||
            transaction.sourceMode != draft.sourceMode ||
            transaction.title != draft.counterparty ||
            transaction.note != draft.note ||
            transaction.occurredAt != draft.occurredAt ||
            transaction.occurredAt > transaction.confirmedAt ||
            transaction.entries.size != 2
        ) {
            return false
        }
        val nonSystemAccountIds = transaction.entries
            .map { it.accountId }
            .filterNot { it.value in SystemAccountIdValues }
            .toSet()
        if (nonSystemAccountIds != setOf(fundingAccountId)) return false

        val expectedAmount = draft.amount.minorUnits
        return when (draft.type) {
            TransactionType.EXPENSE -> transaction.entries.any { entry ->
                entry.accountId == SystemAccountIds.uncategorizedExpense(draft.amount.currency) &&
                    entry.role == EntryRole.EXPENSE &&
                    entry.amount == draft.amount
            } && transaction.entries.any { entry ->
                entry.accountId == fundingAccountId &&
                    entry.role in setOf(EntryRole.FUNDING, EntryRole.LIABILITY) &&
                    entry.amount.currency == draft.amount.currency &&
                    entry.amount.minorUnits == -expectedAmount
            }

            TransactionType.INCOME -> transaction.entries.any { entry ->
                entry.accountId == SystemAccountIds.uncategorizedIncome(draft.amount.currency) &&
                    entry.role == EntryRole.INCOME &&
                    entry.amount.currency == draft.amount.currency &&
                    entry.amount.minorUnits == -expectedAmount
            } && transaction.entries.any { entry ->
                entry.accountId == fundingAccountId &&
                    entry.role == EntryRole.FUNDING &&
                    entry.amount == draft.amount
            }

            else -> false
        }
    }
}

private sealed interface CommandInspection {
    data object New : CommandInspection
    data object Collision : CommandInspection
    data class Replay(val result: RepositoryWriteResult) : CommandInspection
}

private enum class TransactionValidation {
    VALID,
    ACCOUNT_NOT_FOUND,
    INVALID,
}

private class ConcurrentStateChangeException : RuntimeException()

private data class AuditTarget(val entityType: String, val entityId: String)

private object Operation {
    const val CREATE_ACCOUNT = "CREATE_ACCOUNT"
    const val CREATE_DRAFT = "CREATE_DRAFT"
    const val SELECT_FUNDING_ACCOUNT = "SELECT_FUNDING_ACCOUNT"
    const val CONFIRM_DRAFT = "CONFIRM_DRAFT"
    const val DISMISS_DRAFT = "DISMISS_DRAFT"
    const val VOID_TRANSACTION = "VOID_TRANSACTION"
    const val RESOLVE_RECONCILIATION = "RESOLVE_RECONCILIATION"
}

internal object Fingerprints {
    // Lifecycle timestamps are generated again when a lost response is retried. They are
    // deliberately excluded so a stable command ID describes stable business intent.
    fun createAccount(account: LedgerAccount, opening: PostedTransaction?): String =
        CanonicalFingerprint()
            .add(Operation.CREATE_ACCOUNT)
            .addAccount(account)
            .add(opening != null)
            .also { fingerprint -> if (opening != null) fingerprint.addTransaction(opening) }
            .finish()

    fun createDraft(draft: ManualDraft): String = CanonicalFingerprint()
        .add(Operation.CREATE_DRAFT)
        .add(draft.id.value)
        .add(draft.state.name)
        .add(draft.sourceMode.name)
        .add(draft.type.name)
        .add(draft.amount.minorUnits)
        .add(draft.amount.currency.value)
        .add(draft.counterparty)
        .addNullable(draft.note)
        .addNullable(draft.fundingAccountId?.value)
        .finish()

    fun selectFundingAccount(draftId: DraftId, accountId: AccountId): String =
        CanonicalFingerprint()
            .add(Operation.SELECT_FUNDING_ACCOUNT)
            .add(draftId.value)
            .add(accountId.value)
            .finish()

    fun confirmDraft(draftId: DraftId, transaction: PostedTransaction): String =
        CanonicalFingerprint()
            .add(Operation.CONFIRM_DRAFT)
            .add(draftId.value)
            .addTransaction(transaction)
            .finish()

    fun reconciliation(resolution: ReconciliationResolution): String =
        CanonicalFingerprint()
            .add(Operation.RESOLVE_RECONCILIATION)
            .add(resolution.kind.name)
            .addTransaction(resolution.transaction)
            .add(resolution.draftLinks.size)
            .also { fingerprint ->
                resolution.draftLinks
                    .sortedWith(
                        compareBy<dev.bill.core.domain.ReconciliationDraftLink> {
                            it.role.name
                        }.thenBy { it.draftId.value },
                    )
                    .forEach { link ->
                        fingerprint.add(link.role.name).add(link.draftId.value)
                    }
            }
            .add(resolution.relations.size)
            .also { fingerprint ->
                resolution.relations.sortedBy { it.id.value }.forEach { relation ->
                    fingerprint
                        .add(relation.id.value)
                        .add(relation.fromTransactionId.value)
                        .add(relation.toTransactionId.value)
                        .add(relation.type.name)
                        .add(relation.decision.name)
                }
            }
            .finish()

    fun targetOnly(operation: String, targetId: String): String = CanonicalFingerprint()
        .add(operation)
        .add(targetId)
        .finish()

    private fun CanonicalFingerprint.addAccount(account: LedgerAccount): CanonicalFingerprint =
        add(account.id.value)
            .add(account.name)
            .add(account.normalizedName)
            .add(account.type.name)
            .add(account.currency.value)
            .add(account.isSystem)
            .add(account.isArchived)

    private fun CanonicalFingerprint.addTransaction(
        transaction: PostedTransaction,
    ): CanonicalFingerprint {
        add(transaction.id.value)
            .addNullable(transaction.draftId?.value)
            .add(transaction.type.name)
            .add(transaction.status.name)
            .add(transaction.sourceMode.name)
            .add(transaction.title)
            .addNullable(transaction.note)
            .add(transaction.entries.size)
        transaction.entries.forEach { entry ->
            add(entry.accountId.value)
                .add(entry.amount.minorUnits)
                .add(entry.amount.currency.value)
                .add(entry.role.name)
        }
        return this
    }
}

private class CanonicalFingerprint {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun add(value: String): CanonicalFingerprint = apply {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    fun add(value: Long): CanonicalFingerprint = add(value.toString())

    fun add(value: Int): CanonicalFingerprint = add(value.toString())

    fun add(value: Boolean): CanonicalFingerprint = add(if (value) "1" else "0")

    fun addNullable(value: String?): CanonicalFingerprint =
        if (value == null) add("<null>") else add("<value>").add(value)

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private const val SystemCreationCommandId = "system:init:v3"

private val SystemAccountIdValues = (
    SystemAccountIds.allFor(CurrencyCode.CNY) + SystemAccountIds.allFor(CurrencyCode.USD)
).mapTo(linkedSetOf(), AccountId::value)

private fun systemAccountTemplates(createdAtEpochMillis: Long): List<AccountEntity> = listOf(
    CurrencyCode.CNY,
    CurrencyCode.USD,
).flatMap { currency -> systemAccountTemplates(currency, createdAtEpochMillis) }

private fun systemAccountTemplates(
    currency: CurrencyCode,
    createdAtEpochMillis: Long,
): List<AccountEntity> = listOf(
    AccountEntity(
        id = SystemAccountIds.uncategorizedExpense(currency).value,
        name = if (currency == CurrencyCode.CNY) "未分类支出" else "Uncategorized expense (USD)",
        normalizedName = systemNormalizedName("__system_expense_uncategorized", currency),
        type = AccountType.EXPENSE_CATEGORY.name,
        currency = currency.value,
        isSystem = true,
        isArchived = false,
        createdAtEpochMillis = createdAtEpochMillis,
        creationCommandId = SystemCreationCommandId,
    ),
    AccountEntity(
        id = SystemAccountIds.uncategorizedIncome(currency).value,
        name = if (currency == CurrencyCode.CNY) "未分类收入" else "Uncategorized income (USD)",
        normalizedName = systemNormalizedName("__system_income_uncategorized", currency),
        type = AccountType.INCOME_CATEGORY.name,
        currency = currency.value,
        isSystem = true,
        isArchived = false,
        createdAtEpochMillis = createdAtEpochMillis,
        creationCommandId = SystemCreationCommandId,
    ),
    AccountEntity(
        id = SystemAccountIds.openingEquity(currency).value,
        name = if (currency == CurrencyCode.CNY) "期初权益" else "Opening equity (USD)",
        normalizedName = systemNormalizedName("__system_equity_opening", currency),
        type = AccountType.EQUITY_ADJUSTMENT.name,
        currency = currency.value,
        isSystem = true,
        isArchived = false,
        createdAtEpochMillis = createdAtEpochMillis,
        creationCommandId = SystemCreationCommandId,
    ),
)

private fun systemAccountDomain(id: String, createdAtEpochMillis: Long): LedgerAccount {
    val entity = systemAccountTemplates(createdAtEpochMillis).firstOrNull { it.id == id }
        ?: error("Unknown system account id")
    return LedgerEntityMapper.accountToDomain(entity, MappingDiagnostics.None)
        ?: error("Static system account template is invalid")
}

private fun systemNormalizedName(base: String, currency: CurrencyCode): String =
    if (currency == CurrencyCode.CNY) base else "${base}_${currency.value.lowercase()}"

private fun AccountEntity.matchesSystemTemplate(template: AccountEntity): Boolean =
    id == template.id &&
        normalizedName == template.normalizedName &&
        type == template.type &&
        currency == template.currency &&
        isSystem &&
        !isArchived

private fun <T> List<T>.allUnique(): Boolean = size == toSet().size

private fun result(status: RepositoryWriteStatus, entityId: String? = null) =
    RepositoryWriteResult(status = status, entityId = entityId)

private fun applied(entityId: String) = result(RepositoryWriteStatus.APPLIED, entityId)

private fun collision() = result(RepositoryWriteStatus.COMMAND_COLLISION)

private fun invalidState() = result(RepositoryWriteStatus.INVALID_STATE)

private fun notFound() = result(RepositoryWriteStatus.NOT_FOUND)

private fun accountNotFound() = result(RepositoryWriteStatus.ACCOUNT_NOT_FOUND)
