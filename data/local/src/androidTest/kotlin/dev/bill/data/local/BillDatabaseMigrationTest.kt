package dev.bill.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BillDatabase::class.java,
    )

    @Test
    fun migrate1To2PreservesRawEvidenceAndCreatesLedgerSchema() = runBlocking {
        helper.createDatabase(DatabaseName, 1).apply {
            execSQL(
                """
                INSERT INTO raw_events (
                    id,
                    sourceFamily,
                    connectorId,
                    captureMethod,
                    captureScope,
                    contentHash,
                    capturedAtEpochMillis,
                    payloadReference
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "fixture-event-1",
                    "BANK",
                    "fixture-connector",
                    "STATEMENT_IMPORT",
                    "FIXTURE_ONLY",
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    1_753_000_000_000L,
                    "fixture-payload-1",
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(DatabaseName, 2, true).use { migrated ->
            migrated.query(
                "SELECT id, payloadReference FROM raw_events WHERE id = ?",
                arrayOf("fixture-event-1"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fixture-event-1", cursor.getString(0))
                assertEquals("fixture-payload-1", cursor.getString(1))
            }

            migrated.query(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'table' AND name IN (
                    'accounts',
                    'drafts',
                    'ledger_transactions',
                    'ledger_entries',
                    'audit_events',
                    'command_receipts'
                )
                """.trimIndent(),
            ).use { cursor ->
                val tableNames = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertEquals(ExpectedLedgerTables, tableNames)
            }
        }

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
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
            .allowMainThreadQueries()
            .build()
        try {
            val expected = RawEvent(
                id = RawEventId("fixture-event-1"),
                sourceFamily = SourceFamily.BANK,
                connectorId = ConnectorId("fixture-connector"),
                captureMethod = CaptureMethod.STATEMENT_IMPORT,
                captureScope = CaptureScopeId("FIXTURE_ONLY"),
                contentHash = EvidenceHash(ValidHash),
                capturedAt = Instant.ofEpochMilli(1_753_000_000_000L),
                payloadId = PayloadId("fixture-payload-1"),
            )
            val entity = checkNotNull(database.rawEventDao().findById(expected.id.value))
            assertEquals(expected, RawEventEntityMapper.toDomain(entity))
            assertEquals(expected, RoomRawEventRepository(database).findById(expected.id))

            database.rawEventDao().insertIfNewId(
                entity.copy(
                    id = "fixture-corrupt-event",
                    sourceFamily = "PRIVATE_UNKNOWN_FAMILY",
                    payloadReference = "fixture-corrupt-payload",
                ),
            )
            database.sourceEvidenceDao().insertPayload(
                SourceEvidencePayloadEntity(
                    rawEventId = "fixture-corrupt-event",
                    payloadId = "fixture-corrupt-payload",
                    payloadSizeBytes = null,
                    state = "AVAILABLE",
                    clearCommandId = null,
                    clearReason = null,
                    clearRequestedAtEpochMillis = null,
                    clearedAtEpochMillis = null,
                ),
            )
            val failure = runCatching {
                RoomRawEventRepository(database).findById(RawEventId("fixture-corrupt-event"))
            }.exceptionOrNull()
            assertTrue(failure is LocalDataIntegrityException)
            assertEquals("Local ledger data failed validation: raw event", failure?.message)
            assertFalse(failure?.message.orEmpty().contains("PRIVATE_UNKNOWN_FAMILY"))
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate2To3PreservesExistingDataAndCreatesSourceReviewSchema() = runBlocking {
        helper.createDatabase(DatabaseV2Name, 2).apply {
            execSQL(
                """
                INSERT INTO raw_events (
                    id,
                    sourceFamily,
                    connectorId,
                    captureMethod,
                    captureScope,
                    contentHash,
                    capturedAtEpochMillis,
                    payloadReference
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "fixture-v2-event",
                    "GENERIC",
                    "android-share-text",
                    "SHARE_TEXT",
                    "local-install",
                    ValidHash,
                    1_753_000_000_000L,
                    "fixture-v2-payload",
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(DatabaseV2Name, 3, true).use { migrated ->
            migrated.query(
                "SELECT id, payloadReference FROM raw_events WHERE id = ?",
                arrayOf("fixture-v2-event"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fixture-v2-event", cursor.getString(0))
                assertEquals("fixture-v2-payload", cursor.getString(1))
            }
            migrated.query(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'table' AND name IN (
                    'parse_attempts',
                    'source_draft_proposals',
                    'draft_source_evidence'
                )
                """.trimIndent(),
            ).use { cursor ->
                val tableNames = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertEquals(ExpectedSourceTables, tableNames)
            }
        }

        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BillDatabase::class.java,
            DatabaseV2Name,
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
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(
                "fixture-v2-event",
                database.rawEventDao().findById("fixture-v2-event")?.id,
            )
            assertTrue(database.sourceDao().observePendingProposalRows().first().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate3To4BackfillsEvidenceLifecycleAndDefaultPolicy() = runBlocking {
        helper.createDatabase(DatabaseV3Name, 3).apply {
            execSQL(
                """
                INSERT INTO raw_events (
                    id,
                    sourceFamily,
                    connectorId,
                    captureMethod,
                    captureScope,
                    contentHash,
                    capturedAtEpochMillis,
                    payloadReference
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "fixture-v3-event",
                    "GENERIC",
                    "android-share-text",
                    "SHARE_TEXT",
                    "local-install",
                    ValidHash,
                    1_753_000_000_000L,
                    "fixture-v3-payload",
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseV3Name,
            4,
            true,
            BillMigrations.Migration3To4,
        ).use { migrated ->
            migrated.query(
                """
                SELECT payloadId, payloadSizeBytes, state
                FROM source_evidence_payloads
                WHERE rawEventId = ?
                """.trimIndent(),
                arrayOf("fixture-v3-event"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fixture-v3-payload", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals("AVAILABLE", cursor.getString(2))
            }
            migrated.query(
                "SELECT retentionDays, updatedAtEpochMillis FROM source_evidence_policy",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(30, cursor.getInt(0))
                assertEquals(0L, cursor.getLong(1))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test
    fun migrate4To5PreservesLifecycleAndCreatesEmptyStagingJournal() = runBlocking {
        helper.createDatabase(DatabaseV4Name, 4).apply {
            execSQL(
                """
                INSERT INTO raw_events (
                    id,
                    sourceFamily,
                    connectorId,
                    captureMethod,
                    captureScope,
                    contentHash,
                    capturedAtEpochMillis,
                    payloadReference
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "fixture-v4-event",
                    "GENERIC",
                    "android-share-text",
                    "SHARE_TEXT",
                    "local-install",
                    ValidHash,
                    1_753_000_000_000L,
                    "fixture-v4-payload",
                ),
            )
            execSQL(
                """
                INSERT INTO source_evidence_payloads (
                    rawEventId,
                    payloadId,
                    payloadSizeBytes,
                    state,
                    clearCommandId,
                    clearReason,
                    clearRequestedAtEpochMillis,
                    clearedAtEpochMillis
                ) VALUES (?, ?, ?, ?, NULL, NULL, NULL, NULL)
                """.trimIndent(),
                arrayOf<Any>(
                    "fixture-v4-event",
                    "fixture-v4-payload",
                    42L,
                    "AVAILABLE",
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseV4Name,
            5,
            true,
            BillMigrations.Migration4To5,
            BillMigrations.Migration5To6,
        ).use { migrated ->
            migrated.query(
                """
                SELECT payloadId, payloadSizeBytes, state
                FROM source_evidence_payloads
                WHERE rawEventId = ?
                """.trimIndent(),
                arrayOf("fixture-v4-event"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fixture-v4-payload", cursor.getString(0))
                assertEquals(42L, cursor.getLong(1))
                assertEquals("AVAILABLE", cursor.getString(2))
            }
            migrated.query("SELECT COUNT(*) FROM source_evidence_staging").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        }
    }

    @Test
    fun migrate5To6PreservesEvidenceAndCreatesEmptyNotificationObservationJournal() = runBlocking {
        helper.createDatabase(DatabaseV5Name, 5).apply {
            execSQL(
                """
                INSERT INTO raw_events (
                    id,
                    sourceFamily,
                    connectorId,
                    captureMethod,
                    captureScope,
                    contentHash,
                    capturedAtEpochMillis,
                    payloadReference
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "fixture-v5-event",
                    "GENERIC",
                    "android-share-text",
                    "SHARE_TEXT",
                    "local-install",
                    ValidHash,
                    1_753_000_000_000L,
                    "fixture-v5-payload",
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseV5Name,
            6,
            true,
            BillMigrations.Migration5To6,
        ).use { migrated ->
            migrated.query(
                "SELECT id, payloadReference FROM raw_events WHERE id = ?",
                arrayOf("fixture-v5-event"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fixture-v5-event", cursor.getString(0))
                assertEquals("fixture-v5-payload", cursor.getString(1))
            }
            migrated.query("SELECT COUNT(*) FROM notification_observations").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        }
    }

    @Test
    fun migrate6To7PreservesDataAndCreatesEmptyImportAndReconciliationHistory() = runBlocking {
        helper.createDatabase(DatabaseV6Name, 6).apply {
            execSQL(
                """
                INSERT INTO raw_events (
                    id,
                    sourceFamily,
                    connectorId,
                    captureMethod,
                    captureScope,
                    contentHash,
                    capturedAtEpochMillis,
                    payloadReference
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "fixture-v6-event",
                    "GENERIC",
                    "android-share-text",
                    "SHARE_TEXT",
                    "local-install",
                    ValidHash,
                    1_753_000_000_000L,
                    "fixture-v6-payload",
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseV6Name,
            7,
            true,
            BillMigrations.Migration6To7,
        ).use { migrated ->
            migrated.query(
                "SELECT id, payloadReference FROM raw_events WHERE id = ?",
                arrayOf("fixture-v6-event"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fixture-v6-event", cursor.getString(0))
                assertEquals("fixture-v6-payload", cursor.getString(1))
            }
            migrated.query("SELECT COUNT(*) FROM statement_import_batches").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
            migrated.query("SELECT COUNT(*) FROM statement_import_rows").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
            migrated.query("SELECT COUNT(*) FROM reconciliation_draft_links").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
            migrated.query("SELECT COUNT(*) FROM transaction_relations").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        }
    }

    @Test
    fun migrate8To9PreservesDraftsAndAddsOptionalInvestmentTarget() = runBlocking {
        helper.createDatabase(DatabaseV8Name, 8).apply {
            execSQL(
                """
                INSERT INTO drafts (
                    id,
                    state,
                    type,
                    amountMinorUnits,
                    currency,
                    occurredAtEpochMillis,
                    counterparty,
                    note,
                    fundingAccountId,
                    createdAtEpochMillis,
                    updatedAtEpochMillis,
                    creationCommandId
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "fixture-v8-draft",
                    "WAITING_USER",
                    "EXPENSE",
                    1_234L,
                    "CNY",
                    1_753_000_000_000L,
                    "fixture merchant",
                    1_753_000_000_000L,
                    1_753_000_000_000L,
                    "fixture-v8-command",
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseV8Name,
            9,
            true,
            BillMigrations.Migration8To9,
        ).use { migrated ->
            migrated.query(
                "SELECT id, investmentAccountId FROM drafts WHERE id = ?",
                arrayOf("fixture-v8-draft"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fixture-v8-draft", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
            migrated.query(
                """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'index' AND name = 'index_drafts_investmentAccountId'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
        }
    }

    @Test
    fun migrate9To10PreservesDraftsAndDefaultsReviewedChannelToUnknown() = runBlocking {
        helper.createDatabase(DatabaseV9Name, 9).apply {
            execSQL(
                """
                INSERT INTO drafts (
                    id,
                    state,
                    type,
                    amountMinorUnits,
                    currency,
                    occurredAtEpochMillis,
                    counterparty,
                    note,
                    fundingAccountId,
                    investmentAccountId,
                    createdAtEpochMillis,
                    updatedAtEpochMillis,
                    creationCommandId
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "fixture-v9-draft",
                    "WAITING_USER",
                    "EXPENSE",
                    990L,
                    "CNY",
                    1_753_000_000_000L,
                    "fixture merchant",
                    1_753_000_000_000L,
                    1_753_000_000_000L,
                    "fixture-v9-command",
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseV9Name,
            10,
            true,
            BillMigrations.Migration9To10,
        ).use { migrated ->
            migrated.query(
                "SELECT id, observedChannel FROM drafts WHERE id = ?",
                arrayOf("fixture-v9-draft"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fixture-v9-draft", cursor.getString(0))
                assertEquals("UNKNOWN", cursor.getString(1))
            }
        }
    }

    private companion object {
        const val DatabaseName = "bill-v1-to-v2-migration-test"
        const val DatabaseV2Name = "bill-v2-to-v3-migration-test"
        const val DatabaseV3Name = "bill-v3-to-v4-migration-test"
        const val DatabaseV4Name = "bill-v4-to-v5-migration-test"
        const val DatabaseV5Name = "bill-v5-to-v6-migration-test"
        const val DatabaseV6Name = "bill-v6-to-v7-migration-test"
        const val DatabaseV8Name = "bill-v8-to-v9-migration-test"
        const val DatabaseV9Name = "bill-v9-to-v10-migration-test"
        const val ValidHash =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val ExpectedLedgerTables = setOf(
            "accounts",
            "drafts",
            "ledger_transactions",
            "ledger_entries",
            "audit_events",
            "command_receipts",
        )
        val ExpectedSourceTables = setOf(
            "parse_attempts",
            "source_draft_proposals",
            "draft_source_evidence",
        )
    }
}
