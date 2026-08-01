package dev.bill.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.AutoMigration

@Database(
    entities = [
        RawEventEntity::class,
        AccountEntity::class,
        DraftEntity::class,
        TransactionEntity::class,
        EntryEntity::class,
        AuditEventEntity::class,
        CommandReceiptEntity::class,
        ParseAttemptEntity::class,
        SourceDraftProposalEntity::class,
        DraftSourceEvidenceEntity::class,
        SourceEvidencePayloadEntity::class,
        SourceEvidencePolicyEntity::class,
        SourceEvidenceStagingEntity::class,
        NotificationObservationEntity::class,
        StatementImportBatchEntity::class,
        StatementImportRowEntity::class,
        ReconciliationDraftLinkEntity::class,
        TransactionRelationEntity::class,
        InvestmentPositionEntity::class,
        BalanceSnapshotEntity::class,
    ],
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
    ],
    version = 11,
    exportSchema = true,
)
abstract class BillDatabase : RoomDatabase() {
    abstract fun rawEventDao(): RawEventDao

    abstract fun ledgerDao(): LedgerDao

    abstract fun sourceDao(): SourceDao

    abstract fun sourceEvidenceDao(): SourceEvidenceDao

    abstract fun sourceEvidenceStagingDao(): SourceEvidenceStagingDao

    abstract fun notificationObservationDao(): NotificationObservationDao

    abstract fun statementImportDao(): StatementImportDao
}
