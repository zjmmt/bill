package dev.bill.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

object BillDatabaseCallbacks {
    val EnsureSourceEvidencePolicy = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            ensurePolicy(db)
        }

        private fun ensurePolicy(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO source_evidence_policy (
                    id,
                    retentionDays,
                    updatedAtEpochMillis
                ) VALUES (1, 30, 0)
                """.trimIndent(),
            )
        }
    }
}
