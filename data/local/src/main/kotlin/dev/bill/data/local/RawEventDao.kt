package dev.bill.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RawEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNewId(event: RawEventEntity): Long

    @Query("SELECT * FROM raw_events WHERE id = :id")
    suspend fun findById(id: String): RawEventEntity?

    @Query(
        """
        SELECT COUNT(*) FROM raw_events
        WHERE connectorId = :connectorId
          AND contentHash = :contentHash
          AND captureScope = :captureScope
        """,
    )
    suspend fun countObservations(
        connectorId: String,
        contentHash: String,
        captureScope: String,
    ): Long
}
