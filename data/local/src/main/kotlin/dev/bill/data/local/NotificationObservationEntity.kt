package dev.bill.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A redacted notification dedupe journal. It stores an install-scoped digest rather than an
 * Android notification key, package name, channel, category or rendered notification content.
 */
@Entity(
    tableName = "notification_observations",
    indices = [
        Index(value = ["state", "leaseExpiresAtEpochMillis"]),
        Index(value = ["state", "capturedAtEpochMillis"]),
    ],
)
data class NotificationObservationEntity(
    @PrimaryKey val observationId: String,
    val commandId: String,
    val state: String,
    val leaseId: String,
    val createdAtEpochMillis: Long,
    val leaseExpiresAtEpochMillis: Long,
    val capturedAtEpochMillis: Long?,
) {
    override fun toString(): String = "NotificationObservationEntity(state=$state, redacted=true)"
}
