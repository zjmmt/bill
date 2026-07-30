package dev.bill.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventAppendResult
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.StatementImportBatchId
import dev.bill.source.contract.StatementImportBatchOpenResult
import dev.bill.source.contract.StatementImportBatchRefreshResult
import dev.bill.source.contract.StatementImportBatchRequest
import dev.bill.source.contract.StatementImportBatchState
import dev.bill.source.contract.StatementImportRowRecord
import dev.bill.source.contract.StatementImportRowState
import dev.bill.source.contract.StatementImportRowWriteResult
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomStatementImportBatchRepositoryTest {
    private lateinit var database: BillDatabase
    private lateinit var repository: RoomStatementImportBatchRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BillDatabase::class.java,
        )
            .addCallback(BillDatabaseCallbacks.EnsureSourceEvidencePolicy)
            .allowMainThreadQueries()
            .build()
        repository = RoomStatementImportBatchRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun recordsPartialOutcomesIdempotentlyAndCompletesWithErrors() = runBlocking {
        val opened = repository.open(request())
        assertTrue(opened is StatementImportBatchOpenResult.Created)
        assertTrue(repository.open(request()) is StatementImportBatchOpenResult.Existing)

        val rejected = rejectedRow(index = 1)
        assertEquals(
            StatementImportRowWriteResult.Inserted,
            repository.recordRow(rejected),
        )
        assertEquals(
            StatementImportRowWriteResult.AlreadyPresent,
            repository.recordRow(rejected.copy(updatedAt = BaseTime.plusSeconds(1))),
        )

        val partial = requireNotNull(repository.find(BatchId))
        assertEquals(StatementImportBatchState.IN_PROGRESS, partial.state)
        assertEquals(1, partial.processedRowCount)
        assertEquals(1, partial.rejectedRowCount)

        val rawEvent = rawEvent("statement-row-2", rowIndex = 2)
        assertEquals(
            RawEventAppendResult.Inserted,
            RoomRawEventRepository(database).append(rawEvent),
        )
        assertEquals(
            StatementImportRowWriteResult.Inserted,
            repository.recordRow(
                readyRow(index = 2, rawEventId = rawEvent.id),
            ),
        )

        val terminalBeforeRefresh = requireNotNull(repository.find(BatchId))
        assertEquals(
            StatementImportBatchState.COMPLETED_WITH_ERRORS,
            terminalBeforeRefresh.state,
        )
        assertEquals(2, terminalBeforeRefresh.processedRowCount)
        assertEquals(1, terminalBeforeRefresh.readyForReviewCount)
        assertEquals(1, terminalBeforeRefresh.rejectedRowCount)

        val completed = repository.refresh(BatchId, BaseTime.plusSeconds(2))
            as StatementImportBatchRefreshResult.Updated
        assertEquals(StatementImportBatchState.COMPLETED_WITH_ERRORS, completed.batch.state)
        assertEquals(2, completed.batch.processedRowCount)
        assertEquals(1, completed.batch.readyForReviewCount)
        assertEquals(1, completed.batch.rejectedRowCount)
        assertEquals(listOf(1, 2), repository.listRows(BatchId).map { it.tableRowIndex })
    }

    @Test
    fun failsClosedOnBatchOrRowIdentityCollision() = runBlocking {
        repository.open(request())
        assertEquals(
            StatementImportBatchOpenResult.IdCollision,
            repository.open(request().copy(fileHash = hash("different-file"))),
        )
        assertEquals(
            StatementImportRowWriteResult.Inserted,
            repository.recordRow(rejectedRow(index = 1)),
        )
        assertEquals(
            StatementImportRowWriteResult.IdCollision,
            repository.recordRow(
                rejectedRow(index = 1).copy(rowFingerprint = hash("changed-row")),
            ),
        )
        assertEquals(
            StatementImportRowWriteResult.IdCollision,
            repository.recordRow(rejectedRow(index = 3)),
        )
    }

    @Test
    fun rejectsReadyRowWhenRawEvidenceDoesNotMatchItsFingerprint() = runBlocking {
        repository.open(request())
        val rawEvent = rawEvent("statement-row-mismatch", rowIndex = 2)
            .copy(contentHash = hash("different-row"))
        assertEquals(
            RawEventAppendResult.Inserted,
            RoomRawEventRepository(database).append(rawEvent),
        )

        assertEquals(
            StatementImportRowWriteResult.IdCollision,
            repository.recordRow(readyRow(index = 2, rawEventId = rawEvent.id)),
        )
        assertTrue(repository.listRows(BatchId).isEmpty())
    }

    private fun request() = StatementImportBatchRequest(
        id = BatchId,
        fileHash = hash("file"),
        mappingHash = hash("mapping"),
        totalRowCount = 2,
        now = BaseTime,
    )

    private fun rejectedRow(index: Int) = StatementImportRowRecord(
        batchId = BatchId,
        tableRowIndex = index,
        rowFingerprint = hash("row-$index"),
        state = StatementImportRowState.REJECTED,
        rawEventId = null,
        errorCode = "INVALID_AMOUNT",
        updatedAt = BaseTime,
    )

    private fun readyRow(
        index: Int,
        rawEventId: RawEventId,
    ) = StatementImportRowRecord(
        batchId = BatchId,
        tableRowIndex = index,
        rowFingerprint = hash("row-$index"),
        state = StatementImportRowState.READY_FOR_REVIEW,
        rawEventId = rawEventId,
        errorCode = null,
        updatedAt = BaseTime.plusSeconds(1),
    )

    private fun rawEvent(
        id: String,
        rowIndex: Int,
    ) = RawEvent(
        id = RawEventId(id),
        sourceFamily = SourceFamily.GENERIC,
        connectorId = ConnectorId("android-saf-delimited-row"),
        captureMethod = CaptureMethod.STATEMENT_IMPORT,
        captureScope = CaptureScopeId("local-install"),
        contentHash = hash("row-$rowIndex"),
        capturedAt = BaseTime,
        payloadId = PayloadId(id),
        payloadSizeBytes = 10,
    )

    private fun hash(value: String) = EvidenceHash.fromBytes(value.toByteArray())

    private companion object {
        val BaseTime: Instant = Instant.parse("2026-07-26T08:00:00Z")
        val BatchId = StatementImportBatchId("statement-import-test")
    }
}
