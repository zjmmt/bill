package dev.bill.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "reconciliation_draft_links",
    primaryKeys = ["transactionId", "draftId"],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["draftId"]),
        Index(value = ["transactionId", "role"]),
    ],
)
data class ReconciliationDraftLinkEntity(
    val transactionId: String,
    val draftId: String,
    val role: String,
    val linkedAtEpochMillis: Long,
)

@Entity(
    tableName = "transaction_relations",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["fromTransactionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["toTransactionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["fromTransactionId"]),
        Index(value = ["toTransactionId", "type"]),
        Index(
            value = ["fromTransactionId", "toTransactionId", "type"],
            unique = true,
        ),
    ],
)
data class TransactionRelationEntity(
    @androidx.room.PrimaryKey val id: String,
    val fromTransactionId: String,
    val toTransactionId: String,
    val type: String,
    val decision: String,
    val createdAtEpochMillis: Long,
)

data class ActiveRefundLegRow(
    val amountMinorUnits: Long,
    val currency: String,
)

data class ActiveRefundTotalRow(
    val originalTransactionId: String,
    val amountMinorUnits: Long,
    val currency: String,
)
