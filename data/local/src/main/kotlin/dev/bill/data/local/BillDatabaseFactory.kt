package dev.bill.data.local

import android.content.Context
import androidx.room.Room

object BillDatabaseFactory {
    const val DatabaseName = "bill-local.db"

    fun create(context: Context): BillDatabase = Room.databaseBuilder(
        context.applicationContext,
        BillDatabase::class.java,
        DatabaseName,
    )
        .addMigrations(
            BillMigrations.Migration3To4,
            BillMigrations.Migration4To5,
            BillMigrations.Migration5To6,
            BillMigrations.Migration6To7,
            BillMigrations.Migration7To8,
            BillMigrations.Migration8To9,
            BillMigrations.Migration9To10,
        )
        .addCallback(BillDatabaseCallbacks.EnsureSourceEvidencePolicy)
        .build()
}
