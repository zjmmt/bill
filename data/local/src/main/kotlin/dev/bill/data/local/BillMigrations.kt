package dev.bill.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object BillMigrations {
    val Migration3To4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `source_evidence_payloads` (
                    `rawEventId` TEXT NOT NULL,
                    `payloadId` TEXT NOT NULL,
                    `payloadSizeBytes` INTEGER,
                    `state` TEXT NOT NULL,
                    `clearCommandId` TEXT,
                    `clearReason` TEXT,
                    `clearRequestedAtEpochMillis` INTEGER,
                    `clearedAtEpochMillis` INTEGER,
                    PRIMARY KEY(`rawEventId`),
                    FOREIGN KEY(`rawEventId`) REFERENCES `raw_events`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_source_evidence_payloads_payloadId`
                ON `source_evidence_payloads` (`payloadId`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_source_evidence_payloads_state`
                ON `source_evidence_payloads` (`state`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_source_evidence_payloads_clearCommandId`
                ON `source_evidence_payloads` (`clearCommandId`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `source_evidence_policy` (
                    `id` INTEGER NOT NULL,
                    `retentionDays` INTEGER,
                    `updatedAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO `source_evidence_policy` (
                    `id`,
                    `retentionDays`,
                    `updatedAtEpochMillis`
                ) VALUES (1, 30, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO `source_evidence_payloads` (
                    `rawEventId`,
                    `payloadId`,
                    `payloadSizeBytes`,
                    `state`,
                    `clearCommandId`,
                    `clearReason`,
                    `clearRequestedAtEpochMillis`,
                    `clearedAtEpochMillis`
                )
                SELECT
                    `id`,
                    `payloadReference`,
                    NULL,
                    'AVAILABLE',
                    NULL,
                    NULL,
                    NULL,
                    NULL
                FROM `raw_events`
                """.trimIndent(),
            )
        }
    }

    val Migration4To5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `source_evidence_staging` (
                    `payloadId` TEXT NOT NULL,
                    `rawEventId` TEXT,
                    `contentHash` TEXT,
                    `payloadSizeBytes` INTEGER,
                    `state` TEXT NOT NULL,
                    `leaseId` TEXT NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    `leaseExpiresAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`payloadId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_source_evidence_staging_rawEventId`
                ON `source_evidence_staging` (`rawEventId`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_source_evidence_staging_state_leaseExpiresAtEpochMillis`
                ON `source_evidence_staging` (`state`, `leaseExpiresAtEpochMillis`)
                """.trimIndent(),
            )
        }
    }

    val Migration5To6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `notification_observations` (
                    `observationId` TEXT NOT NULL,
                    `commandId` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `leaseId` TEXT NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    `leaseExpiresAtEpochMillis` INTEGER NOT NULL,
                    `capturedAtEpochMillis` INTEGER,
                    PRIMARY KEY(`observationId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_notification_observations_state_leaseExpiresAtEpochMillis`
                ON `notification_observations` (`state`, `leaseExpiresAtEpochMillis`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_notification_observations_state_capturedAtEpochMillis`
                ON `notification_observations` (`state`, `capturedAtEpochMillis`)
                """.trimIndent(),
            )
        }
    }

    val Migration6To7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `statement_import_batches` (
                    `id` TEXT NOT NULL,
                    `fileHash` TEXT NOT NULL,
                    `mappingHash` TEXT NOT NULL,
                    `totalRowCount` INTEGER NOT NULL,
                    `processedRowCount` INTEGER NOT NULL,
                    `readyForReviewCount` INTEGER NOT NULL,
                    `rejectedRowCount` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    `updatedAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_statement_import_batches_fileHash_mappingHash`
                ON `statement_import_batches` (`fileHash`, `mappingHash`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_statement_import_batches_state_updatedAtEpochMillis`
                ON `statement_import_batches` (`state`, `updatedAtEpochMillis`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `statement_import_rows` (
                    `batchId` TEXT NOT NULL,
                    `tableRowIndex` INTEGER NOT NULL,
                    `rowFingerprint` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `rawEventId` TEXT,
                    `errorCode` TEXT,
                    `updatedAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`batchId`, `tableRowIndex`),
                    FOREIGN KEY(`batchId`) REFERENCES `statement_import_batches`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`rawEventId`) REFERENCES `raw_events`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_statement_import_rows_rawEventId`
                ON `statement_import_rows` (`rawEventId`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_statement_import_rows_batchId_state`
                ON `statement_import_rows` (`batchId`, `state`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `reconciliation_draft_links` (
                    `transactionId` TEXT NOT NULL,
                    `draftId` TEXT NOT NULL,
                    `role` TEXT NOT NULL,
                    `linkedAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`transactionId`, `draftId`),
                    FOREIGN KEY(`transactionId`) REFERENCES `ledger_transactions`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`draftId`) REFERENCES `drafts`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_reconciliation_draft_links_draftId`
                ON `reconciliation_draft_links` (`draftId`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_reconciliation_draft_links_transactionId_role`
                ON `reconciliation_draft_links` (`transactionId`, `role`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `transaction_relations` (
                    `id` TEXT NOT NULL,
                    `fromTransactionId` TEXT NOT NULL,
                    `toTransactionId` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `decision` TEXT NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`fromTransactionId`) REFERENCES `ledger_transactions`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`toTransactionId`) REFERENCES `ledger_transactions`(`id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_transaction_relations_fromTransactionId`
                ON `transaction_relations` (`fromTransactionId`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_transaction_relations_toTransactionId_type`
                ON `transaction_relations` (`toTransactionId`, `type`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_transaction_relations_fromTransactionId_toTransactionId_type`
                ON `transaction_relations` (`fromTransactionId`, `toTransactionId`, `type`)
                """.trimIndent(),
            )
        }
    }
}
