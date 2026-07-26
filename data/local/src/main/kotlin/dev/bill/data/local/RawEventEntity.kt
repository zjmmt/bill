package dev.bill.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "raw_events",
    indices = [
        Index(
            value = ["connectorId", "contentHash", "captureScope"],
        ),
    ],
)
data class RawEventEntity(
    @PrimaryKey val id: String,
    val sourceFamily: String,
    val connectorId: String,
    val captureMethod: String,
    val captureScope: String,
    val contentHash: String,
    val capturedAtEpochMillis: Long,
    val payloadReference: String,
)
