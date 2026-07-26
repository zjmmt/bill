package dev.bill.application

import dev.bill.core.domain.AccountBalance
import dev.bill.core.domain.AuditAction
import dev.bill.core.domain.AuditEventId
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
import dev.bill.core.domain.ReviewDraft
import dev.bill.core.domain.SystemAccountIds
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
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dev.bill.source.review.SourceProposalRecord
import dev.bill.source.review.SourceReviewRepository
import dev.bill.source.review.allowedExternalDraftCurrencies
import dev.bill.source.review.allowsExternalDraftCurrency

enum class OperationError {
    INVALID_NAME,
    INVALID_AMOUNT,
    INVALID_COUNTERPARTY,
    NOTE_TOO_LONG,
    UNSUPPORTED_ACCOUNT_TYPE,
    UNSUPPORTED_CURRENCY,
    ACCOUNT_REQUIRED,
    ACCOUNT_NOT_FOUND,
    DUPLICATE_ACCOUNT_NAME,
    SOURCE_CURRENCY_NOT_ALLOWED,
    INVALID_STATE,
    NOT_FOUND,
    CONFLICT,
}

sealed interface OperationResult {
    data class Success(val entityId: String?) : OperationResult

    data class Failure(val error: OperationError) : OperationResult
}

data class CreateAccountCommand(
    val commandId: CommandId,
    val name: String,
    val type: AccountType,
    val openingBalanceText: String,
    val currency: CurrencyCode = CurrencyCode.CNY,
)

data class CreateManualDraftCommand(
    val commandId: CommandId,
    val type: TransactionType,
    val amountText: String,
    val counterparty: String,
    val note: String?,
    val occurredAt: Instant? = null,
    val currency: CurrencyCode = CurrencyCode.CNY,
)

data class CreateExternalDraftCommand(
    val commandId: CommandId,
    val proposalId: String,
    val type: TransactionType,
    val amountText: String,
    val counterparty: String,
    val note: String?,
    val occurredAt: Instant? = null,
    val currency: CurrencyCode = CurrencyCode.CNY,
)

class BillService(
    private val repository: LedgerRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val sourceReviewRepository: SourceReviewRepository = SourceReviewRepository.Empty,
) {
    fun newCommandId(): CommandId = CommandId(UUID.randomUUID().toString())

    fun observeSnapshot(): Flow<BillSnapshot> = combine(
        repository.observeState(),
        sourceReviewRepository.observePendingSourceProposals(),
        ::toSnapshot,
    )

    suspend fun createAccount(command: CreateAccountCommand): OperationResult {
        if (command.name.hasControlCharacter()) {
            return OperationResult.Failure(OperationError.INVALID_NAME)
        }
        val name = command.name.trim().replace(whitespace, " ")
        if (name.isBlank() || name.length > MAX_ACCOUNT_NAME_LENGTH || name.hasControlCharacter()) {
            return OperationResult.Failure(OperationError.INVALID_NAME)
        }
        if (command.type !in creatableAccountTypes) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_ACCOUNT_TYPE)
        }
        if (!command.type.allowsUserAccountCurrency(command.currency)) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_CURRENCY)
        }

        val openingBalance = MoneyInput.parseNonNegative(command.openingBalanceText, command.currency)
            ?: return OperationResult.Failure(OperationError.INVALID_AMOUNT)
        val now = clock.instant()
        val account = LedgerAccount(
            id = AccountId("account:${command.commandId.value}"),
            name = name,
            normalizedName = name.lowercase(Locale.ROOT),
            type = command.type,
            currency = command.currency,
            isSystem = false,
            isArchived = false,
            createdAt = now,
            creationCommandId = command.commandId,
        )

        val openingTransaction = if (openingBalance.minorUnits == 0L) {
            null
        } else {
            when (
                val posting = PostingFactory.openingBalance(
                    account = account,
                    visibleBalance = openingBalance,
                    transactionId = TransactionId("transaction:opening:${command.commandId.value}"),
                    occurredAt = now,
                )
            ) {
                is PostingBuildResult.Valid -> PostedTransaction(
                    id = posting.transaction.id,
                    draftId = null,
                    type = posting.transaction.type,
                    status = TransactionStatus.ACTIVE,
                    sourceMode = TransactionSourceMode.MANUAL,
                    occurredAt = posting.transaction.occurredAt,
                    confirmedAt = now,
                    title = "$name 期初余额",
                    note = null,
                    commandId = command.commandId,
                    entries = posting.transaction.entries,
                )

                else -> return OperationResult.Failure(posting.toOperationError())
            }
        }

        val audits = buildList {
            add(
                audit(
                    commandId = command.commandId,
                    suffix = "account-created",
                    action = AuditAction.ACCOUNT_CREATED,
                    entityType = "account",
                    entityId = account.id.value,
                    occurredAt = now,
                ),
            )
            if (openingTransaction != null) {
                add(
                    audit(
                        commandId = command.commandId,
                        suffix = "opening-posted",
                        action = AuditAction.OPENING_BALANCE_POSTED,
                        entityType = "transaction",
                        entityId = openingTransaction.id.value,
                        occurredAt = now,
                    ),
                )
            }
        }
        return repository.createAccount(account, openingTransaction, audits).toOperationResult()
    }

    suspend fun createManualDraft(command: CreateManualDraftCommand): OperationResult {
        if (command.type != TransactionType.EXPENSE && command.type != TransactionType.INCOME) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }
        if (!command.currency.isSupportedLedgerCurrency()) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_CURRENCY)
        }
        val amount = MoneyInput.parsePositive(command.amountText, command.currency)
            ?: return OperationResult.Failure(OperationError.INVALID_AMOUNT)
        if (command.counterparty.hasControlCharacter()) {
            return OperationResult.Failure(OperationError.INVALID_COUNTERPARTY)
        }
        val counterparty = command.counterparty.trim().replace(whitespace, " ")
        if (
            counterparty.isBlank() ||
            counterparty.length > MAX_COUNTERPARTY_LENGTH ||
            counterparty.hasControlCharacter()
        ) {
            return OperationResult.Failure(OperationError.INVALID_COUNTERPARTY)
        }
        val note = command.note?.trim()?.takeIf(String::isNotEmpty)
        if (note != null && (note.length > MAX_NOTE_LENGTH || note.hasControlCharacter())) {
            return OperationResult.Failure(OperationError.NOTE_TOO_LONG)
        }

        val now = clock.instant()
        val draft = ManualDraft(
            id = DraftId("draft:${command.commandId.value}"),
            state = DraftState.WAITING_USER,
            type = command.type,
            amount = amount,
            occurredAt = command.occurredAt?.coerceAtMost(now) ?: now,
            counterparty = counterparty,
            note = note,
            fundingAccountId = null,
            createdAt = now,
            updatedAt = now,
            creationCommandId = command.commandId,
        )
        return repository.createManualDraft(
            draft = draft,
            auditRecord = audit(
                commandId = command.commandId,
                suffix = "draft-created",
                action = AuditAction.MANUAL_DRAFT_CREATED,
                entityType = "draft",
                entityId = draft.id.value,
                occurredAt = now,
            ),
        ).toOperationResult()
    }

    suspend fun createExternalDraft(command: CreateExternalDraftCommand): OperationResult {
        if (
            command.proposalId.isBlank() ||
            command.proposalId.length > MAX_SOURCE_PROPOSAL_ID_LENGTH ||
            command.proposalId.hasControlCharacter()
        ) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }
        if (command.type != TransactionType.EXPENSE && command.type != TransactionType.INCOME) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }
        if (!command.currency.isSupportedLedgerCurrency()) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_CURRENCY)
        }
        val amount = MoneyInput.parsePositive(command.amountText, command.currency)
            ?: return OperationResult.Failure(OperationError.INVALID_AMOUNT)
        if (command.counterparty.hasControlCharacter()) {
            return OperationResult.Failure(OperationError.INVALID_COUNTERPARTY)
        }
        val counterparty = command.counterparty.trim().replace(whitespace, " ")
        if (
            counterparty.isBlank() ||
            counterparty.length > MAX_COUNTERPARTY_LENGTH ||
            counterparty.hasControlCharacter()
        ) {
            return OperationResult.Failure(OperationError.INVALID_COUNTERPARTY)
        }
        val note = command.note?.trim()?.takeIf(String::isNotEmpty)
        if (note != null && (note.length > MAX_NOTE_LENGTH || note.hasControlCharacter())) {
            return OperationResult.Failure(OperationError.NOTE_TOO_LONG)
        }
        val sourceProposal = sourceReviewRepository.observePendingSourceProposals()
            .first()
            .firstOrNull { it.id == command.proposalId }
            ?: return OperationResult.Failure(OperationError.NOT_FOUND)
        if (!sourceProposal.allowsExternalDraftCurrency(command.currency)) {
            return OperationResult.Failure(OperationError.SOURCE_CURRENCY_NOT_ALLOWED)
        }

        val now = clock.instant()
        val draft = ReviewDraft(
            id = DraftId("draft:${command.commandId.value}"),
            state = DraftState.WAITING_USER,
            type = command.type,
            amount = amount,
            occurredAt = command.occurredAt?.coerceAtMost(now) ?: now,
            counterparty = counterparty,
            note = note,
            fundingAccountId = null,
            createdAt = now,
            updatedAt = now,
            creationCommandId = command.commandId,
            sourceMode = TransactionSourceMode.EXTERNAL,
        )
        return sourceReviewRepository.completeSourceProposal(
            proposalId = command.proposalId,
            draft = draft,
            auditRecord = audit(
                commandId = command.commandId,
                suffix = "external-draft-created",
                action = AuditAction.EXTERNAL_DRAFT_CREATED,
                entityType = "draft",
                entityId = draft.id.value,
                occurredAt = now,
            ),
        ).toOperationResult()
    }

    suspend fun dismissSourceProposal(
        commandId: CommandId,
        proposalId: String,
    ): OperationResult {
        if (
            proposalId.isBlank() ||
            proposalId.length > MAX_SOURCE_PROPOSAL_ID_LENGTH ||
            proposalId.hasControlCharacter()
        ) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }
        val now = clock.instant()
        return sourceReviewRepository.dismissSourceProposal(
            proposalId = proposalId,
            auditRecord = audit(
                commandId = commandId,
                suffix = "source-proposal-dismissed",
                action = AuditAction.SOURCE_PROPOSAL_DISMISSED,
                entityType = "source_proposal",
                entityId = proposalId,
                occurredAt = now,
            ),
        ).toOperationResult()
    }

    suspend fun selectFundingAccount(
        commandId: CommandId,
        draftId: DraftId,
        accountId: AccountId,
    ): OperationResult {
        val draft = repository.findDraft(draftId)
            ?: return OperationResult.Failure(OperationError.NOT_FOUND)
        if (draft.state != DraftState.WAITING_USER && draft.state != DraftState.EDITED) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }
        val account = repository.findAccount(accountId)
            ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
        if (!account.canFund(draft)) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_ACCOUNT_TYPE)
        }
        val now = clock.instant()
        return repository.selectFundingAccount(
            draftId = draftId,
            accountId = accountId,
            auditRecord = audit(
                commandId = commandId,
                suffix = "funding-selected",
                action = AuditAction.FUNDING_ACCOUNT_SELECTED,
                entityType = "draft",
                entityId = draftId.value,
                occurredAt = now,
            ),
        ).toOperationResult()
    }

    suspend fun confirmDraft(
        commandId: CommandId,
        draftId: DraftId,
    ): OperationResult {
        val transactionId = TransactionId("transaction:confirm:${commandId.value}")
        repository.findTransaction(transactionId)?.let { existing ->
            return if (existing.commandId == commandId && existing.draftId == draftId) {
                OperationResult.Success(existing.id.value)
            } else {
                OperationResult.Failure(OperationError.CONFLICT)
            }
        }
        val draft = repository.findDraft(draftId)
            ?: return OperationResult.Failure(OperationError.NOT_FOUND)
        if (draft.state != DraftState.WAITING_USER && draft.state != DraftState.EDITED) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }
        val fundingAccountId = draft.fundingAccountId
            ?: return OperationResult.Failure(OperationError.ACCOUNT_REQUIRED)
        val fundingAccount = repository.findAccount(fundingAccountId)
            ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
        val now = clock.instant()
        val validated = when (
            val posting = PostingFactory.manualDraft(
                draft = draft,
                fundingAccount = fundingAccount,
                transactionId = transactionId,
                confirmedAt = now,
            )
        ) {
            is PostingBuildResult.Valid -> posting.transaction
            else -> return OperationResult.Failure(posting.toOperationError())
        }
        val transaction = PostedTransaction(
            id = validated.id,
            draftId = draft.id,
            type = validated.type,
            status = TransactionStatus.ACTIVE,
            sourceMode = draft.sourceMode,
            occurredAt = validated.occurredAt,
            confirmedAt = now,
            title = draft.counterparty,
            note = draft.note,
            commandId = commandId,
            entries = validated.entries,
        )
        return repository.confirmDraft(
            draftId = draftId,
            transaction = transaction,
            auditRecord = audit(
                commandId = commandId,
                suffix = "draft-confirmed",
                action = AuditAction.DRAFT_CONFIRMED,
                entityType = "draft",
                entityId = draftId.value,
                occurredAt = now,
            ),
        ).toOperationResult()
    }

    suspend fun dismissDraft(
        commandId: CommandId,
        draftId: DraftId,
    ): OperationResult {
        val now = clock.instant()
        return repository.dismissDraft(
            draftId = draftId,
            auditRecord = audit(
                commandId = commandId,
                suffix = "draft-dismissed",
                action = AuditAction.DRAFT_DISMISSED,
                entityType = "draft",
                entityId = draftId.value,
                occurredAt = now,
            ),
        ).toOperationResult()
    }

    suspend fun voidTransaction(
        commandId: CommandId,
        transactionId: TransactionId,
    ): OperationResult {
        val now = clock.instant()
        return repository.voidTransaction(
            transactionId = transactionId,
            auditRecord = audit(
                commandId = commandId,
                suffix = "transaction-voided",
                action = AuditAction.TRANSACTION_VOIDED,
                entityType = "transaction",
                entityId = transactionId.value,
                occurredAt = now,
            ),
        ).toOperationResult()
    }

    private fun toSnapshot(
        state: LedgerState,
        sourceProposalRecords: List<SourceProposalRecord>,
    ): BillSnapshot {
        check(
            state.accountBalances.all { it.account.currency.isSupportedLedgerCurrency() } &&
                state.pendingDrafts.all { it.amount.currency.isSupportedLedgerCurrency() } &&
                state.recentTransactions.all { transaction ->
                    transaction.entries.all { it.amount.currency.isSupportedLedgerCurrency() }
                },
        ) { "Ledger snapshot contains an unsupported currency" }

        val userBalances = state.accountBalances.filterNot { it.account.isSystem || it.account.isArchived }
        val assetBalances = userBalances.filter { it.account.type.isAssetLike() }
        val liabilityBalances = userBalances.filter { it.account.type.isLiabilityLike() }
        val currencyBalances = userBalances
            .map { it.account.currency }
            .distinct()
            .sortedBy(CurrencyCode::value)
            .ifEmpty { listOf(CurrencyCode.CNY) }
            .map { currency ->
                val assetsMinor = assetBalances
                    .filter { it.account.currency == currency }
                    .sumMinorUnitsExact()
                val signedLiabilitiesMinor = liabilityBalances
                    .filter { it.account.currency == currency }
                    .sumMinorUnitsExact()
                val liabilitiesMinor = Math.negateExact(signedLiabilitiesMinor)
                CurrencyBalanceSummary(
                    currency = currency,
                    netWorth = Money(
                        Math.subtractExact(assetsMinor, liabilitiesMinor),
                        currency,
                    ),
                    assets = Money(assetsMinor, currency),
                    liabilities = Money(liabilitiesMinor, currency),
                )
            }

        val accounts = userBalances
            .sortedWith(compareBy<AccountBalance> { it.account.createdAt }.thenBy { it.account.id.value })
            .map { accountBalance ->
                val isLiability = accountBalance.account.type.isLiabilityLike()
                AccountSummary(
                    id = accountBalance.account.id.value,
                    name = accountBalance.account.name,
                    type = accountBalance.account.type,
                    displayBalance = if (isLiability) {
                        accountBalance.balance.copy(
                            minorUnits = Math.negateExact(accountBalance.balance.minorUnits),
                        )
                    } else {
                        accountBalance.balance
                    },
                    isLiability = isLiability,
                )
            }

        val pendingDrafts = state.pendingDrafts
            .filter { it.state == DraftState.WAITING_USER || it.state == DraftState.EDITED }
            .sortedWith(compareByDescending<ManualDraft> { it.updatedAt }.thenBy { it.id.value })
            .map { draft ->
                DraftSummary(
                    id = draft.id.value,
                    kind = if (draft.type == TransactionType.EXPENSE) {
                        DraftSummaryKind.EXPENSE
                    } else {
                        DraftSummaryKind.INCOME
                    },
                    amount = draft.amount,
                    counterparty = draft.counterparty,
                    note = draft.note,
                    fundingAccountId = draft.fundingAccountId?.value,
                    occurredAt = draft.occurredAt,
                    sourceMode = draft.sourceMode,
                )
            }

        val pendingSourceReviews = sourceProposalRecords
            .map { record ->
                val kind = when (record.captureMethod) {
                    dev.bill.source.contract.CaptureMethod.NOTIFICATION ->
                        SourceReviewKind.NOTIFICATION

                    dev.bill.source.contract.CaptureMethod.SHARE_TEXT ->
                        SourceReviewKind.SHARED_TEXT

                    dev.bill.source.contract.CaptureMethod.STATEMENT_IMPORT ->
                        SourceReviewKind.SELECTED_TEXT_FILE

                    dev.bill.source.contract.CaptureMethod.SHARE_FILE ->
                        SourceReviewKind.SHARED_RECEIPT_IMAGE

                    dev.bill.source.contract.CaptureMethod.PHOTO_OCR ->
                        SourceReviewKind.PHOTO_OCR

                    else -> error("Source proposal has no supported review presentation")
                }
                SourceReviewSummary(
                    id = record.id,
                    kind = kind,
                    sourceFamily = record.sourceFamily,
                    capturedAt = record.capturedAt,
                    suggestedAmount = record.candidate?.amount?.value,
                    allowedDraftCurrencies = record.allowedExternalDraftCurrencies(),
                    suggestedKind = when (record.candidate?.moneyDirection?.value) {
                        dev.bill.source.contract.ObservedMoneyDirection.OUTBOUND ->
                            DraftSummaryKind.EXPENSE

                        dev.bill.source.contract.ObservedMoneyDirection.INBOUND ->
                            DraftSummaryKind.INCOME

                        null -> null
                    },
                    suggestedCounterparty = record.candidate?.counterparty?.value,
                    diagnosticCode = record.diagnostic?.code?.name,
                    isPossibleDuplicate = record.isPossibleDuplicate,
                )
            }
            .sortedWith(
                compareByDescending<SourceReviewSummary> { it.capturedAt }.thenBy { it.id },
            )

        val recentTransactions = state.recentTransactions
            .asSequence()
            .filter { it.status == TransactionStatus.ACTIVE }
            .sortedWith(compareByDescending<PostedTransaction> { it.confirmedAt }.thenBy { it.id.value })
            .take(MAX_RECENT_TRANSACTIONS)
            .map { transaction -> transaction.toSummary() }
            .toList()

        val accountsById = accounts.associateBy(AccountSummary::id)
        val ledgerHardBlockCount = pendingDrafts.count { draft ->
            val fundingAccount = draft.fundingAccountId?.let(accountsById::get)
            fundingAccount == null || !fundingAccount.canFund(draft)
        }
        val hardBlockCount = Math.addExact(
            ledgerHardBlockCount,
            pendingSourceReviews.size,
        )

        val overview = OverviewSnapshot(
            currencyBalances = currencyBalances,
            pendingDraftCount = Math.addExact(pendingDrafts.size, pendingSourceReviews.size),
            hardBlockCount = hardBlockCount,
            possibleDuplicateCount = pendingSourceReviews.count(
                SourceReviewSummary::isPossibleDuplicate,
            ),
            accountCount = accounts.size,
            sourceHealth = SourceKind.entries.map { source ->
                SourceHealthSummary(
                    id = source.name.lowercase(Locale.ROOT),
                    kind = source,
                    state = SourceHealthState.FALLBACK_REQUIRED,
                )
            },
            recentTransactions = recentTransactions,
        )
        return BillSnapshot(
            overview = overview,
            accounts = accounts,
            pendingDrafts = pendingDrafts,
            pendingSourceReviews = pendingSourceReviews,
        )
    }

    private fun PostedTransaction.toSummary(): TransactionSummary {
        val (kind, amount) = when (type) {
            TransactionType.EXPENSE -> TransactionSummaryKind.EXPENSE to
                entries.first { it.role == EntryRole.EXPENSE }.amount

            TransactionType.INCOME -> TransactionSummaryKind.INCOME to
                entries.first { it.role == EntryRole.INCOME }.amount.absoluteExact()

            else -> TransactionSummaryKind.MONEY_MOVEMENT to
                entries.firstOrNull { entry ->
                    !SystemAccountIds.isSystemAccount(entry.accountId)
                }?.amount?.absoluteExact().orZeroCny()
        }
        return TransactionSummary(
            id = id.value,
            title = title,
            supportingText = when {
                draftId != null && sourceMode == TransactionSourceMode.EXTERNAL ->
                    "外部证据 · 已记账"

                draftId != null -> "手工录入 · 已记账"
                type == TransactionType.ADJUSTMENT -> "期初余额 · 本地账本"
                else -> "本地账本"
            },
            amount = amount,
            kind = kind,
            canUndo = draftId != null,
        )
    }

    private fun LedgerAccount.canFund(draft: ManualDraft): Boolean {
        if (
            isSystem ||
            isArchived ||
            currency != draft.amount.currency ||
            !type.allowsUserAccountCurrency(currency)
        ) return false
        return when (draft.type) {
            TransactionType.EXPENSE -> this.type in creatableAccountTypes
            TransactionType.INCOME -> this.type == AccountType.ASSET_CASH ||
                this.type == AccountType.ASSET_BANK ||
                this.type == AccountType.ASSET_EWALLET_BALANCE

            else -> false
        }
    }

    private fun audit(
        commandId: CommandId,
        suffix: String,
        action: AuditAction,
        entityType: String,
        entityId: String,
        occurredAt: Instant,
    ) = AuditRecord(
        id = AuditEventId("audit:$suffix:${commandId.value}"),
        commandId = commandId,
        action = action,
        entityType = entityType,
        entityId = entityId,
        occurredAt = occurredAt,
    )

    private companion object {
        const val MAX_ACCOUNT_NAME_LENGTH = 40
        const val MAX_COUNTERPARTY_LENGTH = 80
        const val MAX_NOTE_LENGTH = 200
        const val MAX_SOURCE_PROPOSAL_ID_LENGTH = 160
        const val MAX_RECENT_TRANSACTIONS = 20
        val whitespace = Regex("\\s+")
        val creatableAccountTypes = setOf(
            AccountType.ASSET_CASH,
            AccountType.ASSET_BANK,
            AccountType.ASSET_EWALLET_BALANCE,
            AccountType.LIABILITY_CC,
        )
    }
}

private object MoneyInput {
    private val decimalPattern = Regex("(?:0|[1-9]\\d{0,14})(?:\\.\\d{1,2})?")

    fun parsePositive(text: String, currency: CurrencyCode): Money? =
        parse(text, currency)?.takeIf { it.minorUnits > 0L }

    fun parseNonNegative(text: String, currency: CurrencyCode): Money? {
        if (text.isBlank()) return Money(0L, currency)
        return parse(text, currency)
    }

    private fun parse(text: String, currency: CurrencyCode): Money? {
        val normalized = text.trim()
        if (!decimalPattern.matches(normalized)) return null
        return runCatching {
            val minorUnits = BigDecimal(normalized)
                .movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
            Money(minorUnits, currency)
        }.getOrNull()
    }
}

private fun RepositoryWriteResult.toOperationResult(): OperationResult = when (status) {
    RepositoryWriteStatus.APPLIED,
    RepositoryWriteStatus.ALREADY_APPLIED,
    -> OperationResult.Success(entityId)

    RepositoryWriteStatus.DUPLICATE_NAME ->
        OperationResult.Failure(OperationError.DUPLICATE_ACCOUNT_NAME)

    RepositoryWriteStatus.NOT_FOUND -> OperationResult.Failure(OperationError.NOT_FOUND)
    RepositoryWriteStatus.ACCOUNT_NOT_FOUND ->
        OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)

    RepositoryWriteStatus.INVALID_STATE -> OperationResult.Failure(OperationError.INVALID_STATE)
    RepositoryWriteStatus.COMMAND_COLLISION -> OperationResult.Failure(OperationError.CONFLICT)
}

private fun PostingBuildResult.toOperationError(): OperationError = when (this) {
    PostingBuildResult.InvalidAmount -> OperationError.INVALID_AMOUNT
    is PostingBuildResult.InvalidAccountType -> OperationError.UNSUPPORTED_ACCOUNT_TYPE
    is PostingBuildResult.CurrencyMismatch,
    is PostingBuildResult.UnsupportedCurrency,
    -> OperationError.UNSUPPORTED_CURRENCY

    is PostingBuildResult.ValidationFailed -> OperationError.INVALID_STATE
    is PostingBuildResult.Valid -> error("A valid posting has no operation error")
}

private fun List<AccountBalance>.sumMinorUnitsExact(): Long {
    val sum = fold(BigInteger.ZERO) { total, accountBalance ->
        total + BigInteger.valueOf(accountBalance.balance.minorUnits)
    }
    return sum.longValueExact()
}

private fun AccountType.isAssetLike(): Boolean = when (this) {
    AccountType.ASSET_CASH,
    AccountType.ASSET_BANK,
    AccountType.ASSET_EWALLET_BALANCE,
    AccountType.ASSET_WRAPPER,
    AccountType.INVESTMENT_CASH,
    AccountType.INVESTMENT_SECURITY,
    -> true

    else -> false
}

private fun AccountType.isLiabilityLike(): Boolean =
    this == AccountType.LIABILITY_CC || this == AccountType.LIABILITY_LOAN

private fun String.hasControlCharacter(): Boolean = any(Char::isISOControl)

private fun Money.absoluteExact(): Money = copy(minorUnits = Math.absExact(minorUnits))

private fun Money?.orZeroCny(): Money = this ?: Money.cny(0L)
