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
        WHERE state NOT IN ('CONFIRMED', 'DISMISSED')
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(entries: List<EntryEntity>)

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
