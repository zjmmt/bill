package dev.bill.app

import dev.bill.application.BillService
import dev.bill.application.SharedTextCapture
import dev.bill.application.SelectedTextFileCapture
import dev.bill.application.SelectedTextFileEvidence
import dev.bill.application.SharedReceiptImageCapture
import dev.bill.application.SharedReceiptImageEvidence
import dev.bill.application.SourceCaptureResult
import dev.bill.application.EvidenceMaintenanceReport
import dev.bill.application.SourceEvidenceManager
import dev.bill.application.SourceEvidenceOperationResult
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.LedgerRepository
import dev.bill.core.domain.LedgerState
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.PostedTransaction
import dev.bill.core.domain.RepositoryWriteResult
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.TransactionId
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.review.EvidencePayloadState
import dev.bill.source.review.SourceEvidenceCursor
import dev.bill.source.review.SourceEvidenceItem
import dev.bill.source.review.SourceEvidenceOverview
import dev.bill.source.review.SourceEvidencePage
import dev.bill.source.review.SourceEvidencePolicy
import dev.bill.source.review.SourceEvidenceStorageSummary
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `ledger starts covered and leaving foreground covers all content and amounts`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeLedgerRepository())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.amountsMasked)
        assertTrue(viewModel.uiState.value.isPrivacyCovered)
        viewModel.revealForForeground()
        assertFalse(viewModel.uiState.value.isPrivacyCovered)
        viewModel.toggleAmounts()
        assertFalse(viewModel.uiState.value.amountsMasked)
        viewModel.coverForPrivacy()
        assertTrue(viewModel.uiState.value.amountsMasked)
        assertTrue(viewModel.uiState.value.isPrivacyCovered)
    }

    @Test
    fun `create account delegates to service and emits success only after repository applies`() =
        runTest(dispatcher) {
            val repository = FakeLedgerRepository()
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.createAccount(
                commandId = "stable-create-account",
                name = "Cash",
                type = AccountType.ASSET_CASH,
                openingBalance = "12.30",
            )
            advanceUntilIdle()

            assertNotNull(repository.createdAccount)
            assertEquals(
                "stable-create-account",
                repository.createdAccount?.creationCommandId?.value,
            )
            assertEquals(1_230L, repository.openingTransaction?.entries?.first()?.amount?.minorUnits)
            assertEquals(BillOperationKind.CREATE_ACCOUNT, viewModel.events.first().successKind())
            assertEquals(null, viewModel.uiState.value.activeOperation)
        }

    @Test
    fun `snapshot failure clears a previously emitted ledger instead of showing stale data`() =
        runTest(dispatcher) {
            val repository = FakeLedgerRepository(
                observedState = flow {
                    emit(emptyLedgerState())
                    throw IllegalStateException("fixture mapping failure")
                },
            )
            val viewModel = viewModel(repository)

            advanceUntilIdle()

            assertNull(viewModel.uiState.value.snapshot)
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.hasFatalError)
        }

    @Test
    fun `shared text capture emits review navigation only after evidence is ready`() =
        runTest(dispatcher) {
            val capture = FakeSharedTextCapture(
                SourceCaptureResult.ReadyForReview(
                    proposalId = "proposal-1",
                    rawEventId = "event-1",
                    alreadyPresent = false,
                ),
            )
            val evidenceManager = FakeSourceEvidenceManager()
            val viewModel = viewModel(
                repository = FakeLedgerRepository(),
                capture = capture,
                evidenceManager = evidenceManager,
            )
            advanceUntilIdle()
            assertEquals(1, evidenceManager.pageCalls)

            viewModel.ingestSharedText("share-command", "private shared text")
            advanceUntilIdle()

            assertEquals("share-command", capture.commandId)
            assertEquals("private shared text", capture.text)
            assertEquals(2, evidenceManager.pageCalls)
            assertEquals(
                BillUiEvent.SourceReviewReady(
                    commandId = "share-command",
                    proposalId = "proposal-1",
                    alreadyPresent = false,
                ),
                viewModel.events.first(),
            )
            assertNull(viewModel.uiState.value.activeOperation)
        }

    @Test
    fun `selected text file is read once, staged for review, and its bytes are wiped`() =
        runTest(dispatcher) {
            val bytes = "date,amount\n2026-07-25,1".toByteArray()
            val reader = FakeSelectedTextDocumentReader(
                SelectedTextDocumentReadResult.Success(
                    SelectedTextFileEvidence(mediaType = "text/csv", bytes = bytes),
                ),
            )
            val capture = FakeSelectedTextFileCapture(
                SourceCaptureResult.ReadyForReview(
                    proposalId = "file-proposal",
                    rawEventId = "file-event",
                    alreadyPresent = false,
                ),
            )
            val viewModel = viewModel(
                repository = FakeLedgerRepository(),
                selectedFileCapture = capture,
                documentReader = reader,
            )
            advanceUntilIdle()

            viewModel.ingestSelectedTextFile("file-command", "content://test/document")
            advanceUntilIdle()

            assertEquals("content://test/document", reader.uriString)
            assertEquals("file-command", capture.commandId)
            assertEquals("text/csv", capture.mediaType)
            assertTrue(bytes.all { it == 0.toByte() })
            assertEquals(
                BillUiEvent.SourceReviewReady(
                    commandId = "file-command",
                    proposalId = "file-proposal",
                    alreadyPresent = false,
                ),
                viewModel.events.first(),
            )
        }

    @Test
    fun `shared PNG receipt is read once, opens review, and its bytes are wiped`() =
        runTest(dispatcher) {
            val bytes = byteArrayOf(1, 2, 3)
            val reader = FakeSharedReceiptImageDocumentReader(
                SharedReceiptImageReadResult.Success(
                    SharedReceiptImageEvidence(mediaType = "image/png", bytes = bytes),
                ),
            )
            val capture = FakeSharedReceiptImageCapture(
                SourceCaptureResult.ReadyForReview(
                    proposalId = "image-proposal",
                    rawEventId = "image-event",
                    alreadyPresent = false,
                ),
            )
            val viewModel = viewModel(
                repository = FakeLedgerRepository(),
                receiptImageCapture = capture,
                receiptImageReader = reader,
            )
            advanceUntilIdle()

            viewModel.ingestSharedReceiptImage(
                commandId = "image-command",
                uriString = "content://test/receipt",
                declaredMediaType = "image/png",
            )
            advanceUntilIdle()

            assertEquals("content://test/receipt", reader.uriString)
            assertEquals("image/png", reader.declaredMediaType)
            assertEquals("image-command", capture.commandId)
            assertEquals("image/png", capture.mediaType)
            assertTrue(bytes.all { it == 0.toByte() })
            assertEquals(
                BillUiEvent.SourceReviewReady(
                    commandId = "image-command",
                    proposalId = "image-proposal",
                    alreadyPresent = false,
                ),
                viewModel.events.first(),
            )
        }

    @Test
    fun `evidence manager runs maintenance loads metadata and applies retention`() =
        runTest(dispatcher) {
            val manager = FakeSourceEvidenceManager()
            val viewModel = viewModel(
                repository = FakeLedgerRepository(),
                evidenceManager = manager,
            )

            advanceUntilIdle()

            assertEquals(1, manager.maintenanceCalls)
            assertEquals(
                listOf("evidence-event"),
                viewModel.uiState.value.evidence.items.map { it.rawEventId.value },
            )
            assertEquals(30, viewModel.uiState.value.evidence.overview?.policy?.retentionDays)

            viewModel.updateEvidenceRetention(7)
            advanceUntilIdle()

            assertEquals(7, manager.updatedRetentionDays)
            assertEquals(
                EvidenceOperationKind.UPDATE_RETENTION,
                (viewModel.events.first() as BillUiEvent.EvidenceOperationSucceeded).kind,
            )
            assertNull(viewModel.uiState.value.evidence.activeOperation)
        }

    private fun viewModel(
        repository: FakeLedgerRepository,
        capture: FakeSharedTextCapture = FakeSharedTextCapture(),
        evidenceManager: SourceEvidenceManager = SourceEvidenceManager.Empty,
        selectedFileCapture: SelectedTextFileCapture = FakeSelectedTextFileCapture(),
        documentReader: SelectedTextDocumentReader = FakeSelectedTextDocumentReader(),
        receiptImageCapture: SharedReceiptImageCapture = FakeSharedReceiptImageCapture(),
        receiptImageReader: SharedReceiptImageDocumentReader = FakeSharedReceiptImageDocumentReader(),
    ) = BillViewModel(
        BillService(
            repository = repository,
            clock = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC),
        ),
        capture,
        evidenceManager,
        selectedFileCapture,
        documentReader,
        receiptImageCapture,
        receiptImageReader,
    )
}

private fun BillUiEvent.successKind(): BillOperationKind =
    (this as BillUiEvent.OperationSucceeded).kind

private class FakeLedgerRepository(
    private val observedState: Flow<LedgerState> = MutableStateFlow(emptyLedgerState()),
) : LedgerRepository {

    var createdAccount: LedgerAccount? = null
    var openingTransaction: PostedTransaction? = null

    override fun observeState(): Flow<LedgerState> = observedState

    override suspend fun findAccount(id: AccountId): LedgerAccount? = null

    override suspend fun findDraft(id: DraftId): ManualDraft? = null

    override suspend fun findTransaction(id: TransactionId): PostedTransaction? = null

    override suspend fun createAccount(
        account: LedgerAccount,
        openingTransaction: PostedTransaction?,
        auditRecords: List<AuditRecord>,
    ): RepositoryWriteResult {
        createdAccount = account
        this.openingTransaction = openingTransaction
        return RepositoryWriteResult(RepositoryWriteStatus.APPLIED, account.id.value)
    }

    override suspend fun createManualDraft(
        draft: ManualDraft,
        auditRecord: AuditRecord,
    ) = unsupported()

    override suspend fun selectFundingAccount(
        draftId: DraftId,
        accountId: AccountId,
        auditRecord: AuditRecord,
    ) = unsupported()

    override suspend fun confirmDraft(
        draftId: DraftId,
        transaction: PostedTransaction,
        auditRecord: AuditRecord,
    ) = unsupported()

    override suspend fun dismissDraft(
        draftId: DraftId,
        auditRecord: AuditRecord,
    ) = unsupported()

    override suspend fun voidTransaction(
        transactionId: TransactionId,
        auditRecord: AuditRecord,
    ) = unsupported()

    private fun unsupported() = RepositoryWriteResult(RepositoryWriteStatus.INVALID_STATE)
}

private fun emptyLedgerState() = LedgerState(
    accountBalances = emptyList(),
    pendingDrafts = emptyList(),
    recentTransactions = emptyList(),
)

private class FakeSharedTextCapture(
    private val result: SourceCaptureResult = SourceCaptureResult.Failure(
        error = dev.bill.application.SharedTextCaptureError.COMMIT_FAILED,
        diagnosticCode = null,
    ),
) : SharedTextCapture {
    var commandId: String? = null
    var text: String? = null

    override suspend fun ingest(
        commandId: String,
        sharedText: CharSequence?,
    ): SourceCaptureResult {
        this.commandId = commandId
        text = sharedText?.toString()
        return result
    }
}

private class FakeSelectedTextDocumentReader(
    private val result: SelectedTextDocumentReadResult = SelectedTextDocumentReadResult.Failure(
        dev.bill.application.SharedTextCaptureError.PARSE_REJECTED,
    ),
) : SelectedTextDocumentReader {
    var uriString: String? = null

    override suspend fun read(uriString: String): SelectedTextDocumentReadResult {
        this.uriString = uriString
        return result
    }
}

private class FakeSelectedTextFileCapture(
    private val result: SourceCaptureResult = SourceCaptureResult.Failure(
        error = dev.bill.application.SharedTextCaptureError.COMMIT_FAILED,
        diagnosticCode = null,
    ),
) : SelectedTextFileCapture {
    var commandId: String? = null
    var mediaType: String? = null

    override suspend fun ingest(
        commandId: String,
        evidence: SelectedTextFileEvidence,
    ): SourceCaptureResult {
        this.commandId = commandId
        mediaType = evidence.mediaType
        return result
    }
}

private class FakeSharedReceiptImageDocumentReader(
    private val result: SharedReceiptImageReadResult = SharedReceiptImageReadResult.Failure(
        dev.bill.application.SourceCaptureError.PARSE_REJECTED,
    ),
) : SharedReceiptImageDocumentReader {
    var uriString: String? = null
    var declaredMediaType: String? = null

    override suspend fun read(
        uriString: String?,
        declaredMediaType: String?,
    ): SharedReceiptImageReadResult {
        this.uriString = uriString
        this.declaredMediaType = declaredMediaType
        return result
    }
}

private class FakeSharedReceiptImageCapture(
    private val result: SourceCaptureResult = SourceCaptureResult.Failure(
        error = dev.bill.application.SourceCaptureError.COMMIT_FAILED,
        diagnosticCode = null,
    ),
) : SharedReceiptImageCapture {
    var commandId: String? = null
    var mediaType: String? = null

    override suspend fun ingest(
        commandId: String,
        evidence: SharedReceiptImageEvidence,
    ): SourceCaptureResult {
        this.commandId = commandId
        mediaType = evidence.mediaType
        return result
    }
}

private class FakeSourceEvidenceManager : SourceEvidenceManager {
    private val item = SourceEvidenceItem(
        rawEventId = RawEventId("evidence-event"),
        payloadId = PayloadId("evidence-payload"),
        sourceFamily = SourceFamily.GENERIC,
        captureMethod = CaptureMethod.SHARE_TEXT,
        capturedAt = Instant.parse("2026-07-19T00:00:00Z"),
        payloadSizeBytes = 12L,
        state = EvidencePayloadState.AVAILABLE,
        hasPendingReview = false,
        clearReason = null,
        clearedAt = null,
    )
    private val overview = MutableStateFlow(
        SourceEvidenceOverview(
            policy = SourceEvidencePolicy(30, Instant.EPOCH),
            storage = SourceEvidenceStorageSummary(1, 12, 0, 0, 0),
        ),
    )

    var maintenanceCalls = 0
    var pageCalls = 0
    var updatedRetentionDays: Int? = null

    override fun observeOverview(): Flow<SourceEvidenceOverview> = overview

    override suspend fun page(
        cursor: SourceEvidenceCursor?,
        limit: Int,
    ): SourceEvidencePage {
        pageCalls += 1
        return SourceEvidencePage(listOf(item), null)
    }

    override suspend fun updateRetentionPolicy(
        retentionDays: Int?,
    ): SourceEvidenceOperationResult {
        updatedRetentionDays = retentionDays
        overview.value = overview.value.copy(
            policy = SourceEvidencePolicy(retentionDays, Instant.EPOCH),
        )
        return SourceEvidenceOperationResult.Success()
    }

    override suspend fun clearEvidence(
        rawEventId: RawEventId,
    ): SourceEvidenceOperationResult = SourceEvidenceOperationResult.Success()

    override suspend fun runMaintenance(): EvidenceMaintenanceReport {
        maintenanceCalls += 1
        return EvidenceMaintenanceReport(0, 0, 0, 0, 0, 0, true)
    }
}
