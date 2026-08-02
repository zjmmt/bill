package dev.bill.app

import dev.bill.application.BillService
import dev.bill.application.DelimitedStatementImport
import dev.bill.application.LocalDelimitedStatementSession
import dev.bill.application.ReconciliationCaseKind
import dev.bill.application.SelectedDelimitedStatementEvidence
import dev.bill.application.SharedTextCapture
import dev.bill.application.SelectedTextFileCapture
import dev.bill.application.SelectedTextFileEvidence
import dev.bill.application.SharedReceiptImageCapture
import dev.bill.application.SharedReceiptImageEvidence
import dev.bill.application.SourceCaptureError
import dev.bill.application.SourceCaptureResult
import dev.bill.application.EvidenceMaintenanceReport
import dev.bill.application.SourceEvidenceManager
import dev.bill.application.SourceEvidenceOperationResult
import dev.bill.application.StatementImportConfirmationResult
import dev.bill.application.StatementImportMappedPreviewRow
import dev.bill.application.StatementImportMappingPreview
import dev.bill.application.StatementImportMappingPreviewResult
import dev.bill.application.StatementImportPreviewResult
import dev.bill.application.StatementImportProgress
import dev.bill.core.domain.AccountBalance
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.BalanceSnapshot
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.domain.DraftState
import dev.bill.core.domain.LedgerAccount
import dev.bill.core.domain.LedgerRepository
import dev.bill.core.domain.LedgerState
import dev.bill.core.domain.ManualDraft
import dev.bill.core.domain.PostedTransaction
import dev.bill.core.domain.ReconciliationKind
import dev.bill.core.domain.ReconciliationResolution
import dev.bill.core.domain.RepositoryWriteResult
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import dev.bill.core.model.TransactionType
import dev.bill.core.model.TransactionId
import dev.bill.app.quickcapture.SelectedPhotoOcrImporter
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.StatementImportBatchId
import dev.bill.source.contract.StatementImportBatchRecord
import dev.bill.source.contract.StatementImportBatchState
import dev.bill.source.genericdelimited.DelimitedDelimiter
import dev.bill.source.genericdelimited.DelimitedDocument
import dev.bill.source.genericdelimited.DelimitedRow
import dev.bill.source.genericdelimited.DelimitedStatementMapping
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    fun `balance snapshot delegates through view model and reports the account operation`() =
        runTest(dispatcher) {
            val now = Instant.parse("2026-07-19T00:00:00Z")
            val bank = ledgerAccount("snapshot-bank", AccountType.ASSET_BANK, now)
            val repository = FakeLedgerRepository(findableAccounts = mapOf(bank.id to bank))
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.createBalanceSnapshot(
                commandId = "snapshot-command",
                accountId = bank.id.value,
                observedBalance = "123.45",
                asOf = "2026-07-19 00:00",
                note = "manual check",
            )
            advanceUntilIdle()

            val snapshot = requireNotNull(repository.createdBalanceSnapshot)
            assertEquals(bank.id, snapshot.accountId)
            assertEquals(Money.cny(12_345L), snapshot.observedBalance)
            assertEquals("manual check", snapshot.note)
            assertEquals(
                BillOperationKind.CREATE_BALANCE_SNAPSHOT,
                viewModel.events.first().successKind(),
            )
            assertNull(viewModel.uiState.value.activeOperation)
        }

    @Test
    fun `reconciliation confirmation delegates through view model and emits success`() =
        runTest(dispatcher) {
            val now = Instant.parse("2026-07-19T00:00:00Z")
            val bank = ledgerAccount("bank", AccountType.ASSET_BANK, now)
            val wallet = ledgerAccount("wallet", AccountType.ASSET_EWALLET_BALANCE, now)
            val repository = FakeLedgerRepository(
                observedState = MutableStateFlow(
                    LedgerState(
                        accountBalances = listOf(
                            AccountBalance(bank, Money.cny(10_000L)),
                            AccountBalance(wallet, Money.cny(0L)),
                        ),
                        pendingDrafts = listOf(
                            reconciliationDraft(
                                id = "transfer-out",
                                type = TransactionType.EXPENSE,
                                fundingAccountId = bank.id,
                                now = now,
                            ),
                            reconciliationDraft(
                                id = "transfer-in",
                                type = TransactionType.INCOME,
                                fundingAccountId = wallet.id,
                                now = now,
                            ),
                        ),
                        recentTransactions = emptyList(),
                    ),
                ),
            )
            val viewModel = viewModel(repository)
            advanceUntilIdle()
            val case = requireNotNull(viewModel.uiState.value.snapshot)
                .reconciliationCases
                .single { it.kind == ReconciliationCaseKind.TRANSFER }

            viewModel.resolveReconciliation(
                commandId = "view-model-reconcile",
                caseId = case.id,
            )
            advanceUntilIdle()

            assertEquals(
                ReconciliationKind.TRANSFER_PAIR,
                repository.resolvedReconciliation?.kind,
            )
            assertEquals(
                BillOperationKind.RESOLVE_RECONCILIATION,
                viewModel.events.first().successKind(),
            )
            assertNull(viewModel.uiState.value.activeOperation)
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
    fun `structured statement maps previews and imports rows without retaining selected bytes`() =
        runTest(dispatcher) {
            val bytes = "date,amount,counterparty\n2026-07-25,-1.00,Shop".toByteArray()
            val reader = FakeDelimitedStatementDocumentReader(
                SelectedDelimitedStatementReadResult.Success(
                    SelectedDelimitedStatementEvidence(
                        mediaType = "text/csv",
                        bytes = bytes,
                    ),
                ),
            )
            val statementImport = FakeDelimitedStatementImport()
            val evidenceManager = FakeSourceEvidenceManager()
            val viewModel = viewModel(
                repository = FakeLedgerRepository(),
                evidenceManager = evidenceManager,
                delimitedImport = statementImport,
                delimitedReader = reader,
            )
            advanceUntilIdle()
            val initialPageCalls = evidenceManager.pageCalls

            viewModel.ingestSelectedDelimitedStatement(
                documentUri = "content://test/statement",
                delimiter = DelimitedDelimiter.COMMA,
            )
            advanceUntilIdle()

            assertEquals("content://test/statement", reader.uriString)
            assertTrue(bytes.all { it == 0.toByte() })
            val initialMapping = viewModel.uiState.value.statementImport
                as StatementImportUiState.Mapping
            assertNull(initialMapping.input.dateColumnIndex)
            assertEquals(
                StatementImportConfigurationIssue.INCOMPLETE_FIELDS,
                initialMapping.configurationIssue,
            )

            viewModel.updateStatementImportMapping(
                initialMapping.input.copy(
                    dateColumnIndex = 0,
                    amountColumnIndex = 1,
                    counterpartyColumnIndex = 2,
                ),
            )
            advanceUntilIdle()

            val mapping = viewModel.uiState.value.statementImport
                as StatementImportUiState.Mapping
            assertEquals(2, mapping.preview?.validRowCount)
            assertEquals(0, mapping.preview?.invalidRowCount)
            assertEquals(3, mapping.columns.size)

            viewModel.confirmStatementImport()
            advanceUntilIdle()

            val completed = viewModel.uiState.value.statementImport
                as StatementImportUiState.Completed
            assertEquals(2, completed.readyForReviewCount)
            assertEquals(0, completed.rejectedRowCount)
            assertEquals(1, completed.resumedRowCount)
            assertEquals(initialPageCalls + 1, evidenceManager.pageCalls)
            assertTrue(statementImport.session.header.isEmpty())
        }

    @Test
    fun `stopping a structured import releases its session and leaves resumable row state`() =
        runTest(dispatcher) {
            val reader = FakeDelimitedStatementDocumentReader(
                SelectedDelimitedStatementReadResult.Success(
                    SelectedDelimitedStatementEvidence(
                        mediaType = "text/csv",
                        bytes = "date,amount,counterparty\n2026-07-25,-1.00,Shop"
                            .toByteArray(),
                    ),
                ),
            )
            val statementImport = FakeDelimitedStatementImport(suspendConfirmation = true)
            val viewModel = viewModel(
                repository = FakeLedgerRepository(),
                delimitedImport = statementImport,
                delimitedReader = reader,
            )
            advanceUntilIdle()
            viewModel.ingestSelectedDelimitedStatement(
                documentUri = "content://test/statement-stop",
                delimiter = DelimitedDelimiter.COMMA,
            )
            advanceUntilIdle()
            val initial = viewModel.uiState.value.statementImport
                as StatementImportUiState.Mapping
            viewModel.updateStatementImportMapping(
                initial.input.copy(
                    dateColumnIndex = 0,
                    amountColumnIndex = 1,
                    counterpartyColumnIndex = 2,
                ),
            )
            advanceUntilIdle()

            viewModel.confirmStatementImport()
            runCurrent()
            assertTrue(viewModel.uiState.value.statementImport is StatementImportUiState.Importing)

            viewModel.cancelStatementImport()
            advanceUntilIdle()

            assertEquals(StatementImportUiState.Idle, viewModel.uiState.value.statementImport)
            assertTrue(statementImport.session.header.isEmpty())
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
    fun `photo OCR processes at most five images serially and emits one batch result`() =
        runTest(dispatcher) {
            val importer = FakeSelectedPhotoOcrImporter(
                listOf(
                    readyPhotoResult("photo-proposal-1"),
                    failedPhotoResult(SourceCaptureError.PARSE_REJECTED),
                    readyPhotoResult("photo-proposal-3"),
                    failedPhotoResult(SourceCaptureError.EMPTY_CONTENT),
                    readyPhotoResult("photo-proposal-5"),
                ),
            )
            val evidenceManager = FakeSourceEvidenceManager()
            val viewModel = viewModel(
                repository = FakeLedgerRepository(),
                evidenceManager = evidenceManager,
                selectedPhotoOcrImporter = importer,
            )
            advanceUntilIdle()
            val initialPageCalls = evidenceManager.pageCalls

            viewModel.ingestSelectedPhotos(
                (1..6).map { index -> "content://test/photo-$index" },
            )
            advanceUntilIdle()

            assertEquals(
                (1..5).map { index -> "content://test/photo-$index" },
                importer.uriStrings,
            )
            assertEquals(5, importer.commandIds.distinct().size)
            assertEquals(1, importer.maxConcurrentCalls)
            assertEquals(initialPageCalls + 1, evidenceManager.pageCalls)
            assertNull(viewModel.uiState.value.photoOcrBatch)
            assertEquals(
                BillUiEvent.PhotoOcrBatchCompleted(
                    firstProposalId = "photo-proposal-1",
                    readyForReviewCount = 3,
                    failedCount = 2,
                    firstFailure = SourceCaptureError.PARSE_REJECTED,
                ),
                viewModel.events.first(),
            )
        }

    @Test
    fun `photo OCR exposes progress and ignores a second batch while the first is active`() =
        runTest(dispatcher) {
            val releaseFirst = CompletableDeferred<Unit>()
            val importer = FakeSelectedPhotoOcrImporter(
                results = listOf(readyPhotoResult("first-photo-proposal")),
                releaseFirst = releaseFirst,
            )
            val viewModel = viewModel(
                repository = FakeLedgerRepository(),
                selectedPhotoOcrImporter = importer,
            )
            advanceUntilIdle()

            viewModel.ingestSelectedPhotos(listOf("content://test/first-photo"))
            assertEquals(
                PhotoOcrBatchUiState(processedCount = 0, totalCount = 1),
                viewModel.uiState.value.photoOcrBatch,
            )
            runCurrent()

            viewModel.ingestSelectedPhotos(listOf("content://test/ignored-photo"))
            releaseFirst.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("content://test/first-photo"), importer.uriStrings)
            assertNull(viewModel.uiState.value.photoOcrBatch)
            assertEquals(
                BillUiEvent.PhotoOcrBatchCompleted(
                    firstProposalId = "first-photo-proposal",
                    readyForReviewCount = 1,
                    failedCount = 0,
                    firstFailure = null,
                ),
                viewModel.events.first(),
            )
        }

    @Test
    fun `photo OCR retries one opaque identity collision with a fresh command`() =
        runTest(dispatcher) {
            val importer = FakeSelectedPhotoOcrImporter(
                results = listOf(
                    failedPhotoResult(SourceCaptureError.EVIDENCE_COLLISION),
                    readyPhotoResult("retried-photo-proposal"),
                ),
            )
            val viewModel = viewModel(
                repository = FakeLedgerRepository(),
                selectedPhotoOcrImporter = importer,
            )
            advanceUntilIdle()

            viewModel.ingestSelectedPhotos(listOf("content://test/retried-photo"))
            advanceUntilIdle()

            assertEquals(2, importer.commandIds.size)
            assertEquals(2, importer.commandIds.distinct().size)
            assertEquals(
                listOf("content://test/retried-photo", "content://test/retried-photo"),
                importer.uriStrings,
            )
            assertEquals(
                BillUiEvent.PhotoOcrBatchCompleted(
                    firstProposalId = "retried-photo-proposal",
                    readyForReviewCount = 1,
                    failedCount = 0,
                    firstFailure = null,
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
        selectedPhotoOcrImporter: SelectedPhotoOcrImporter =
            SelectedPhotoOcrImporter.Unavailable,
        delimitedImport: DelimitedStatementImport = DelimitedStatementImport.Unavailable,
        delimitedReader: SelectedDelimitedStatementDocumentReader =
            SelectedDelimitedStatementDocumentReader.Unavailable,
    ) = BillViewModel(
        service = BillService(
            repository = repository,
            clock = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC),
            localZoneId = ZoneOffset.UTC,
        ),
        sharedTextIngestionService = capture,
        sourceEvidenceManager = evidenceManager,
        selectedTextFileIngestionService = selectedFileCapture,
        selectedTextDocumentReader = documentReader,
        sharedReceiptImageIngestionService = receiptImageCapture,
        sharedReceiptImageDocumentReader = receiptImageReader,
        selectedPhotoOcrImporter = selectedPhotoOcrImporter,
        delimitedStatementImport = delimitedImport,
        delimitedStatementDocumentReader = delimitedReader,
        statementImportDispatcher = dispatcher,
    )
}

private fun BillUiEvent.successKind(): BillOperationKind =
    (this as BillUiEvent.OperationSucceeded).kind

private fun readyPhotoResult(proposalId: String) = SourceCaptureResult.ReadyForReview(
    proposalId = proposalId,
    rawEventId = "event-$proposalId",
    alreadyPresent = false,
)

private fun failedPhotoResult(error: SourceCaptureError) = SourceCaptureResult.Failure(
    error = error,
    diagnosticCode = null,
)

private class FakeLedgerRepository(
    private val observedState: Flow<LedgerState> = MutableStateFlow(emptyLedgerState()),
    private val findableAccounts: Map<AccountId, LedgerAccount> = emptyMap(),
) : LedgerRepository {

    var createdAccount: LedgerAccount? = null
    var openingTransaction: PostedTransaction? = null
    var createdBalanceSnapshot: BalanceSnapshot? = null

    override fun observeState(): Flow<LedgerState> = observedState

    override suspend fun findAccount(id: AccountId): LedgerAccount? = findableAccounts[id]

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

    override suspend fun createBalanceSnapshot(
        snapshot: BalanceSnapshot,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        createdBalanceSnapshot = snapshot
        return RepositoryWriteResult(RepositoryWriteStatus.APPLIED, snapshot.id.value)
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

    override suspend fun activeRefundTotal(originalTransactionId: TransactionId) = null

    override suspend fun resolveReconciliation(
        resolution: ReconciliationResolution,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        resolvedReconciliation = resolution
        return RepositoryWriteResult(
            RepositoryWriteStatus.APPLIED,
            resolution.transaction.id.value,
        )
    }

    override suspend fun voidTransaction(
        transactionId: TransactionId,
        auditRecord: AuditRecord,
    ) = unsupported()

    private fun unsupported() = RepositoryWriteResult(RepositoryWriteStatus.INVALID_STATE)

    var resolvedReconciliation: ReconciliationResolution? = null
}

private fun emptyLedgerState() = LedgerState(
    accountBalances = emptyList(),
    pendingDrafts = emptyList(),
    recentTransactions = emptyList(),
)

private fun ledgerAccount(
    id: String,
    type: AccountType,
    now: Instant,
) = LedgerAccount(
    id = AccountId(id),
    name = "TEST $id",
    normalizedName = "test $id",
    type = type,
    currency = CurrencyCode.CNY,
    isSystem = false,
    isArchived = false,
    createdAt = now.minusSeconds(120),
    creationCommandId = CommandId("create-$id"),
)

private fun reconciliationDraft(
    id: String,
    type: TransactionType,
    fundingAccountId: AccountId,
    now: Instant,
) = ManualDraft(
    id = DraftId(id),
    state = DraftState.WAITING_USER,
    type = type,
    amount = Money.cny(2_500L),
    occurredAt = now.minusSeconds(60),
    counterparty = "TEST COUNTERPARTY",
    note = null,
    fundingAccountId = fundingAccountId,
    createdAt = now.minusSeconds(120),
    updatedAt = now.minusSeconds(30),
    creationCommandId = CommandId("create-$id"),
    sourceMode = TransactionSourceMode.EXTERNAL,
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

private class FakeDelimitedStatementDocumentReader(
    private val result: SelectedDelimitedStatementReadResult,
) : SelectedDelimitedStatementDocumentReader {
    var uriString: String? = null

    override suspend fun read(uriString: String): SelectedDelimitedStatementReadResult {
        this.uriString = uriString
        return result
    }
}

private class FakeDelimitedStatementImport(
    private val suspendConfirmation: Boolean = false,
) : DelimitedStatementImport {
    private val fileHash = EvidenceHash("a".repeat(64))
    val session = LocalDelimitedStatementSession(
        DelimitedDocument(
            delimiter = DelimitedDelimiter.COMMA,
            fileHash = fileHash,
            header = listOf("date", "amount", "counterparty"),
            rows = listOf(
                DelimitedRow(1, listOf("2026-07-25", "-1.00", "Shop")),
                DelimitedRow(2, listOf("2026-07-26", "2.00", "Refund")),
            ),
        ),
    )

    override fun preview(
        evidence: SelectedDelimitedStatementEvidence,
        delimiter: DelimitedDelimiter,
    ): StatementImportPreviewResult = StatementImportPreviewResult.Ready(session)

    override fun previewMapping(
        session: LocalDelimitedStatementSession,
        mapping: DelimitedStatementMapping,
    ): StatementImportMappingPreviewResult = StatementImportMappingPreviewResult.Ready(
        StatementImportMappingPreview(
            validRowCount = 2,
            invalidRowCount = 0,
            previewRows = listOf(
                StatementImportMappedPreviewRow(1, null),
                StatementImportMappedPreviewRow(2, null),
            ),
        ),
    )

    override suspend fun confirm(
        session: LocalDelimitedStatementSession,
        mapping: DelimitedStatementMapping,
        onProgress: (StatementImportProgress) -> Unit,
    ): StatementImportConfirmationResult {
        if (suspendConfirmation) awaitCancellation()
        val batchId = StatementImportBatchId("statement-import-test")
        onProgress(StatementImportProgress(batchId, 2, 2))
        return StatementImportConfirmationResult.Completed(
            batch = StatementImportBatchRecord(
                id = batchId,
                fileHash = fileHash,
                mappingHash = EvidenceHash("b".repeat(64)),
                totalRowCount = 2,
                processedRowCount = 2,
                readyForReviewCount = 2,
                rejectedRowCount = 0,
                state = StatementImportBatchState.COMPLETED,
                createdAt = Instant.parse("2026-07-19T00:00:00Z"),
                updatedAt = Instant.parse("2026-07-19T00:00:01Z"),
            ),
            resumedRowCount = 1,
        )
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

private class FakeSelectedPhotoOcrImporter(
    private val results: List<SourceCaptureResult>,
    private val releaseFirst: CompletableDeferred<Unit>? = null,
) : SelectedPhotoOcrImporter {
    val commandIds = mutableListOf<String>()
    val uriStrings = mutableListOf<String?>()
    var maxConcurrentCalls = 0
        private set

    private var activeCalls = 0

    override suspend fun ingest(
        commandId: String,
        uriString: String?,
    ): SourceCaptureResult {
        val index = uriStrings.size
        commandIds += commandId
        uriStrings += uriString
        activeCalls += 1
        maxConcurrentCalls = maxOf(maxConcurrentCalls, activeCalls)
        return try {
            if (index == 0) releaseFirst?.await()
            results.getOrElse(index) {
                failedPhotoResult(SourceCaptureError.COMMIT_FAILED)
            }
        } finally {
            activeCalls -= 1
        }
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
