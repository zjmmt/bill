package dev.bill.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Relation

@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["normalizedName"], unique = true),
        Index(value = ["isSystem"]),
        Index(value = ["creationCommandId"]),
    ],
)
data class AccountEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val type: String,
    val currency: String,
    val isSystem: Boolean,
    val isArchived: Boolean,
    val createdAtEpochMillis: Long,
    val creationCommandId: String,
)

@Entity(
    tableName = "drafts",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["fundingAccountId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["state", "updatedAtEpochMillis"]),
        Index(value = ["fundingAccountId"]),
        Index(value = ["creationCommandId"]),
    ],
)
data class DraftEntity(
    @androidx.room.PrimaryKey val id: String,
    val state: String,
    val type: String,
    val amountMinorUnits: Long,
    val currency: String,
    val occurredAtEpochMillis: Long,
    val counterparty: String,
    val note: String?,
    val fundingAccountId: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val creationCommandId: String,
)

@Entity(
    tableName = "ledger_transactions",
    foreignKeys = [
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["draftId"]),
        Index(value = ["status", "confirmedAtEpochMillis"]),
        Index(value = ["commandId"]),
    ],
)
data class TransactionEntity(
    @androidx.room.PrimaryKey val id: String,
    val draftId: String?,
    val type: String,
    val status: String,
    val sourceMode: String,
    val occurredAtEpochMillis: Long,
    val confirmedAtEpochMillis: Long,
    val title: String,
    val note: String?,
    val commandId: String,
)

@Entity(
    tableName = "ledger_entries",
    primaryKeys = ["transactionId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["accountId"])],
)
data class EntryEntity(
    val transactionId: String,
    val position: Int,
    val accountId: String,
    val amountMinorUnits: Long,
    val currency: String,
    val role: String,
)

@Entity(
    tableName = "audit_events",
    indices = [
        Index(value = ["commandId"]),
        Index(value = ["entityType", "entityId"]),
        Index(value = ["occurredAtEpochMillis"]),
    ],
)
data class AuditEventEntity(
    @androidx.room.PrimaryKey val id: String,
    val commandId: String,
    val action: String,
    val entityType: String,
    val entityId: String,
    val occurredAtEpochMillis: Long,
)

/**
 * Global idempotency record. The fingerprint is SHA-256 over a canonical request
 * representation; no account name, amount, note, or counterparty is stored here.
 */
@Entity(tableName = "command_receipts")
data class CommandReceiptEntity(
    @androidx.room.PrimaryKey val commandId: String,
    val operation: String,
    val targetId: String,
    val resultEntityId: String,
    val payloadFingerprint: String,
    val appliedAtEpochMillis: Long,
)

data class AccountBalanceRow(
    @Embedded val account: AccountEntity,
    val balanceMinorUnits: Long,
    val currencyMismatchCount: Long,
)

data class TransactionWithEntries(
    @Embedded val transaction: TransactionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "transactionId",
    )
    val entries: List<EntryEntity>,
)
