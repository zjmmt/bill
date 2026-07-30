package dev.bill.application

import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceLocator
import dev.bill.source.contract.FieldCandidate
import dev.bill.source.contract.NormalizedCandidate
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.StatementImportBatchId
import dev.bill.source.contract.StatementImportBatchOpenResult
import dev.bill.source.contract.StatementImportBatchRecord
import dev.bill.source.contract.StatementImportBatchRefreshResult
import dev.bill.source.contract.StatementImportBatchRepository
import dev.bill.source.contract.StatementImportBatchRequest
import dev.bill.source.contract.StatementImportBatchState
import dev.bill.source.contract.StatementImportRowRecord
import dev.bill.source.contract.StatementImportRowState
import dev.bill.source.contract.StatementImportRowWriteResult
import dev.bill.source.genericdelimited.DelimitedDelimiter
import dev.bill.source.genericdelimited.DelimitedRowEvidenceDecodeResult
import dev.bill.source.genericdelimited.DelimitedStatementMapping
import dev.bill.source.genericdelimited.DelimitedStatementRowEvidenceCodec
import dev.bill.source.genericdelimited.StatementAmountFormat
import dev.bill.source.genericdelimited.StatementDateFormat
import dev.bill.source.genericdelimited.StatementDirection
import dev.bill.source.genericdelimited.StatementDirectionMapping
import dev.bill.source.review.SourceProposalRecord
import dev.bill.source.review.allowedExternalDraftCurrencies
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDelimitedStatementImportServiceTest {
    @Test
    fun `preview is bounded local state and wipes caller bytes`() {
        val bytes = csv().toByteArray(StandardCharsets.UTF_8)
        val service = service(FakeBatchRepository(), FakeRowCapture())

        val result = service.preview(
            SelectedDelimitedStatementEvidence("text/csv; charset=utf-8", bytes),
            DelimitedDelimiter.COMMA,
        )

        assertTrue(bytes.all { it == 0.toByte() })
        val session = (result as StatementImportPreviewResult.Ready).session
        assertEquals(listOf("date", "amount", "direction", "party"), session.header)
        assertEquals(2, session.totalDataRowCount)
        assertEquals("Coffee", session.previewRows.first().cells[3])

        val mappingPreview = service.previewMapping(session, mapping())
            as StatementImportMappingPreviewResult.Ready
        assertEquals(1, mappingPreview.preview.validRowCount)
        assertEquals(1, mappingPreview.preview.invalidRowCount)

        session.close()
        assertTrue(session.header.isEmpty())
        assertTrue(session.previewRows.isEmpty())
        assertEquals(
            StatementImportMappingPreviewResult.ClosedSession,
            service.previewMapping(session, mapping()),
        )
    }

    @Test
    fun `mime delimiter mismatch fails before any persistent work`() {
        val repository = FakeBatchRepository()
        val bytes = csv().toByteArray(StandardCharsets.UTF_8)
        val result = service(repository, FakeRowCapture()).preview(
            SelectedDelimitedStatementEvidence("text/tab-separated-values", bytes),
            DelimitedDelimiter.COMMA,
        )

        assertEquals(
            StatementImportPreviewResult.Failure(
                StatementImportPreviewError.DELIMITER_MISMATCH,
            ),
            result,
        )
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(repository.batches.isEmpty())
    }

    @Test
    fun `confirmation stores one valid row and one content-free rejection then resumes`() =
        runBlocking {
            val repository = FakeBatchRepository()
            val capture = FakeRowCapture()
            val service = service(repository, capture)
            val session = readySession(service)

            val first = service.confirm(session, mapping())
                as StatementImportConfirmationResult.Completed

            assertEquals(StatementImportBatchState.COMPLETED_WITH_ERRORS, first.batch.state)
            assertEquals(1, first.batch.readyForReviewCount)
            assertEquals(1, first.batch.rejectedRowCount)
            assertEquals(1, capture.callCount)
            assertTrue(
                capture.decodedRows.single() is DelimitedRowEvidenceDecodeResult.Decoded,
            )
            val persistedRows = repository.rows.values.toList()
            assertEquals(
                setOf(StatementImportRowState.READY_FOR_REVIEW, StatementImportRowState.REJECTED),
                persistedRows.mapTo(mutableSetOf(), StatementImportRowRecord::state),
            )
            assertEquals("INVALID_AMOUNT", persistedRows.single {
                it.state == StatementImportRowState.REJECTED
            }.errorCode)

            val second = service.confirm(session, mapping())
                as StatementImportConfirmationResult.Completed
            assertEquals(2, second.resumedRowCount)
            assertEquals(1, capture.callCount)
        }

    @Test
    fun `transient capture failure leaves a resumable missing row`() = runBlocking {
        val repository = FakeBatchRepository()
        val capture = FakeRowCapture(failFirstCall = true)
        val service = service(repository, capture)
        val session = readySession(
            service,
            "date,amount,direction,party\n2026-07-01,12.34,out,Coffee",
        )

        val interrupted = service.confirm(session, mapping())
            as StatementImportConfirmationResult.Interrupted
        assertEquals(1, interrupted.tableRowIndex)
        assertTrue(repository.rows.isEmpty())

        val completed = service.confirm(session, mapping())
            as StatementImportConfirmationResult.Completed
        assertEquals(StatementImportBatchState.COMPLETED, completed.batch.state)
        assertEquals(1, completed.batch.readyForReviewCount)
        assertEquals(2, capture.callCount)
    }

    @Test
    fun `only verified generic delimited proposals may expose usd for review`() {
        val amount = FieldCandidate(
            value = Money(100, CurrencyCode.USD),
            confidence = 1.0,
            evidenceLocator = EvidenceLocator.TableCell(1, 1),
        )
        val verified = proposal(
            parserId = "generic-delimited-statement",
            connectorId = "android-saf-delimited-row",
            candidate = NormalizedCandidate(amount = amount),
        )
        val opaqueGeneric = proposal(
            parserId = "generic-selected-text-file",
            connectorId = "android-saf-text-file",
            candidate = NormalizedCandidate(amount = amount),
        )

        assertEquals(setOf(CurrencyCode.USD), verified.allowedExternalDraftCurrencies())
        assertEquals(setOf(CurrencyCode.CNY), opaqueGeneric.allowedExternalDraftCurrencies())
    }

    private fun service(
        repository: FakeBatchRepository,
        capture: FakeRowCapture,
    ) = LocalDelimitedStatementImportService(
        batchRepository = repository,
        rowCapture = capture,
        clock = Clock.fixed(BaseTime, ZoneOffset.UTC),
    )

    private fun readySession(
        service: LocalDelimitedStatementImportService,
        content: String = csv(),
    ): LocalDelimitedStatementSession {
        val result = service.preview(
            SelectedDelimitedStatementEvidence(
                "text/csv",
                content.toByteArray(StandardCharsets.UTF_8),
            ),
            DelimitedDelimiter.COMMA,
        )
        return (result as StatementImportPreviewResult.Ready).session
    }

    private fun mapping() = DelimitedStatementMapping(
        delimiter = DelimitedDelimiter.COMMA,
        dateColumnIndex = 0,
        dateFormat = StatementDateFormat.DATE_DASH,
        amountColumnIndex = 1,
        amountFormat = StatementAmountFormat.DOT_DECIMAL,
        directionMapping = StatementDirectionMapping.DirectionColumn(
            columnIndex = 2,
            inboundTokens = setOf("in"),
            outboundTokens = setOf("out"),
        ),
        counterpartyColumnIndex = 3,
        referenceColumnIndex = null,
        currency = CurrencyCode.CNY,
    )

    private fun csv() =
        "date,amount,direction,party\n" +
            "2026-07-01,12.34,out,Coffee\n" +
            "2026-07-02,not-money,in,Employer"

    private fun proposal(
        parserId: String,
        connectorId: String,
        candidate: NormalizedCandidate,
    ) = SourceProposalRecord(
        id = "proposal",
        rawEventId = "raw",
        parseAttemptId = "attempt",
        parserId = parserId,
        providerId = "user-mapped-statement",
        connectorId = connectorId,
        sourceFamily = SourceFamily.GENERIC,
        captureMethod = CaptureMethod.STATEMENT_IMPORT,
        capturedAt = BaseTime,
        diagnostic = null,
        candidate = candidate,
        isPossibleDuplicate = false,
    )

    private class FakeRowCapture(
        private val failFirstCall: Boolean = false,
    ) : DelimitedStatementRowCapture {
        var callCount: Int = 0
        val decodedRows = mutableListOf<DelimitedRowEvidenceDecodeResult>()

        override suspend fun ingest(
            commandId: String,
            bytes: ByteArray,
        ): SourceCaptureResult {
            callCount += 1
            if (failFirstCall && callCount == 1) {
                return SourceCaptureResult.Failure(
                    SharedTextCaptureError.STORAGE_LIMIT_REACHED,
                    diagnosticCode = null,
                )
            }
            decodedRows += DelimitedStatementRowEvidenceCodec.decode(bytes)
            return SourceCaptureResult.ReadyForReview(
                proposalId = "proposal-$commandId",
                rawEventId = "statement-row-$commandId",
                alreadyPresent = false,
            )
        }
    }

    private class FakeBatchRepository : StatementImportBatchRepository {
        val batches = mutableMapOf<StatementImportBatchId, StatementImportBatchRecord>()
        val rows = mutableMapOf<Pair<StatementImportBatchId, Int>, StatementImportRowRecord>()

        override suspend fun open(
            request: StatementImportBatchRequest,
        ): StatementImportBatchOpenResult {
            val existing = batches[request.id]
            if (existing != null) {
                return if (
                    existing.fileHash == request.fileHash &&
                    existing.mappingHash == request.mappingHash &&
                    existing.totalRowCount == request.totalRowCount
                ) {
                    StatementImportBatchOpenResult.Existing(existing)
                } else {
                    StatementImportBatchOpenResult.IdCollision
                }
            }
            val created = StatementImportBatchRecord(
                id = request.id,
                fileHash = request.fileHash,
                mappingHash = request.mappingHash,
                totalRowCount = request.totalRowCount,
                processedRowCount = 0,
                readyForReviewCount = 0,
                rejectedRowCount = 0,
                state = StatementImportBatchState.IN_PROGRESS,
                createdAt = request.now,
                updatedAt = request.now,
            )
            batches[request.id] = created
            return StatementImportBatchOpenResult.Created(created)
        }

        override suspend fun find(id: StatementImportBatchId): StatementImportBatchRecord? =
            batches[id]

        override suspend fun listRows(
            id: StatementImportBatchId,
        ): List<StatementImportRowRecord> = rows.values
            .filter { it.batchId == id }
            .sortedBy(StatementImportRowRecord::tableRowIndex)

        override suspend fun recordRow(
            row: StatementImportRowRecord,
        ): StatementImportRowWriteResult {
            val batch = batches[row.batchId]
                ?: return StatementImportRowWriteResult.BatchNotFound
            if (row.tableRowIndex > batch.totalRowCount) {
                return StatementImportRowWriteResult.IdCollision
            }
            val key = row.batchId to row.tableRowIndex
            val existing = rows[key]
            if (existing != null) {
                return if (
                    existing.rowFingerprint == row.rowFingerprint &&
                    existing.state == row.state &&
                    existing.rawEventId == row.rawEventId &&
                    existing.errorCode == row.errorCode
                ) {
                    StatementImportRowWriteResult.AlreadyPresent
                } else {
                    StatementImportRowWriteResult.IdCollision
                }
            }
            rows[key] = row
            refresh(row.batchId, row.updatedAt)
            return StatementImportRowWriteResult.Inserted
        }

        override suspend fun refresh(
            id: StatementImportBatchId,
            now: Instant,
        ): StatementImportBatchRefreshResult {
            val current = batches[id] ?: return StatementImportBatchRefreshResult.NotFound
            val matching = rows.values.filter { it.batchId == id }
            val rejected = matching.count { it.state == StatementImportRowState.REJECTED }
            val ready = matching.count {
                it.state == StatementImportRowState.READY_FOR_REVIEW
            }
            val state = when {
                matching.size < current.totalRowCount -> StatementImportBatchState.IN_PROGRESS
                rejected == 0 -> StatementImportBatchState.COMPLETED
                else -> StatementImportBatchState.COMPLETED_WITH_ERRORS
            }
            val updated = current.copy(
                processedRowCount = matching.size,
                readyForReviewCount = ready,
                rejectedRowCount = rejected,
                state = state,
                updatedAt = maxOf(current.updatedAt, now),
            )
            batches[id] = updated
            return StatementImportBatchRefreshResult.Updated(updated)
        }
    }

    private companion object {
        val BaseTime: Instant = Instant.parse("2026-07-26T08:00:00Z")
    }
}
