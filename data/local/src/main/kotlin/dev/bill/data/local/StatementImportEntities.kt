package dev.bill.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "statement_import_batches",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["fileHash", "mappingHash"], unique = true),
        Index(value = ["state", "updatedAtEpochMillis"]),
    ],
)
data class StatementImportBatchEntity(
    val id: String,
    val fileHash: String,
    val mappingHash: String,
    val totalRowCount: Int,
    val processedRowCount: Int,
    val readyForReviewCount: Int,
    val rejectedRowCount: Int,
    val state: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "StatementImportBatchEntity(state=$state, rows=$processedRowCount/$totalRowCount, redacted=true)"
}

@Entity(
    tableName = "statement_import_rows",
    primaryKeys = ["batchId", "tableRowIndex"],
    foreignKeys = [
        ForeignKey(
            entity = StatementImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = RawEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawEventId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["rawEventId"], unique = true),
        Index(value = ["batchId", "state"]),
    ],
)
data class StatementImportRowEntity(
    val batchId: String,
    val tableRowIndex: Int,
    val rowFingerprint: String,
    val state: String,
    val rawEventId: String?,
    val errorCode: String?,
    val updatedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "StatementImportRowEntity(state=$state, row=$tableRowIndex, redacted=true)"
}

data class StatementImportRowCounts(
    val processedRowCount: Int,
    val readyForReviewCount: Int,
    val rejectedRowCount: Int,
)
