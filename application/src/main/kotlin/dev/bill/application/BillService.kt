package dev.bill.application

import dev.bill.core.domain.AccountBalance
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
import dev.bill.core.domain.LedgerRepository
import dev.bill.core.domain.LedgerState
import dev.bill.core.domain.InvestmentPosition
import dev.bill.core.domain.InvestmentPositionId
import dev.bill.core.domain.InvestmentPositionSourceMode
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.ObservedChannel
import dev.bill.core.domain.PostedTransaction
import dev.bill.core.domain.RepositoryWriteResult
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.core.domain.ReconciliationDraftLink
import dev.bill.core.domain.ReconciliationDraftRole
import dev.bill.core.domain.ReconciliationKind
import dev.bill.core.domain.ReconciliationResolution
import dev.bill.core.domain.RelationDecision
import dev.bill.core.domain.ReviewDraft
import dev.bill.core.domain.SystemAccountIds
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.domain.TransactionStatus
import dev.bill.core.domain.supportsBalanceSnapshots
import dev.bill.core.domain.TransactionRelation
import dev.bill.core.domain.TransactionRelationId
import dev.bill.core.domain.TransactionRelationType
import dev.bill.core.domain.allowsCurrency
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
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dev.bill.source.review.SourceProposalRecord
import dev.bill.source.review.SourceReviewRepository
import dev.bill.source.review.allowedExternalDraftCurrencies
import dev.bill.source.review.allowedExternalDraftTypes
import dev.bill.source.review.allowsExternalDraftCurrency
import dev.bill.source.review.allowsExternalDraftType
import dev.bill.source.contract.ObservedTime
import dev.bill.source.contract.GenericDelimitedStatementIdentity
import dev.bill.source.contract.SourceFamily

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
    INVALID_INSTRUMENT_CODE,
    INVALID_UNITS,
    INVALID_COST_BASIS,
    INVALID_TIME,
}

sealed interface OperationResult {
    data class Success(val entityId: String?) : OperationResult

    data class Failure(val error: OperationError) : OperationResult
}

/** Resolves only a locally bundled safe notification route label from opaque provenance. */
fun interface NotificationRouteLabelResolver {
    fun labelFor(connectorId: String): String?

    data object Empty : NotificationRouteLabelResolver {
        override fun labelFor(connectorId: String): String? = null
    }
}

private fun ObservedTime.resolveForReview(localZoneId: ZoneId): Instant = when (this) {
    is ObservedTime.DateOnly -> date.atStartOfDay(localZoneId).toInstant()
    is ObservedTime.DateTime -> resolvedInstant
        ?: offset?.let(localDateTime::toInstant)
        ?: zoneId?.let { zone -> localDateTime.atZone(zone).toInstant() }
        ?: localDateTime.atZone(localZoneId).toInstant()
}

data class CreateAccountCommand(
    val commandId: CommandId,
    val name: String,
    val type: AccountType,
    val openingBalanceText: String,
    val currency: CurrencyCode = CurrencyCode.CNY,
)

data class CreateInvestmentPositionCommand(
    val commandId: CommandId,
    val name: String,
    val instrumentCode: String?,
    val currentValueText: String,
    val unitsText: String?,
    val costBasisText: String?,
    val asOf: Instant? = null,
    val sourceMode: InvestmentPositionSourceMode = InvestmentPositionSourceMode.MANUAL,
)

data class CreateBalanceSnapshotCommand(
    val commandId: CommandId,
    val accountId: AccountId,
    val observedBalanceText: String,
    val asOfText: String,
    val note: String?,
)

data class CreateManualDraftCommand(
    val commandId: CommandId,
    val type: TransactionType,
    val amountText: String,
    val counterparty: String,
    val note: String?,
    val occurredAt: Instant? = null,
    val currency: CurrencyCode = CurrencyCode.CNY,
    val observedChannel: ObservedChannel = ObservedChannel.UNKNOWN,
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
    val investmentAccountId: AccountId? = null,
    val observedChannel: ObservedChannel = ObservedChannel.UNKNOWN,
)

data class UpdateDraftCommand(
    val commandId: CommandId,
    val draftId: DraftId,
    val type: TransactionType,
    val amountText: String,
    val counterparty: String,
    val note: String?,
    val occurredAt: Instant,
    val observedChannel: ObservedChannel,
    val fundingAccountId: AccountId?,
    val investmentAccountId: AccountId? = null,
)

data class ResolveReconciliationCommand(
    val commandId: CommandId,
    val caseId: String,
)

private data class FundedByKey(
    val amount: Money,
    val fundingAccountId: AccountId,
    val normalizedCounterparty: String,
)

class BillService(
    private val repository: LedgerRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val sourceReviewRepository: SourceReviewRepository = SourceReviewRepository.Empty,
    private val notificationRouteLabelResolver: NotificationRouteLabelResolver =
        NotificationRouteLabelResolver.Empty,
    private val localZoneId: ZoneId = ZoneId.systemDefault(),
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

    suspend fun createInvestmentPosition(
        command: CreateInvestmentPositionCommand,
    ): OperationResult {
        if (command.name.hasControlCharacter()) {
            return OperationResult.Failure(OperationError.INVALID_NAME)
        }
        val name = command.name.trim().replace(whitespace, " ")
        if (
            name.isBlank() ||
            name.length > MAX_INVESTMENT_NAME_LENGTH ||
            name.hasControlCharacter()
        ) {
            return OperationResult.Failure(OperationError.INVALID_NAME)
        }
        val instrumentCode = command.instrumentCode
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.uppercase(Locale.ROOT)
        if (
            instrumentCode != null &&
            !investmentCodePattern.matches(instrumentCode)
        ) {
            return OperationResult.Failure(OperationError.INVALID_INSTRUMENT_CODE)
        }
        val currentValue = MoneyInput.parsePositive(
            command.currentValueText,
            CurrencyCode.CNY,
        ) ?: return OperationResult.Failure(OperationError.INVALID_AMOUNT)
        val units = command.unitsText
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(InvestmentUnitsInput::parse)
        if (!command.unitsText.isNullOrBlank() && units == null) {
            return OperationResult.Failure(OperationError.INVALID_UNITS)
        }
        val costBasis = if (command.costBasisText.isNullOrBlank()) {
            null
        } else {
            MoneyInput.parsePositive(command.costBasisText, CurrencyCode.CNY)
                ?: return OperationResult.Failure(OperationError.INVALID_COST_BASIS)
        }

        val now = clock.instant()
        val asOf = command.asOf?.coerceAtMost(now) ?: now
        val accountId = AccountId("account:investment:${command.commandId.value}")
        val position = InvestmentPosition(
            id = InvestmentPositionId("investment:${command.commandId.value}"),
            accountId = accountId,
            instrumentCode = instrumentCode,
            name = name,
            currentValue = currentValue,
            units = units,
            costBasis = costBasis,
            asOf = asOf,
            sourceMode = command.sourceMode,
            createdAt = now,
            updatedAt = now,
            creationCommandId = command.commandId,
        )
        val normalizedKey = instrumentCode?.lowercase(Locale.ROOT)
            ?: name.lowercase(Locale.ROOT)
        val account = LedgerAccount(
            id = accountId,
            name = name,
            normalizedName = "investment:$normalizedKey",
            type = AccountType.INVESTMENT_SECURITY,
            currency = CurrencyCode.CNY,
            isSystem = false,
            isArchived = false,
            createdAt = now,
            creationCommandId = command.commandId,
        )
        val validated = when (
            val posting = PostingFactory.openingBalance(
                account = account,
                visibleBalance = currentValue,
                transactionId = TransactionId(
                    "transaction:investment-opening:${command.commandId.value}",
                ),
                occurredAt = asOf,
            )
        ) {
            is PostingBuildResult.Valid -> posting.transaction
            else -> return OperationResult.Failure(posting.toOperationError())
        }
        val openingTransaction = PostedTransaction(
            id = validated.id,
            draftId = null,
            type = validated.type,
            status = TransactionStatus.ACTIVE,
            sourceMode = TransactionSourceMode.MANUAL,
            occurredAt = validated.occurredAt,
            confirmedAt = now,
            title = "$name 持仓快照",
            note = null,
            commandId = command.commandId,
            entries = validated.entries,
        )
        return repository.createInvestmentPosition(
            position = position,
            account = account,
            openingTransaction = openingTransaction,
            auditRecords = listOf(
                audit(
                    commandId = command.commandId,
                    suffix = "investment-account-created",
                    action = AuditAction.ACCOUNT_CREATED,
                    entityType = "account",
                    entityId = account.id.value,
                    occurredAt = now,
                ),
                audit(
                    commandId = command.commandId,
                    suffix = "investment-opening-posted",
                    action = AuditAction.OPENING_BALANCE_POSTED,
                    entityType = "transaction",
                    entityId = openingTransaction.id.value,
                    occurredAt = now,
                ),
                audit(
                    commandId = command.commandId,
                    suffix = "investment-position-created",
                    action = AuditAction.INVESTMENT_POSITION_CREATED,
                    entityType = "investment_position",
                    entityId = position.id.value,
                    occurredAt = now,
                ),
            ),
        ).toOperationResult()
    }

    suspend fun createBalanceSnapshot(
        command: CreateBalanceSnapshotCommand,
    ): OperationResult {
        val account = repository.findAccount(command.accountId)
            ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
        if (
            account.isSystem ||
            account.isArchived ||
            !account.type.supportsBalanceSnapshots() ||
            !account.type.allowsUserAccountCurrency(account.currency)
        ) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_ACCOUNT_TYPE)
        }
        if (command.observedBalanceText.isBlank()) {
            return OperationResult.Failure(OperationError.INVALID_AMOUNT)
        }
        val observedBalance = MoneyInput.parseNonNegative(
            command.observedBalanceText,
            account.currency,
        ) ?: return OperationResult.Failure(OperationError.INVALID_AMOUNT)
        val note = command.note?.trim()?.takeIf(String::isNotEmpty)
        if (note != null && (note.length > MAX_NOTE_LENGTH || note.hasControlCharacter())) {
            return OperationResult.Failure(OperationError.NOTE_TOO_LONG)
        }
        val asOf = BalanceSnapshotTimeInput.parse(command.asOfText, localZoneId)
            ?: return OperationResult.Failure(OperationError.INVALID_TIME)
        val now = clock.instant()
        if (asOf > now) {
            return OperationResult.Failure(OperationError.INVALID_TIME)
        }

        val snapshot = BalanceSnapshot(
            id = BalanceSnapshotId("balance-snapshot:${command.commandId.value}"),
            accountId = account.id,
            observedBalance = observedBalance,
            asOf = asOf,
            recordedAt = now,
            note = note,
            sourceMode = BalanceSnapshotSourceMode.MANUAL,
            creationCommandId = command.commandId,
        )
        return repository.createBalanceSnapshot(
            snapshot = snapshot,
            auditRecord = audit(
                commandId = command.commandId,
                suffix = "balance-snapshot-recorded",
                action = AuditAction.BALANCE_SNAPSHOT_RECORDED,
                entityType = "balance_snapshot",
                entityId = snapshot.id.value,
                occurredAt = now,
            ),
        ).toOperationResult()
    }

    suspend fun createManualDraft(command: CreateManualDraftCommand): OperationResult {
        if (
            command.type != TransactionType.EXPENSE &&
            command.type != TransactionType.INCOME
        ) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }
        if (!command.currency.isSupportedLedgerCurrency()) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_CURRENCY)
        }
        if (!command.observedChannel.allowsCurrency(command.currency)) {
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
            observedChannel = command.observedChannel,
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
        if (
            command.type != TransactionType.EXPENSE &&
            command.type != TransactionType.INCOME &&
            command.type != TransactionType.INVEST_BUY
        ) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }
        if (!command.currency.isSupportedLedgerCurrency()) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_CURRENCY)
        }
        if (!command.observedChannel.allowsCurrency(command.currency)) {
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
        if (!sourceProposal.allowsExternalDraftType(command.type)) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }

        val investmentAccount = when (command.type) {
            TransactionType.INVEST_BUY -> {
                val investmentAccountId = command.investmentAccountId
                    ?: return OperationResult.Failure(OperationError.ACCOUNT_REQUIRED)
                repository.findAccount(investmentAccountId)
                    ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
            }

            TransactionType.EXPENSE,
            TransactionType.INCOME,
            -> {
                if (command.investmentAccountId != null) {
                    return OperationResult.Failure(OperationError.INVALID_STATE)
                }
                null
            }
        }
        if (
            investmentAccount != null &&
            (
                investmentAccount.type != AccountType.INVESTMENT_SECURITY ||
                    investmentAccount.currency != command.currency ||
                    investmentAccount.isSystem ||
                    investmentAccount.isArchived
            )
        ) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_ACCOUNT_TYPE)
        }
        if (
            investmentAccount != null &&
            repository.findInvestmentPositionByAccountId(investmentAccount.id) == null
        ) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
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
            investmentAccountId = investmentAccount?.id,
            createdAt = now,
            updatedAt = now,
            creationCommandId = command.commandId,
            sourceMode = TransactionSourceMode.EXTERNAL,
            observedChannel = command.observedChannel,
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

    suspend fun updateDraft(command: UpdateDraftCommand): OperationResult {
        val existing = repository.findDraft(command.draftId)
            ?: return OperationResult.Failure(OperationError.NOT_FOUND)
        if (
            command.type != TransactionType.EXPENSE &&
            command.type != TransactionType.INCOME &&
            command.type != TransactionType.INVEST_BUY
        ) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }
        if (!command.observedChannel.allowsCurrency(existing.amount.currency)) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_CURRENCY)
        }
        val amount = MoneyInput.parsePositive(command.amountText, existing.amount.currency)
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
        if (command.occurredAt > now) {
            return OperationResult.Failure(OperationError.INVALID_TIME)
        }

        val investmentAccount = when (command.type) {
            TransactionType.INVEST_BUY -> {
                val investmentId = command.investmentAccountId
                    ?: return OperationResult.Failure(OperationError.ACCOUNT_REQUIRED)
                repository.findAccount(investmentId)
                    ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
            }

            TransactionType.EXPENSE,
            TransactionType.INCOME,
            -> {
                if (command.investmentAccountId != null) {
                    return OperationResult.Failure(OperationError.INVALID_STATE)
                }
                null
            }

        }
        if (
            investmentAccount != null &&
            (
                investmentAccount.type != AccountType.INVESTMENT_SECURITY ||
                    investmentAccount.currency != existing.amount.currency ||
                    investmentAccount.isSystem ||
                    investmentAccount.isArchived ||
                    repository.findInvestmentPositionByAccountId(investmentAccount.id) == null
                )
        ) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_ACCOUNT_TYPE)
        }

        val replacement = existing.copy(
            state = DraftState.EDITED,
            type = command.type,
            amount = amount,
            occurredAt = command.occurredAt,
            counterparty = counterparty,
            note = note,
            fundingAccountId = command.fundingAccountId,
            investmentAccountId = investmentAccount?.id,
            updatedAt = now,
            observedChannel = command.observedChannel,
        )
        val fundingAccount = command.fundingAccountId?.let { accountId ->
            repository.findAccount(accountId)
                ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
        }
        if (fundingAccount != null && !fundingAccount.canFund(replacement)) {
            return OperationResult.Failure(OperationError.UNSUPPORTED_ACCOUNT_TYPE)
        }
        return repository.updateDraft(
            draft = replacement,
            auditRecord = audit(
                commandId = command.commandId,
                suffix = "draft-edited",
                action = AuditAction.DRAFT_EDITED,
                entityType = "draft",
                entityId = replacement.id.value,
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
        val investmentAccount = draft.investmentAccountId?.let { accountId ->
            repository.findAccount(accountId)
                ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
        }
        if (
            investmentAccount != null &&
            repository.findInvestmentPositionByAccountId(investmentAccount.id) == null
        ) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }
        val now = clock.instant()
        val validated = when (
            val posting = PostingFactory.manualDraft(
                draft = draft,
                fundingAccount = fundingAccount,
                investmentAccount = investmentAccount,
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

    suspend fun resolveReconciliation(
        command: ResolveReconciliationCommand,
    ): OperationResult {
        if (
            command.caseId.isBlank() ||
            command.caseId.length > MAX_RECONCILIATION_CASE_ID_LENGTH ||
            command.caseId.hasControlCharacter()
        ) {
            return OperationResult.Failure(OperationError.INVALID_STATE)
        }
        val transactionId = TransactionId(
            "transaction:reconcile:${stableDigest(command.commandId.value, command.caseId)}",
        )
        repository.findTransaction(transactionId)?.let { existing ->
            return if (existing.commandId == command.commandId) {
                OperationResult.Success(existing.id.value)
            } else {
                OperationResult.Failure(OperationError.CONFLICT)
            }
        }

        val state = repository.observeState().first()
        val case = reconciliationCases(state).firstOrNull { it.id == command.caseId }
            ?: return OperationResult.Failure(OperationError.NOT_FOUND)
        val draftsById = state.pendingDrafts.associateBy { it.id.value }
        val accountsById = state.accountBalances.associateBy { it.account.id.value }
        val transactionsById = state.recentTransactions.associateBy { it.id.value }
        val now = clock.instant()

        val build = when (case.kind) {
            ReconciliationCaseKind.TRANSFER -> {
                val outbound = draftsById[case.draftIds.getOrNull(0)]
                    ?: return OperationResult.Failure(OperationError.NOT_FOUND)
                val inbound = draftsById[case.draftIds.getOrNull(1)]
                    ?: return OperationResult.Failure(OperationError.NOT_FOUND)
                val source = case.sourceAccountId
                    ?.let(accountsById::get)
                    ?.account
                    ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
                val destination = accountsById[case.destinationAccountId]?.account
                    ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
                PostingFactory.transferPair(
                    outboundDraft = outbound,
                    inboundDraft = inbound,
                    sourceAccount = source,
                    destinationAccount = destination,
                    transactionId = transactionId,
                    confirmedAt = now,
                )
            }

            ReconciliationCaseKind.LIABILITY_REPAYMENT -> {
                val outbound = draftsById[case.draftIds.singleOrNull()]
                    ?: return OperationResult.Failure(OperationError.NOT_FOUND)
                val source = case.sourceAccountId
                    ?.let(accountsById::get)
                    ?.account
                    ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
                val liability = accountsById[case.destinationAccountId]?.account
                    ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
                PostingFactory.liabilityRepayment(
                    outboundDraft = outbound,
                    sourceAccount = source,
                    liabilityAccount = liability,
                    transactionId = transactionId,
                    confirmedAt = now,
                )
            }

            ReconciliationCaseKind.REFUND -> {
                val inbound = draftsById[case.draftIds.singleOrNull()]
                    ?: return OperationResult.Failure(OperationError.NOT_FOUND)
                val destination = accountsById[case.destinationAccountId]?.account
                    ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
                val originalId = case.relatedTransactionId
                    ?: return OperationResult.Failure(OperationError.INVALID_STATE)
                val original = transactionsById[originalId]
                    ?: repository.findTransaction(TransactionId(originalId))
                    ?: return OperationResult.Failure(OperationError.NOT_FOUND)
                val refunded = repository.activeRefundTotal(original.id)
                    ?: return OperationResult.Failure(OperationError.INVALID_STATE)
                PostingFactory.refund(
                    inboundDraft = inbound,
                    destinationAccount = destination,
                    originalExpense = original,
                    alreadyRefundedMinorUnits = refunded.minorUnits,
                    transactionId = transactionId,
                    confirmedAt = now,
                )
            }

            ReconciliationCaseKind.FUNDED_BY -> {
                val channelDraft = draftsById[case.draftIds.getOrNull(0)]
                    ?: return OperationResult.Failure(OperationError.NOT_FOUND)
                val bankEvidenceDraft = draftsById[case.draftIds.getOrNull(1)]
                    ?: return OperationResult.Failure(OperationError.NOT_FOUND)
                val funding = case.sourceAccountId
                    ?.let(accountsById::get)
                    ?.account
                    ?: return OperationResult.Failure(OperationError.ACCOUNT_NOT_FOUND)
                PostingFactory.fundedExpense(
                    channelDraft = channelDraft,
                    bankEvidenceDraft = bankEvidenceDraft,
                    fundingAccount = funding,
                    transactionId = transactionId,
                    confirmedAt = now,
                )
            }
        }
        val validated = (build as? PostingBuildResult.Valid)?.transaction
            ?: return OperationResult.Failure(build.toOperationError())
        val inputDrafts = case.draftIds.mapNotNull(draftsById::get)
        if (inputDrafts.size != case.draftIds.size) {
            return OperationResult.Failure(OperationError.NOT_FOUND)
        }
        val sourceMode = if (
            inputDrafts.any { it.sourceMode == TransactionSourceMode.EXTERNAL }
        ) {
            TransactionSourceMode.EXTERNAL
        } else {
            TransactionSourceMode.MANUAL
        }
        val transaction = PostedTransaction(
            id = validated.id,
            draftId = null,
            type = validated.type,
            status = TransactionStatus.ACTIVE,
            sourceMode = sourceMode,
            occurredAt = validated.occurredAt,
            confirmedAt = now,
            title = case.title,
            note = null,
            commandId = command.commandId,
            entries = validated.entries,
        )
        val draftLinks = when (case.kind) {
            ReconciliationCaseKind.TRANSFER -> listOf(
                ReconciliationDraftLink(
                    DraftId(case.draftIds[0]),
                    ReconciliationDraftRole.TRANSFER_OUTBOUND,
                ),
                ReconciliationDraftLink(
                    DraftId(case.draftIds[1]),
                    ReconciliationDraftRole.TRANSFER_INBOUND,
                ),
            )

            ReconciliationCaseKind.LIABILITY_REPAYMENT -> listOf(
                ReconciliationDraftLink(
                    DraftId(case.draftIds.single()),
                    ReconciliationDraftRole.REPAYMENT_OUTBOUND,
                ),
            )

            ReconciliationCaseKind.REFUND -> listOf(
                ReconciliationDraftLink(
                    DraftId(case.draftIds.single()),
                    ReconciliationDraftRole.REFUND_INBOUND,
                ),
            )

            ReconciliationCaseKind.FUNDED_BY -> listOf(
                ReconciliationDraftLink(
                    DraftId(case.draftIds[0]),
                    ReconciliationDraftRole.FUNDED_CHANNEL_EXPENSE,
                ),
                ReconciliationDraftLink(
                    DraftId(case.draftIds[1]),
                    ReconciliationDraftRole.FUNDED_BANK_EVIDENCE,
                ),
            )
        }
        val relations = if (case.kind == ReconciliationCaseKind.REFUND) {
            listOf(
                TransactionRelation(
                    id = TransactionRelationId(
                        "relation:refund:${stableDigest(command.commandId.value, case.id)}",
                    ),
                    fromTransactionId = transaction.id,
                    toTransactionId = TransactionId(requireNotNull(case.relatedTransactionId)),
                    type = TransactionRelationType.REFUNDS,
                    decision = RelationDecision.USER_CONFIRMED,
                    createdAt = now,
                ),
            )
        } else {
            emptyList()
        }
        return repository.resolveReconciliation(
            resolution = ReconciliationResolution(
                kind = when (case.kind) {
                    ReconciliationCaseKind.TRANSFER -> ReconciliationKind.TRANSFER_PAIR
                    ReconciliationCaseKind.LIABILITY_REPAYMENT ->
                        ReconciliationKind.LIABILITY_REPAYMENT

                    ReconciliationCaseKind.REFUND -> ReconciliationKind.REFUND
                    ReconciliationCaseKind.FUNDED_BY -> ReconciliationKind.FUNDED_BY
                },
                transaction = transaction,
                draftLinks = draftLinks,
                relations = relations,
            ),
            auditRecord = audit(
                commandId = command.commandId,
                suffix = "reconciliation-confirmed",
                action = AuditAction.RECONCILIATION_CONFIRMED,
                entityType = "transaction",
                entityId = transaction.id.value,
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

        val balanceComparisonsByAccountId = state.balanceSnapshotComparisons
            .associateBy { comparison -> comparison.snapshot.accountId.value }
        check(balanceComparisonsByAccountId.size == state.balanceSnapshotComparisons.size) {
            "Ledger snapshot contains duplicate latest balance snapshots"
        }
        check(
            balanceComparisonsByAccountId.keys.all { accountId ->
                userBalances.any { balance -> balance.account.id.value == accountId }
            },
        ) { "Ledger snapshot contains a balance snapshot for an unavailable account" }

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
                    latestBalanceSnapshot = balanceComparisonsByAccountId[
                        accountBalance.account.id.value
                    ]?.let { comparison ->
                        BalanceSnapshotSummary(
                            observedBalance = comparison.snapshot.observedBalance,
                            ledgerBalance = comparison.ledgerBalance,
                            difference = comparison.difference,
                            asOf = comparison.snapshot.asOf,
                            recordedAt = comparison.snapshot.recordedAt,
                            note = comparison.snapshot.note,
                            status = if (comparison.isReconciled) {
                                BalanceSnapshotStatus.RECONCILED
                            } else {
                                BalanceSnapshotStatus.NEEDS_EXPLANATION
                            },
                        )
                    },
                )
            }

        val pendingDrafts = state.pendingDrafts
            .filter { it.state == DraftState.WAITING_USER || it.state == DraftState.EDITED }
            .sortedWith(compareByDescending<ManualDraft> { it.updatedAt }.thenBy { it.id.value })
            .map { draft ->
                DraftSummary(
                    id = draft.id.value,
                    kind = when (draft.type) {
                        TransactionType.EXPENSE -> DraftSummaryKind.EXPENSE
                        TransactionType.INCOME -> DraftSummaryKind.INCOME
                        TransactionType.INVEST_BUY -> DraftSummaryKind.INVEST_BUY
                        else -> error("Unsupported review draft type: ${draft.type}")
                    },
                    amount = draft.amount,
                    counterparty = draft.counterparty,
                    note = draft.note,
                    fundingAccountId = draft.fundingAccountId?.value,
                    investmentAccountId = draft.investmentAccountId?.value,
                    occurredAt = draft.occurredAt,
                    sourceMode = draft.sourceMode,
                    observedChannel = draft.observedChannel,
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
                        if (
                            record.parserId == GenericDelimitedStatementIdentity.PARSER_ID &&
                            record.connectorId == GenericDelimitedStatementIdentity.CONNECTOR_ID
                        ) {
                            SourceReviewKind.DELIMITED_STATEMENT_ROW
                        } else {
                            SourceReviewKind.SELECTED_TEXT_FILE
                        }

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
                    notificationRouteLabel = if (kind == SourceReviewKind.NOTIFICATION) {
                        try {
                            notificationRouteLabelResolver.labelFor(record.connectorId)
                        } catch (_: RuntimeException) {
                            null
                        }
                    } else {
                        null
                    },
                    capturedAt = record.capturedAt,
                    suggestedAmount = record.candidate?.amount?.value,
                    allowedDraftCurrencies = record.allowedExternalDraftCurrencies(),
                    suggestedKind = record.suggestedDraftKind(),
                    allowedDraftKinds = record.allowedExternalDraftTypes()
                        .mapNotNull { type -> type.toDraftSummaryKindOrNull() }
                        .toSet(),
                    suggestedCounterparty = record.candidate?.counterparty?.value,
                    suggestedOccurredAt = record.candidate?.occurredAt?.value
                        ?.resolveForReview(localZoneId),
                    suggestedObservedChannel = record.sourceFamily.toObservedChannel(),
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
        val investmentAccountIds = state.investmentPositions
            .mapTo(hashSetOf()) { position -> position.accountId.value }
        val ledgerHardBlockCount = pendingDrafts.count { draft ->
            val fundingAccount = draft.fundingAccountId?.let(accountsById::get)
            val fundingInvalid = fundingAccount == null || !fundingAccount.canFund(draft)
            val investmentInvalid = when (draft.kind) {
                DraftSummaryKind.EXPENSE,
                DraftSummaryKind.INCOME,
                -> draft.investmentAccountId != null

                DraftSummaryKind.INVEST_BUY -> {
                    val investmentAccount = draft.investmentAccountId?.let(accountsById::get)
                    investmentAccount == null ||
                        investmentAccount.type != AccountType.INVESTMENT_SECURITY ||
                        investmentAccount.id !in investmentAccountIds ||
                        investmentAccount.displayBalance.currency != draft.amount.currency ||
                        investmentAccount.id == draft.fundingAccountId
                }
            }
            fundingInvalid || investmentInvalid
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
            reconciliationCases = reconciliationCases(state),
            investmentPositions = state.investmentPositions.map { position ->
                InvestmentPositionSummary(
                    id = position.id.value,
                    accountId = position.accountId.value,
                    instrumentCode = position.instrumentCode,
                    name = position.name,
                    currentValue = position.currentValue,
                    units = position.units,
                    costBasis = position.costBasis,
                    asOf = position.asOf,
                    wasOcrPrefilled = position.sourceMode == InvestmentPositionSourceMode.OCR,
                )
            },
        )
    }

    private fun reconciliationCases(state: LedgerState): List<ReconciliationCaseSummary> {
        val accountsById = state.accountBalances.associateBy { it.account.id }
        val pending = state.pendingDrafts.asSequence()
            .filter {
                it.state == DraftState.WAITING_USER || it.state == DraftState.EDITED
            }
            .sortedWith(
                compareByDescending<ManualDraft> { it.updatedAt }.thenBy { it.id.value },
            )
            .take(MAX_RECONCILIATION_INPUT_DRAFTS)
            .toList()
        val outboundDrafts = pending.filter { it.type == TransactionType.EXPENSE }
        val inboundDrafts = pending.filter { it.type == TransactionType.INCOME }
        val inboundDraftsByAmount = inboundDrafts.groupBy(ManualDraft::amount)
        val refundSourceTransactions = state.recentTransactions.asSequence()
            .sortedWith(
                compareByDescending<PostedTransaction> { it.confirmedAt }
                    .thenBy { it.id.value },
            )
            .take(MAX_RECONCILIATION_INPUT_TRANSACTIONS)
            .toList()
        val cases = mutableListOf<ReconciliationCaseSummary>()

        outboundDrafts.forEach { outbound ->
            val source = outbound.fundingAccountId?.let(accountsById::get)?.account
                ?: return@forEach
            if (source.type !in assetMovementTypes || source.isArchived || source.isSystem) {
                return@forEach
            }
            inboundDraftsByAmount[outbound.amount].orEmpty().asSequence()
                .filter { inbound ->
                    withinWindow(
                        outbound.occurredAt,
                        inbound.occurredAt,
                        TRANSFER_MATCH_WINDOW,
                    )
                }
                .mapNotNull { inbound ->
                    val destination = inbound.fundingAccountId
                        ?.let(accountsById::get)
                        ?.account
                        ?: return@mapNotNull null
                    destination.takeIf {
                        it.id != source.id &&
                            it.type in assetMovementTypes &&
                            !it.isArchived &&
                            !it.isSystem &&
                            it.currency == source.currency
                    }?.let { inbound to it }
                }
                .sortedBy { (inbound, _) ->
                    timeDistanceMillis(outbound.occurredAt, inbound.occurredAt)
                }
                .take(MAX_CASES_PER_DRAFT)
                .forEach { (inbound, destination) ->
                    cases += ReconciliationCaseSummary(
                        id = reconciliationCaseId(
                            ReconciliationCaseKind.TRANSFER,
                            outbound.id.value,
                            inbound.id.value,
                        ),
                        kind = ReconciliationCaseKind.TRANSFER,
                        amount = outbound.amount,
                        occurredAt = minOf(outbound.occurredAt, inbound.occurredAt),
                        title = "${source.name} → ${destination.name}",
                        draftIds = listOf(outbound.id.value, inbound.id.value),
                        sourceAccountId = source.id.value,
                        destinationAccountId = destination.id.value,
                        relatedTransactionId = null,
                    )
                }

            state.accountBalances.asSequence()
                .map(AccountBalance::account)
                .filter { account ->
                    source.type == AccountType.ASSET_BANK &&
                        account.type == AccountType.LIABILITY_CC &&
                        !account.isArchived &&
                        !account.isSystem &&
                        account.currency == outbound.amount.currency
                }
                .sortedWith(
                    compareBy<LedgerAccount> { it.createdAt }
                        .thenBy { it.id.value },
                )
                .take(MAX_CASES_PER_DRAFT)
                .forEach { liability ->
                    cases += ReconciliationCaseSummary(
                        id = reconciliationCaseId(
                            ReconciliationCaseKind.LIABILITY_REPAYMENT,
                            outbound.id.value,
                            liability.id.value,
                        ),
                        kind = ReconciliationCaseKind.LIABILITY_REPAYMENT,
                        amount = outbound.amount,
                        occurredAt = outbound.occurredAt,
                        title = "${source.name} → ${liability.name}",
                        draftIds = listOf(outbound.id.value),
                        sourceAccountId = source.id.value,
                        destinationAccountId = liability.id.value,
                        relatedTransactionId = null,
                    )
                }
        }

        val fundedBankDraftsByKey = outboundDrafts.asSequence()
            .filter { draft ->
                draft.sourceMode == TransactionSourceMode.EXTERNAL &&
                    draft.observedChannel == ObservedChannel.BANK
            }
            .mapNotNull { draft ->
                val accountId = draft.fundingAccountId ?: return@mapNotNull null
                val account = accountsById[accountId]?.account ?: return@mapNotNull null
                if (
                    account.isSystem ||
                    account.isArchived ||
                    account.type !in fundedByAccountTypes ||
                    account.currency != draft.amount.currency
                ) {
                    return@mapNotNull null
                }
                fundedByKey(draft) to draft
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        outboundDrafts.asSequence()
            .filter { draft ->
                draft.sourceMode == TransactionSourceMode.EXTERNAL &&
                    draft.observedChannel in walletObservedChannels
            }
            .forEach { channelDraft ->
                val fundingId = channelDraft.fundingAccountId ?: return@forEach
                val funding = accountsById[fundingId]?.account ?: return@forEach
                if (
                    funding.isSystem ||
                    funding.isArchived ||
                    funding.type !in fundedByAccountTypes ||
                    funding.currency != channelDraft.amount.currency
                ) {
                    return@forEach
                }
                fundedBankDraftsByKey[fundedByKey(channelDraft)].orEmpty().asSequence()
                    .filter { bankDraft ->
                        bankDraft.id != channelDraft.id &&
                            withinWindow(
                                channelDraft.occurredAt,
                                bankDraft.occurredAt,
                                FUNDED_BY_MATCH_WINDOW,
                            )
                    }
                    .sortedBy { bankDraft ->
                        timeDistanceMillis(channelDraft.occurredAt, bankDraft.occurredAt)
                    }
                    .take(MAX_CASES_PER_DRAFT)
                    .forEach { bankDraft ->
                        cases += ReconciliationCaseSummary(
                            id = reconciliationCaseId(
                                ReconciliationCaseKind.FUNDED_BY,
                                channelDraft.id.value,
                                bankDraft.id.value,
                            ),
                            kind = ReconciliationCaseKind.FUNDED_BY,
                            amount = channelDraft.amount,
                            occurredAt = minOf(
                                channelDraft.occurredAt,
                                bankDraft.occurredAt,
                            ),
                            title = "${channelDraft.counterparty} · ${funding.name}",
                            draftIds = listOf(
                                channelDraft.id.value,
                                bankDraft.id.value,
                            ),
                            sourceAccountId = funding.id.value,
                            destinationAccountId = funding.id.value,
                            relatedTransactionId = null,
                        )
                    }
            }

        inboundDrafts.forEach { inbound ->
            refundSourceTransactions.asSequence()
                .filter { original ->
                    original.status == TransactionStatus.ACTIVE &&
                        original.type == TransactionType.EXPENSE &&
                        !inbound.occurredAt.isBefore(original.occurredAt) &&
                        withinWindow(
                            original.occurredAt,
                            inbound.occurredAt,
                            REFUND_MATCH_WINDOW,
                        )
                }
                .mapNotNull { original ->
                    val expense = original.entries.singleOrNull {
                        it.role == EntryRole.EXPENSE && it.amount.minorUnits > 0L
                    } ?: return@mapNotNull null
                    val refunded = state.activeRefundTotals[original.id]?.also {
                        if (it.currency != expense.amount.currency) return@mapNotNull null
                    }?.minorUnits ?: 0L
                    val remaining = try {
                        Math.subtractExact(expense.amount.minorUnits, refunded)
                    } catch (_: ArithmeticException) {
                        return@mapNotNull null
                    }
                    if (
                        inbound.amount.currency != expense.amount.currency ||
                        inbound.amount.minorUnits > remaining
                    ) {
                        return@mapNotNull null
                    }
                    val counterpart = original.entries.singleOrNull {
                        it.amount.minorUnits < 0L &&
                            it.amount.currency == inbound.amount.currency &&
                            it.role in setOf(EntryRole.FUNDING, EntryRole.LIABILITY)
                    } ?: return@mapNotNull null
                    if (
                        inbound.fundingAccountId != null &&
                        inbound.fundingAccountId != counterpart.accountId
                    ) {
                        return@mapNotNull null
                    }
                    val destination = accountsById[counterpart.accountId]?.account
                        ?: return@mapNotNull null
                    if (
                        destination.type !in refundDestinationTypes ||
                        destination.isArchived ||
                        destination.isSystem
                    ) {
                        return@mapNotNull null
                    }
                    Triple(original, destination, remaining)
                }
                .sortedBy { (original, _, _) ->
                    timeDistanceMillis(original.occurredAt, inbound.occurredAt)
                }
                .take(MAX_CASES_PER_DRAFT)
                .forEach { (original, destination, _) ->
                    cases += ReconciliationCaseSummary(
                        id = reconciliationCaseId(
                            ReconciliationCaseKind.REFUND,
                            inbound.id.value,
                            original.id.value,
                        ),
                        kind = ReconciliationCaseKind.REFUND,
                        amount = inbound.amount,
                        occurredAt = inbound.occurredAt,
                        title = "${original.title} · ${destination.name}",
                        draftIds = listOf(inbound.id.value),
                        sourceAccountId = null,
                        destinationAccountId = destination.id.value,
                        relatedTransactionId = original.id.value,
                    )
                }
        }

        return cases
            .distinctBy(ReconciliationCaseSummary::id)
            .sortedWith(
                compareByDescending<ReconciliationCaseSummary> { it.occurredAt }
                    .thenBy { it.kind.name }
                    .thenBy { it.id },
            )
            .take(MAX_RECONCILIATION_CASES)
    }

    private fun reconciliationCaseId(
        kind: ReconciliationCaseKind,
        vararg ids: String,
    ): String = "reconcile-${kind.name.lowercase(Locale.ROOT)}-${stableDigest(*ids)}"

    private fun stableDigest(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            try {
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            } finally {
                bytes.fill(0)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun withinWindow(
        first: Instant,
        second: Instant,
        window: Duration,
    ): Boolean = Duration.between(first, second).abs() <= window

    private fun timeDistanceMillis(first: Instant, second: Instant): Long =
        runCatching { Duration.between(first, second).abs().toMillis() }
            .getOrDefault(Long.MAX_VALUE)

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
            canUndo = draftId != null ||
                (draftId == null && type == TransactionType.EXPENSE) ||
                type == TransactionType.TRANSFER ||
                type == TransactionType.REFUND ||
                type == TransactionType.LIABILITY_REPAY,
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

            TransactionType.INVEST_BUY -> this.type == AccountType.ASSET_CASH ||
                this.type == AccountType.ASSET_BANK ||
                this.type == AccountType.ASSET_EWALLET_BALANCE

            else -> false
        }
    }

    private fun SourceProposalRecord.suggestedDraftKind(): DraftSummaryKind? =
        when (candidate?.economicEvent?.value) {
            dev.bill.source.contract.ObservedEconomicEvent.INVEST_BUY ->
                DraftSummaryKind.INVEST_BUY

            dev.bill.source.contract.ObservedEconomicEvent.INVEST_SELL -> null
            null -> when (candidate?.moneyDirection?.value) {
                dev.bill.source.contract.ObservedMoneyDirection.OUTBOUND ->
                    DraftSummaryKind.EXPENSE

                dev.bill.source.contract.ObservedMoneyDirection.INBOUND ->
                    DraftSummaryKind.INCOME

                null -> null
            }
        }

    private fun TransactionType.toDraftSummaryKindOrNull(): DraftSummaryKind? = when (this) {
        TransactionType.EXPENSE -> DraftSummaryKind.EXPENSE
        TransactionType.INCOME -> DraftSummaryKind.INCOME
        TransactionType.INVEST_BUY -> DraftSummaryKind.INVEST_BUY
        else -> null
    }

    private fun SourceFamily.toObservedChannel(): ObservedChannel = when (this) {
        SourceFamily.ALIPAY -> ObservedChannel.ALIPAY
        SourceFamily.WECHAT -> ObservedChannel.WECHAT
        SourceFamily.BANK -> ObservedChannel.BANK
        SourceFamily.GENERIC,
        SourceFamily.MANUAL,
        -> ObservedChannel.UNKNOWN
    }

    private fun fundedByKey(draft: ManualDraft): FundedByKey = FundedByKey(
        amount = draft.amount,
        fundingAccountId = requireNotNull(draft.fundingAccountId),
        normalizedCounterparty = draft.counterparty
            .trim()
            .replace(whitespace, " ")
            .lowercase(Locale.ROOT),
    )

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
        const val MAX_INVESTMENT_NAME_LENGTH = 80
        const val MAX_RECONCILIATION_CASE_ID_LENGTH = 128
        const val MAX_RECENT_TRANSACTIONS = 20
        const val MAX_RECONCILIATION_CASES = 50
        const val MAX_CASES_PER_DRAFT = 3
        const val MAX_RECONCILIATION_INPUT_DRAFTS = 500
        const val MAX_RECONCILIATION_INPUT_TRANSACTIONS = 50
        val TRANSFER_MATCH_WINDOW: Duration = Duration.ofDays(3)
        val REFUND_MATCH_WINDOW: Duration = Duration.ofDays(180)
        val FUNDED_BY_MATCH_WINDOW: Duration = Duration.ofMinutes(30)
        val whitespace = Regex("\\s+")
        val investmentCodePattern = Regex("[A-Z0-9][A-Z0-9._-]{0,31}")
        val fundedByAccountTypes = setOf(AccountType.ASSET_BANK, AccountType.LIABILITY_CC)
        val walletObservedChannels = setOf(ObservedChannel.ALIPAY, ObservedChannel.WECHAT)
        val creatableAccountTypes = setOf(
            AccountType.ASSET_CASH,
            AccountType.ASSET_BANK,
            AccountType.ASSET_EWALLET_BALANCE,
            AccountType.LIABILITY_CC,
        )
        val assetMovementTypes = setOf(
            AccountType.ASSET_CASH,
            AccountType.ASSET_BANK,
            AccountType.ASSET_EWALLET_BALANCE,
        )
        val refundDestinationTypes = assetMovementTypes + AccountType.LIABILITY_CC
    }
}

private object InvestmentUnitsInput {
    private val decimalPattern = Regex("(?:0|[1-9]\\d{0,15})(?:\\.\\d{1,8})?")

    fun parse(text: String): BigDecimal? {
        if (!decimalPattern.matches(text)) return null
        return runCatching { BigDecimal(text) }
            .getOrNull()
            ?.takeIf { value -> value.signum() > 0 && value.precision() <= 24 }
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
                .toLongExactCompat()
            Money(minorUnits, currency)
        }.getOrNull()
    }
}

private object BalanceSnapshotTimeInput {
    private val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(text: String, zoneId: ZoneId): Instant? {
        val localDateTime = try {
            LocalDateTime.parse(text.trim(), formatter)
        } catch (_: DateTimeParseException) {
            return null
        }
        val offset = zoneId.rules.getValidOffsets(localDateTime).singleOrNull() ?: return null
        return localDateTime.toInstant(offset)
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
    PostingBuildResult.InvalidDraftPair,
    PostingBuildResult.SameAccount,
    PostingBuildResult.InvalidRelatedTransaction,
    PostingBuildResult.AmountExceedsRemaining,
    -> OperationError.INVALID_STATE
    is PostingBuildResult.Valid -> error("A valid posting has no operation error")
}

private fun List<AccountBalance>.sumMinorUnitsExact(): Long {
    val sum = fold(BigInteger.ZERO) { total, accountBalance ->
        total + BigInteger.valueOf(accountBalance.balance.minorUnits)
    }
    return sum.toLongExactCompat()
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
