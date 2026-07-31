package dev.bill.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun findAccount(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id IN (:ids)")
    suspend fun findAccounts(ids: List<String>): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findAccountByNormalizedName(normalizedName: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccount(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccountsIfAbsent(accounts: List<AccountEntity>): List<Long>

    @Query(
        """
        SELECT a.*,
               COALESCE(SUM(
                   CASE
                       WHEN t.status = 'ACTIVE' AND e.currency = a.currency
                       THEN e.amountMinorUnits
                       ELSE 0
                   END
               ), 0) AS balanceMinorUnits,
               COALESCE(SUM(
                   CASE
                       WHEN t.status = 'ACTIVE' AND e.currency <> a.currency
                       THEN 1
                       ELSE 0
                   END
               ), 0) AS currencyMismatchCount
        FROM accounts AS a
        LEFT JOIN ledger_entries AS e ON e.accountId = a.id
        LEFT JOIN ledger_transactions AS t ON t.id = e.transactionId
        GROUP BY a.id
        ORDER BY a.isSystem ASC, a.createdAtEpochMillis ASC, a.id ASC
        """,
    )
    fun observeAccountBalances(): Flow<List<AccountBalanceRow>>

    @Transaction
    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun findDraft(id: String): DraftWithSourceEvidence?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDraft(draft: DraftEntity)

    @Query(
        """
        SELECT * FROM drafts
        WHERE state NOT IN ('CONFIRMED', 'LINKED', 'DISMISSED')
        ORDER BY occurredAtEpochMillis DESC, id DESC
        """,
    )
    @Transaction
    fun observePendingDrafts(): Flow<List<DraftWithSourceEvidence>>

    @Query(
        """
        UPDATE drafts
        SET fundingAccountId = :accountId,
            state = 'EDITED',
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :draftId
          AND state IN ('WAITING_USER', 'EDITED')
        """,
    )
    suspend fun selectFundingAccount(
        draftId: String,
        accountId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE drafts
        SET state = 'CONFIRMED',
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :draftId
          AND state IN ('WAITING_USER', 'EDITED')
        """,
    )
    suspend fun markDraftConfirmed(draftId: String, updatedAtEpochMillis: Long): Int

    @Query(
        """
        UPDATE drafts
        SET state = 'LINKED',
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id IN (:draftIds)
          AND state IN ('WAITING_USER', 'EDITED')
        """,
    )
    suspend fun markDraftsLinked(
        draftIds: List<String>,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE drafts
        SET state = 'DISMISSED',
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :draftId
          AND state IN ('WAITING_USER', 'EDITED')
        """,
    )
    suspend fun dismissDraft(draftId: String, updatedAtEpochMillis: Long): Int

    @Query(
        """
        UPDATE drafts
        SET state = 'WAITING_USER',
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :draftId
        """,
    )
    suspend fun restoreDraftForReview(draftId: String, updatedAtEpochMillis: Long): Int

    @Query(
        """
        UPDATE drafts
        SET state = 'WAITING_USER',
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id IN (:draftIds)
          AND state = 'LINKED'
        """,
    )
    suspend fun restoreLinkedDraftsForReview(
        draftIds: List<String>,
        updatedAtEpochMillis: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(entries: List<EntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInvestmentPosition(position: InvestmentPositionEntity)

    @Query("SELECT * FROM investment_positions WHERE id = :id")
    suspend fun findInvestmentPosition(id: String): InvestmentPositionEntity?

    @Query("SELECT * FROM investment_positions WHERE accountId = :accountId")
    suspend fun findInvestmentPositionByAccountId(accountId: String): InvestmentPositionEntity?

    @Query(
        """
        SELECT * FROM investment_positions
        ORDER BY updatedAtEpochMillis DESC, id ASC
        """,
    )
    fun observeInvestmentPositions(): Flow<List<InvestmentPositionEntity>>

    @Query(
        """
        SELECT COUNT(*)
        FROM investment_positions AS position
        LEFT JOIN accounts AS account ON account.id = position.accountId
        LEFT JOIN command_receipts AS receipt
            ON receipt.commandId = position.creationCommandId
        WHERE account.id IS NULL
           OR account.type != 'INVESTMENT_SECURITY'
           OR account.currency != position.currency
           OR account.isSystem != 0
           OR account.isArchived != 0
           OR position.currency != 'CNY'
           OR position.currentValueMinorUnits <= 0
           OR TRIM(position.name) = ''
           OR (position.instrumentCode IS NOT NULL AND TRIM(position.instrumentCode) = '')
           OR (position.unitsDecimal IS NOT NULL AND TRIM(position.unitsDecimal) = '')
           OR (position.costBasisMinorUnits IS NULL) != (position.costBasisCurrency IS NULL)
           OR (position.costBasisMinorUnits IS NOT NULL AND position.costBasisMinorUnits <= 0)
           OR (position.costBasisCurrency IS NOT NULL AND position.costBasisCurrency != position.currency)
           OR position.sourceMode NOT IN ('MANUAL', 'OCR')
           OR position.asOfEpochMillis > position.updatedAtEpochMillis
           OR position.createdAtEpochMillis > position.updatedAtEpochMillis
           OR receipt.commandId IS NULL
           OR receipt.operation != 'CREATE_INVESTMENT_POSITION'
           OR receipt.targetId != position.id
           OR receipt.resultEntityId != position.id
        """,
    )
    fun observeInvestmentPositionIntegrityIssueCount(): Flow<Long>

    @Query(
        """
        SELECT COUNT(*)
        FROM drafts AS draft
        LEFT JOIN accounts AS investment_account
            ON investment_account.id = draft.investmentAccountId
        LEFT JOIN investment_positions AS investment_position
            ON investment_position.accountId = draft.investmentAccountId
        WHERE draft.state NOT IN ('WAITING_USER', 'EDITED', 'CONFIRMED', 'DISMISSED', 'LINKED')
           OR draft.type NOT IN ('EXPENSE', 'INCOME', 'INVEST_BUY')
           OR draft.amountMinorUnits <= 0
           OR draft.currency NOT IN ('CNY', 'USD')
           OR TRIM(draft.counterparty) = ''
           OR draft.createdAtEpochMillis > draft.updatedAtEpochMillis
           OR draft.occurredAtEpochMillis > draft.updatedAtEpochMillis
           OR (
               draft.type IN ('EXPENSE', 'INCOME')
               AND draft.investmentAccountId IS NOT NULL
           )
           OR (
               draft.type = 'INVEST_BUY'
               AND (
                   draft.investmentAccountId IS NULL
                   OR investment_account.id IS NULL
                   OR investment_position.id IS NULL
                   OR investment_account.type != 'INVESTMENT_SECURITY'
                   OR investment_account.currency != draft.currency
                   OR investment_account.currency != 'CNY'
                   OR investment_account.isSystem != 0
                   OR investment_account.isArchived != 0
               )
           )
           OR (
               draft.fundingAccountId IS NOT NULL
               AND draft.fundingAccountId = draft.investmentAccountId
           )
        """,
    )
    fun observeDraftIntegrityIssueCount(): Flow<Long>

    @Transaction
    @Query("SELECT * FROM ledger_transactions WHERE id = :id")
    suspend fun findTransactionWithEntries(id: String): TransactionWithEntries?

    @Transaction
    @Query(
        """
        SELECT * FROM ledger_transactions
        WHERE status = 'ACTIVE'
        ORDER BY confirmedAtEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeRecentTransactions(limit: Int): Flow<List<TransactionWithEntries>>

    @Query(
        """
        SELECT
            (SELECT COUNT(*)
             FROM ledger_transactions
             WHERE status NOT IN ('ACTIVE', 'VOIDED'))
            +
            (SELECT COUNT(*)
             FROM ledger_transactions AS t
             WHERE t.status = 'ACTIVE'
               AND (
                   t.type NOT IN (
                       'EXPENSE', 'INCOME', 'TRANSFER', 'TOPUP', 'REFUND',
                       'LIABILITY_DRAW', 'LIABILITY_REPAY', 'INVEST_BUY',
                       'INVEST_SELL', 'FEE', 'ADJUSTMENT'
                   )
                   OR t.sourceMode NOT IN ('MANUAL', 'EXTERNAL')
                   OR TRIM(t.title) = ''
                   OR t.occurredAtEpochMillis > t.confirmedAtEpochMillis
                   OR (SELECT COUNT(*)
                       FROM ledger_entries AS e
                       WHERE e.transactionId = t.id) < 2
                   OR EXISTS (
                       SELECT 1
                       FROM ledger_entries AS e
                       WHERE e.transactionId = t.id
                         AND (
                             e.amountMinorUnits = 0
                             OR e.currency NOT IN ('CNY', 'USD')
                             OR e.role NOT IN (
                                 'FUNDING', 'EXPENSE', 'INCOME', 'ASSET',
                                 'LIABILITY', 'INVESTMENT', 'EQUITY', 'FEE'
                             )
                       )
                   )
                   OR (SELECT COUNT(DISTINCT e.currency)
                       FROM ledger_entries AS e
                       WHERE e.transactionId = t.id) <> 1
                   OR EXISTS (
                       SELECT 1
                       FROM (
                           SELECT e.currency, SUM(e.amountMinorUnits) AS currencyBalance
                           FROM ledger_entries AS e
                           WHERE e.transactionId = t.id
                           GROUP BY e.currency
                       ) AS balancesByCurrency
                       WHERE balancesByCurrency.currencyBalance <> 0
                   )
               ))
        """,
    )
    fun observeLedgerIntegrityIssueCount(): Flow<Long>

    @Query(
        """
        SELECT COUNT(*) FROM ledger_transactions
        WHERE draftId = :draftId AND status = 'ACTIVE'
        """,
    )
    suspend fun countActiveTransactionsForDraft(draftId: String): Int

    @Query(
        """
        UPDATE ledger_transactions
        SET status = 'VOIDED'
        WHERE id = :transactionId AND status = 'ACTIVE'
        """,
    )
    suspend fun markTransactionVoided(transactionId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReconciliationDraftLinks(
        links: List<ReconciliationDraftLinkEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransactionRelations(relations: List<TransactionRelationEntity>)

    @Query(
        """
        SELECT * FROM reconciliation_draft_links
        WHERE transactionId = :transactionId
        ORDER BY role ASC, draftId ASC
        """,
    )
    suspend fun findReconciliationDraftLinks(
        transactionId: String,
    ): List<ReconciliationDraftLinkEntity>

    @Query(
        """
        SELECT COUNT(*)
        FROM reconciliation_draft_links AS link
        INNER JOIN ledger_transactions AS transaction_record
            ON transaction_record.id = link.transactionId
        WHERE link.draftId = :draftId
          AND transaction_record.status = 'ACTIVE'
        """,
    )
    suspend fun countActiveReconciliationsForDraft(draftId: String): Int

    @Query(
        """
        SELECT entry.amountMinorUnits AS amountMinorUnits, entry.currency AS currency
        FROM transaction_relations AS relation_record
        INNER JOIN ledger_transactions AS refund
            ON refund.id = relation_record.fromTransactionId
        INNER JOIN ledger_entries AS entry
            ON entry.transactionId = refund.id
        WHERE relation_record.toTransactionId = :originalTransactionId
          AND relation_record.type = 'REFUNDS'
          AND refund.status = 'ACTIVE'
          AND refund.type = 'REFUND'
          AND entry.role = 'EXPENSE'
        ORDER BY refund.id ASC, entry.position ASC
        """,
    )
    suspend fun findActiveRefundExpenseLegs(
        originalTransactionId: String,
    ): List<ActiveRefundLegRow>

    @Query(
        """
        SELECT COUNT(*)
        FROM transaction_relations AS relation_record
        INNER JOIN ledger_transactions AS refund
            ON refund.id = relation_record.fromTransactionId
        WHERE relation_record.toTransactionId = :originalTransactionId
          AND relation_record.type = 'REFUNDS'
          AND refund.status = 'ACTIVE'
        """,
    )
    suspend fun countActiveRefundRelationsTo(originalTransactionId: String): Int

    @Query(
        """
        SELECT
            relation_record.toTransactionId AS originalTransactionId,
            -SUM(entry.amountMinorUnits) AS amountMinorUnits,
            entry.currency AS currency
        FROM transaction_relations AS relation_record
        INNER JOIN ledger_transactions AS refund
            ON refund.id = relation_record.fromTransactionId
        INNER JOIN ledger_entries AS entry
            ON entry.transactionId = refund.id
        WHERE relation_record.type = 'REFUNDS'
          AND refund.status = 'ACTIVE'
          AND refund.type = 'REFUND'
          AND entry.role = 'EXPENSE'
        GROUP BY relation_record.toTransactionId, entry.currency
        ORDER BY relation_record.toTransactionId ASC
        """,
    )
    fun observeActiveRefundTotals(): Flow<List<ActiveRefundTotalRow>>

    @Query(
        """
        SELECT COUNT(*)
        FROM reconciliation_draft_links AS link
        LEFT JOIN ledger_transactions AS transaction_record
            ON transaction_record.id = link.transactionId
        LEFT JOIN drafts AS draft
            ON draft.id = link.draftId
        LEFT JOIN command_receipts AS receipt
            ON receipt.commandId = transaction_record.commandId
        WHERE transaction_record.id IS NULL
           OR draft.id IS NULL
           OR receipt.commandId IS NULL
           OR receipt.operation != 'RESOLVE_RECONCILIATION'
           OR receipt.targetId != transaction_record.id
           OR link.role NOT IN (
               'TRANSFER_OUTBOUND',
               'TRANSFER_INBOUND',
               'REPAYMENT_OUTBOUND',
               'REFUND_INBOUND'
           )
           OR (
               transaction_record.type = 'TRANSFER'
               AND link.role NOT IN ('TRANSFER_OUTBOUND', 'TRANSFER_INBOUND')
           )
           OR (
               transaction_record.type = 'LIABILITY_REPAY'
               AND link.role != 'REPAYMENT_OUTBOUND'
           )
           OR (
               transaction_record.type = 'REFUND'
               AND link.role != 'REFUND_INBOUND'
           )
           OR transaction_record.type NOT IN ('TRANSFER', 'LIABILITY_REPAY', 'REFUND')
           OR (
               transaction_record.status = 'ACTIVE'
               AND (
                   draft.state != 'LINKED'
                   OR (
                       SELECT COUNT(*)
                       FROM reconciliation_draft_links AS active_link
                       INNER JOIN ledger_transactions AS active_transaction
                           ON active_transaction.id = active_link.transactionId
                       WHERE active_link.draftId = link.draftId
                         AND active_transaction.status = 'ACTIVE'
                   ) != 1
               )
           )
           OR (
               transaction_record.status = 'VOIDED'
               AND draft.state = 'LINKED'
               AND NOT EXISTS (
                   SELECT 1
                   FROM reconciliation_draft_links AS active_link
                   INNER JOIN ledger_transactions AS active_transaction
                       ON active_transaction.id = active_link.transactionId
                   WHERE active_link.draftId = link.draftId
                     AND active_transaction.status = 'ACTIVE'
               )
           )
           OR link.linkedAtEpochMillis < draft.createdAtEpochMillis
           OR link.linkedAtEpochMillis < transaction_record.occurredAtEpochMillis
        """,
    )
    fun observeReconciliationLinkIntegrityIssueCount(): Flow<Long>

    @Query(
        """
        SELECT COUNT(*)
        FROM transaction_relations AS relation_record
        LEFT JOIN ledger_transactions AS source_transaction
            ON source_transaction.id = relation_record.fromTransactionId
        LEFT JOIN ledger_transactions AS target_transaction
            ON target_transaction.id = relation_record.toTransactionId
        LEFT JOIN command_receipts AS receipt
            ON receipt.commandId = source_transaction.commandId
        WHERE source_transaction.id IS NULL
           OR target_transaction.id IS NULL
           OR receipt.commandId IS NULL
           OR receipt.operation != 'RESOLVE_RECONCILIATION'
           OR receipt.targetId != source_transaction.id
           OR relation_record.fromTransactionId = relation_record.toTransactionId
           OR relation_record.type != 'REFUNDS'
           OR relation_record.decision != 'USER_CONFIRMED'
           OR source_transaction.type != 'REFUND'
           OR target_transaction.type != 'EXPENSE'
           OR relation_record.createdAtEpochMillis < source_transaction.occurredAtEpochMillis
           OR relation_record.createdAtEpochMillis < target_transaction.occurredAtEpochMillis
        """,
    )
    fun observeTransactionRelationIntegrityIssueCount(): Flow<Long>

    @Query(
        """
        SELECT COUNT(*)
        FROM command_receipts AS receipt
        LEFT JOIN ledger_transactions AS transaction_record
            ON transaction_record.id = receipt.targetId
        WHERE receipt.operation = 'RESOLVE_RECONCILIATION'
          AND (
              transaction_record.id IS NULL
              OR transaction_record.commandId != receipt.commandId
              OR transaction_record.draftId IS NOT NULL
              OR transaction_record.type NOT IN ('TRANSFER', 'LIABILITY_REPAY', 'REFUND')
              OR (
                  transaction_record.type = 'TRANSFER'
                  AND (
                      (SELECT COUNT(*)
                       FROM reconciliation_draft_links AS link
                       WHERE link.transactionId = transaction_record.id) != 2
                      OR
                      (SELECT COUNT(*)
                       FROM reconciliation_draft_links AS link
                       WHERE link.transactionId = transaction_record.id
                         AND link.role = 'TRANSFER_OUTBOUND') != 1
                      OR
                      (SELECT COUNT(*)
                       FROM reconciliation_draft_links AS link
                       WHERE link.transactionId = transaction_record.id
                         AND link.role = 'TRANSFER_INBOUND') != 1
                      OR
                      (SELECT COUNT(*)
                       FROM transaction_relations AS relation_record
                       WHERE relation_record.fromTransactionId = transaction_record.id) != 0
                  )
              )
              OR (
                  transaction_record.type = 'LIABILITY_REPAY'
                  AND (
                      (SELECT COUNT(*)
                       FROM reconciliation_draft_links AS link
                       WHERE link.transactionId = transaction_record.id
                         AND link.role = 'REPAYMENT_OUTBOUND') != 1
                      OR
                      (SELECT COUNT(*)
                       FROM reconciliation_draft_links AS link
                       WHERE link.transactionId = transaction_record.id) != 1
                      OR
                      (SELECT COUNT(*)
                       FROM transaction_relations AS relation_record
                       WHERE relation_record.fromTransactionId = transaction_record.id) != 0
                  )
              )
              OR (
                  transaction_record.type = 'REFUND'
                  AND (
                      (SELECT COUNT(*)
                       FROM reconciliation_draft_links AS link
                       WHERE link.transactionId = transaction_record.id
                         AND link.role = 'REFUND_INBOUND') != 1
                      OR
                      (SELECT COUNT(*)
                       FROM reconciliation_draft_links AS link
                       WHERE link.transactionId = transaction_record.id) != 1
                      OR
                      (SELECT COUNT(*)
                       FROM transaction_relations AS relation_record
                       WHERE relation_record.fromTransactionId = transaction_record.id
                         AND relation_record.type = 'REFUNDS') != 1
                      OR
                      (SELECT COUNT(*)
                       FROM transaction_relations AS relation_record
                       WHERE relation_record.fromTransactionId = transaction_record.id) != 1
                  )
              )
          )
        """,
    )
    fun observeReconciliationTransactionIntegrityIssueCount(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAuditEvents(events: List<AuditEventEntity>)

    @Query("SELECT COUNT(*) FROM audit_events WHERE id IN (:ids)")
    suspend fun countAuditEvents(ids: List<String>): Int

    @Query("SELECT * FROM audit_events WHERE id = :id")
    suspend fun findAuditEvent(id: String): AuditEventEntity?

    @Query("SELECT * FROM command_receipts WHERE commandId = :commandId")
    suspend fun findCommandReceipt(commandId: String): CommandReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCommandReceipt(receipt: CommandReceiptEntity)
}
