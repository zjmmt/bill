package dev.bill.data.local

import dev.bill.core.domain.AccountBalance
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.BalanceSnapshot
import dev.bill.core.domain.BalanceSnapshotId
import dev.bill.core.domain.BalanceSnapshotSourceMode
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.InvestmentPosition
import dev.bill.core.domain.InvestmentPositionId
import dev.bill.core.domain.InvestmentPositionSourceMode
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.ObservedChannel
import dev.bill.core.domain.PostedTransaction
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.domain.TransactionStatus
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.EntryRole
import dev.bill.core.model.LedgerEntry
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import java.time.Instant
import java.math.BigDecimal

enum class MappingIssueKind {
    BLANK_REQUIRED_VALUE,
    UNKNOWN_ENUM,
    INVALID_CURRENCY,
    INVALID_DOMAIN_RECORD,
    CURRENCY_MISMATCH,
}

/** Contains schema metadata only; it deliberately excludes user-authored text and amounts. */
data class MappingIssue(
    val entity: String,
    val field: String,
    val kind: MappingIssueKind,
    val rawSchemaValue: String? = null,
)

fun interface MappingDiagnostics {
    fun report(issue: MappingIssue)

    companion object {
        val None = MappingDiagnostics { }
    }
}

internal object LedgerEntityMapper {
    fun accountToDomain(
        entity: AccountEntity,
        diagnostics: MappingDiagnostics,
    ): LedgerAccount? {
        val id = accountId(entity.id, "accounts", diagnostics) ?: return null
        val type = enumOrNull<AccountType>(entity.type, "accounts", "type", diagnostics) ?: return null
        val currency = currencyOrNull(entity.currency, "accounts", diagnostics) ?: return null
        val commandId = commandId(entity.creationCommandId, "accounts", diagnostics) ?: return null
        if (entity.name.isBlank() || entity.normalizedName.isBlank()) {
            diagnostics.reportSafely(
                MappingIssue("accounts", "name", MappingIssueKind.BLANK_REQUIRED_VALUE),
            )
            return null
        }
        return constructOrNull("accounts", diagnostics) {
            LedgerAccount(
                id = id,
                name = entity.name,
                normalizedName = entity.normalizedName,
                type = type,
                currency = currency,
                isSystem = entity.isSystem,
                isArchived = entity.isArchived,
                createdAt = Instant.ofEpochMilli(entity.createdAtEpochMillis),
                creationCommandId = commandId,
            )
        }
    }

    fun draftToDomain(
        entity: DraftEntity,
        diagnostics: MappingDiagnostics,
        sourceMode: TransactionSourceMode = TransactionSourceMode.MANUAL,
    ): ManualDraft? {
        val id = draftId(entity.id, "drafts", diagnostics) ?: return null
        val state = enumOrNull<DraftState>(entity.state, "drafts", "state", diagnostics) ?: return null
        val type = enumOrNull<TransactionType>(entity.type, "drafts", "type", diagnostics) ?: return null
        val currency = currencyOrNull(entity.currency, "drafts", diagnostics) ?: return null
        val commandId = commandId(entity.creationCommandId, "drafts", diagnostics) ?: return null
        val observedChannel = enumOrNull<ObservedChannel>(
            entity.observedChannel,
            "drafts",
            "observedChannel",
            diagnostics,
        ) ?: return null
        val fundingAccountId = entity.fundingAccountId?.let {
            accountId(it, "drafts", diagnostics) ?: return null
        }
        val investmentAccountId = entity.investmentAccountId?.let {
            accountId(it, "drafts", diagnostics) ?: return null
        }
        return constructOrNull("drafts", diagnostics) {
            ManualDraft(
                id = id,
                state = state,
                type = type,
                amount = Money(entity.amountMinorUnits, currency),
                occurredAt = Instant.ofEpochMilli(entity.occurredAtEpochMillis),
                counterparty = entity.counterparty,
                note = entity.note,
                fundingAccountId = fundingAccountId,
                investmentAccountId = investmentAccountId,
                createdAt = Instant.ofEpochMilli(entity.createdAtEpochMillis),
                updatedAt = Instant.ofEpochMilli(entity.updatedAtEpochMillis),
                creationCommandId = commandId,
                sourceMode = sourceMode,
                observedChannel = observedChannel,
            )
        }
    }

    fun transactionToDomain(
        relation: TransactionWithEntries,
        diagnostics: MappingDiagnostics,
    ): PostedTransaction? {
        val entity = relation.transaction
        val id = transactionId(entity.id, "ledger_transactions", diagnostics) ?: return null
        val draftId = entity.draftId?.let {
            draftId(it, "ledger_transactions", diagnostics) ?: return null
        }
        val type = enumOrNull<TransactionType>(
            entity.type,
            "ledger_transactions",
            "type",
            diagnostics,
        ) ?: return null
        val status = enumOrNull<TransactionStatus>(
            entity.status,
            "ledger_transactions",
            "status",
            diagnostics,
        ) ?: return null
        val sourceMode = enumOrNull<TransactionSourceMode>(
            entity.sourceMode,
            "ledger_transactions",
            "sourceMode",
            diagnostics,
        ) ?: return null
        val commandId = commandId(entity.commandId, "ledger_transactions", diagnostics) ?: return null
        val entries = relation.entries
            .sortedBy(EntryEntity::position)
            .map { entryToDomain(it, entity.id, diagnostics) ?: return null }
        return constructOrNull("ledger_transactions", diagnostics) {
            PostedTransaction(
                id = id,
                draftId = draftId,
                type = type,
                status = status,
                sourceMode = sourceMode,
                occurredAt = Instant.ofEpochMilli(entity.occurredAtEpochMillis),
                confirmedAt = Instant.ofEpochMilli(entity.confirmedAtEpochMillis),
                title = entity.title,
                note = entity.note,
                commandId = commandId,
                entries = entries,
            )
        }
    }

    fun accountBalanceToDomain(
        row: AccountBalanceRow,
        diagnostics: MappingDiagnostics,
    ): AccountBalance? {
        val account = accountToDomain(row.account, diagnostics) ?: return null
        if (row.currencyMismatchCount > 0L) {
            diagnostics.reportSafely(
                MappingIssue(
                    entity = "ledger_entries",
                    field = "currency",
                    kind = MappingIssueKind.CURRENCY_MISMATCH,
                ),
            )
            return null
        }
        return AccountBalance(
            account = account,
            balance = Money(row.balanceMinorUnits, account.currency),
        )
    }

    fun investmentPositionToDomain(
        entity: InvestmentPositionEntity,
        diagnostics: MappingDiagnostics,
    ): InvestmentPosition? {
        val id = constructIdOrNull(
            entity.id,
            "investment_positions",
            "id",
            diagnostics,
            ::InvestmentPositionId,
        ) ?: return null
        val accountId = accountId(entity.accountId, "investment_positions", diagnostics)
            ?: return null
        val currency = currencyOrNull(entity.currency, "investment_positions", diagnostics)
            ?: return null
        val sourceMode = enumOrNull<InvestmentPositionSourceMode>(
            entity.sourceMode,
            "investment_positions",
            "sourceMode",
            diagnostics,
        ) ?: return null
        val commandId = commandId(
            entity.creationCommandId,
            "investment_positions",
            diagnostics,
        ) ?: return null
        val units = entity.unitsDecimal?.let { raw ->
            try {
                BigDecimal(raw)
            } catch (_: NumberFormatException) {
                diagnostics.reportSafely(
                    MappingIssue(
                        "investment_positions",
                        "unitsDecimal",
                        MappingIssueKind.INVALID_DOMAIN_RECORD,
                    ),
                )
                return null
            }
        }
        val costBasis = when {
            entity.costBasisMinorUnits == null && entity.costBasisCurrency == null -> null
            entity.costBasisMinorUnits != null && entity.costBasisCurrency != null -> {
                val costCurrency = currencyOrNull(
                    entity.costBasisCurrency,
                    "investment_positions",
                    diagnostics,
                ) ?: return null
                Money(entity.costBasisMinorUnits, costCurrency)
            }

            else -> {
                diagnostics.reportSafely(
                    MappingIssue(
                        "investment_positions",
                        "costBasis",
                        MappingIssueKind.INVALID_DOMAIN_RECORD,
                    ),
                )
                return null
            }
        }
        return constructOrNull("investment_positions", diagnostics) {
            InvestmentPosition(
                id = id,
                accountId = accountId,
                instrumentCode = entity.instrumentCode,
                name = entity.name,
                currentValue = Money(entity.currentValueMinorUnits, currency),
                units = units,
                costBasis = costBasis,
                asOf = Instant.ofEpochMilli(entity.asOfEpochMillis),
                sourceMode = sourceMode,
                createdAt = Instant.ofEpochMilli(entity.createdAtEpochMillis),
                updatedAt = Instant.ofEpochMilli(entity.updatedAtEpochMillis),
                creationCommandId = commandId,
            )
        }
    }

    fun balanceSnapshotToDomain(
        entity: BalanceSnapshotEntity,
        diagnostics: MappingDiagnostics,
    ): BalanceSnapshot? {
        val id = constructIdOrNull(
            entity.id,
            "balance_snapshots",
            "id",
            diagnostics,
            ::BalanceSnapshotId,
        ) ?: return null
        val accountId = accountId(entity.accountId, "balance_snapshots", diagnostics)
            ?: return null
        val currency = currencyOrNull(entity.currency, "balance_snapshots", diagnostics)
            ?: return null
        val sourceMode = enumOrNull<BalanceSnapshotSourceMode>(
            entity.sourceMode,
            "balance_snapshots",
            "sourceMode",
            diagnostics,
        ) ?: return null
        val commandId = commandId(
            entity.creationCommandId,
            "balance_snapshots",
            diagnostics,
        ) ?: return null
        return constructOrNull("balance_snapshots", diagnostics) {
            BalanceSnapshot(
                id = id,
                accountId = accountId,
                observedBalance = Money(entity.observedBalanceMinorUnits, currency),
                asOf = Instant.ofEpochMilli(entity.asOfEpochMillis),
                recordedAt = Instant.ofEpochMilli(entity.recordedAtEpochMillis),
                note = entity.note,
                sourceMode = sourceMode,
                creationCommandId = commandId,
            )
        }
    }

    fun accountToEntity(account: LedgerAccount): AccountEntity = AccountEntity(
        id = account.id.value,
        name = account.name,
        normalizedName = account.normalizedName,
        type = account.type.name,
        currency = account.currency.value,
        isSystem = account.isSystem,
        isArchived = account.isArchived,
        createdAtEpochMillis = account.createdAt.toEpochMilli(),
        creationCommandId = account.creationCommandId.value,
    )

    fun investmentPositionToEntity(
        position: InvestmentPosition,
    ): InvestmentPositionEntity = InvestmentPositionEntity(
        id = position.id.value,
        accountId = position.accountId.value,
        instrumentCode = position.instrumentCode,
        name = position.name,
        currentValueMinorUnits = position.currentValue.minorUnits,
        currency = position.currentValue.currency.value,
        unitsDecimal = position.units?.toPlainString(),
        costBasisMinorUnits = position.costBasis?.minorUnits,
        costBasisCurrency = position.costBasis?.currency?.value,
        asOfEpochMillis = position.asOf.toEpochMilli(),
        sourceMode = position.sourceMode.name,
        createdAtEpochMillis = position.createdAt.toEpochMilli(),
        updatedAtEpochMillis = position.updatedAt.toEpochMilli(),
        creationCommandId = position.creationCommandId.value,
    )

    fun balanceSnapshotToEntity(snapshot: BalanceSnapshot): BalanceSnapshotEntity =
        BalanceSnapshotEntity(
            id = snapshot.id.value,
            accountId = snapshot.accountId.value,
            observedBalanceMinorUnits = snapshot.observedBalance.minorUnits,
            currency = snapshot.observedBalance.currency.value,
            asOfEpochMillis = snapshot.asOf.toEpochMilli(),
            recordedAtEpochMillis = snapshot.recordedAt.toEpochMilli(),
            note = snapshot.note,
            sourceMode = snapshot.sourceMode.name,
            creationCommandId = snapshot.creationCommandId.value,
        )

    fun draftToEntity(draft: ManualDraft): DraftEntity = DraftEntity(
        id = draft.id.value,
        state = draft.state.name,
        type = draft.type.name,
        amountMinorUnits = draft.amount.minorUnits,
        currency = draft.amount.currency.value,
        occurredAtEpochMillis = draft.occurredAt.toEpochMilli(),
        counterparty = draft.counterparty,
        note = draft.note,
        fundingAccountId = draft.fundingAccountId?.value,
        investmentAccountId = draft.investmentAccountId?.value,
        createdAtEpochMillis = draft.createdAt.toEpochMilli(),
        updatedAtEpochMillis = draft.updatedAt.toEpochMilli(),
        creationCommandId = draft.creationCommandId.value,
        observedChannel = draft.observedChannel.name,
    )

    fun transactionToEntity(transaction: PostedTransaction): TransactionEntity = TransactionEntity(
        id = transaction.id.value,
        draftId = transaction.draftId?.value,
        type = transaction.type.name,
        status = transaction.status.name,
        sourceMode = transaction.sourceMode.name,
        occurredAtEpochMillis = transaction.occurredAt.toEpochMilli(),
        confirmedAtEpochMillis = transaction.confirmedAt.toEpochMilli(),
        title = transaction.title,
        note = transaction.note,
        commandId = transaction.commandId.value,
    )

    fun entriesToEntities(transaction: PostedTransaction): List<EntryEntity> =
        transaction.entries.mapIndexed { index, entry ->
            EntryEntity(
                transactionId = transaction.id.value,
                position = index,
                accountId = entry.accountId.value,
                amountMinorUnits = entry.amount.minorUnits,
                currency = entry.amount.currency.value,
                role = entry.role.name,
            )
        }

    fun auditToEntity(audit: AuditRecord): AuditEventEntity = AuditEventEntity(
        id = audit.id.value,
        commandId = audit.commandId.value,
        action = audit.action.name,
        entityType = audit.entityType,
        entityId = audit.entityId,
        occurredAtEpochMillis = audit.occurredAt.toEpochMilli(),
    )

    private fun entryToDomain(
        entity: EntryEntity,
        expectedTransactionId: String,
        diagnostics: MappingDiagnostics,
    ): LedgerEntry? {
        if (entity.transactionId != expectedTransactionId) {
            diagnostics.reportSafely(
                MappingIssue(
                    "ledger_entries",
                    "transactionId",
                    MappingIssueKind.INVALID_DOMAIN_RECORD,
                ),
            )
            return null
        }
        val accountId = accountId(entity.accountId, "ledger_entries", diagnostics) ?: return null
        val currency = currencyOrNull(entity.currency, "ledger_entries", diagnostics) ?: return null
        val role = enumOrNull<EntryRole>(entity.role, "ledger_entries", "role", diagnostics)
            ?: return null
        return LedgerEntry(
            accountId = accountId,
            amount = Money(entity.amountMinorUnits, currency),
            role = role,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(
        raw: String,
        entity: String,
        field: String,
        diagnostics: MappingDiagnostics,
    ): T? = enumValues<T>().firstOrNull { it.name == raw } ?: run {
        diagnostics.reportSafely(
            MappingIssue(entity, field, MappingIssueKind.UNKNOWN_ENUM, raw),
        )
        null
    }

    private fun currencyOrNull(
        raw: String,
        entity: String,
        diagnostics: MappingDiagnostics,
    ): CurrencyCode? = try {
        CurrencyCode(raw)
    } catch (_: IllegalArgumentException) {
        diagnostics.reportSafely(
            MappingIssue(entity, "currency", MappingIssueKind.INVALID_CURRENCY, raw),
        )
        null
    }

    private fun accountId(
        raw: String,
        entity: String,
        diagnostics: MappingDiagnostics,
    ): AccountId? = constructIdOrNull(raw, entity, "id", diagnostics, ::AccountId)

    private fun draftId(
        raw: String,
        entity: String,
        diagnostics: MappingDiagnostics,
    ): DraftId? = constructIdOrNull(raw, entity, "id", diagnostics, ::DraftId)

    private fun transactionId(
        raw: String,
        entity: String,
        diagnostics: MappingDiagnostics,
    ): TransactionId? = constructIdOrNull(raw, entity, "id", diagnostics, ::TransactionId)

    private fun commandId(
        raw: String,
        entity: String,
        diagnostics: MappingDiagnostics,
    ): CommandId? = constructIdOrNull(raw, entity, "commandId", diagnostics, ::CommandId)

    private fun <T> constructIdOrNull(
        raw: String,
        entity: String,
        field: String,
        diagnostics: MappingDiagnostics,
        constructor: (String) -> T,
    ): T? = try {
        constructor(raw)
    } catch (_: IllegalArgumentException) {
        diagnostics.reportSafely(
            MappingIssue(entity, field, MappingIssueKind.BLANK_REQUIRED_VALUE),
        )
        null
    }

    private inline fun <T> constructOrNull(
        entity: String,
        diagnostics: MappingDiagnostics,
        constructor: () -> T,
    ): T? = try {
        constructor()
    } catch (_: IllegalArgumentException) {
        diagnostics.reportSafely(
            MappingIssue(entity, "record", MappingIssueKind.INVALID_DOMAIN_RECORD),
        )
        null
    }
}

private fun MappingDiagnostics.reportSafely(issue: MappingIssue) {
    try {
        report(issue)
    } catch (_: RuntimeException) {
        // Diagnostics must never make a corrupt local row fatal to the app.
    }
}
