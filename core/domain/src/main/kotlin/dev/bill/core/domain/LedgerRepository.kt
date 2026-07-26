package dev.bill.core.domain

import dev.bill.core.model.AccountId
import dev.bill.core.model.TransactionId
import kotlinx.coroutines.flow.Flow

enum class RepositoryWriteStatus {
    APPLIED,
    ALREADY_APPLIED,
    DUPLICATE_NAME,
    COMMAND_COLLISION,
    NOT_FOUND,
    INVALID_STATE,
    ACCOUNT_NOT_FOUND,
}

data class RepositoryWriteResult(
    val status: RepositoryWriteStatus,
    val entityId: String? = null,
)

interface LedgerRepository {
    fun observeState(): Flow<LedgerState>

    suspend fun findAccount(id: AccountId): LedgerAccount?

    suspend fun findDraft(id: DraftId): ReviewDraft?

    suspend fun findTransaction(id: TransactionId): PostedTransaction?

    suspend fun createAccount(
        account: LedgerAccount,
        openingTransaction: PostedTransaction?,
        auditRecords: List<AuditRecord>,
    ): RepositoryWriteResult

    suspend fun createManualDraft(
        draft: ReviewDraft,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult

    suspend fun selectFundingAccount(
        draftId: DraftId,
        accountId: AccountId,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult

    suspend fun confirmDraft(
        draftId: DraftId,
        transaction: PostedTransaction,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult

    suspend fun dismissDraft(
        draftId: DraftId,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult

    suspend fun voidTransaction(
        transactionId: TransactionId,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult
}
