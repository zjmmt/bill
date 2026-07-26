package dev.bill.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bill.app.quickcapture.SelectedPhotoOcrImporter
import dev.bill.application.BillService
import dev.bill.application.BillSnapshot
import dev.bill.application.CreateAccountCommand
import dev.bill.application.CreateExternalDraftCommand
import dev.bill.application.CreateManualDraftCommand
import dev.bill.application.DraftSummaryKind
import dev.bill.application.OperationError
import dev.bill.application.OperationResult
import dev.bill.application.SelectedTextFileCapture
import dev.bill.application.SharedReceiptImageCapture
import dev.bill.application.SharedTextCapture
import dev.bill.application.SourceCaptureError
import dev.bill.application.SourceCaptureResult
import dev.bill.application.SourceEvidenceManager
import dev.bill.application.SourceEvidenceOperationError
import dev.bill.application.SourceEvidenceOperationResult
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.DraftId
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import dev.bill.source.contract.RawEventId
import dev.bill.source.review.SourceEvidenceCursor
import dev.bill.source.review.SourceEvidenceItem
import dev.bill.source.review.SourceEvidenceOverview
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BillOperationKind {
    CREATE_ACCOUNT,
    CREATE_DRAFT,
    CREATE_EXTERNAL_DRAFT,
    DISMISS_SOURCE_REVIEW,
    SELECT_FUNDING_ACCOUNT,
    CONFIRM_DRAFT,
    DISMISS_DRAFT,
    VOID_TRANSACTION,
}

enum class EvidenceOperationKind {
    UPDATE_RETENTION,
    CLEAR_PAYLOAD,
}

data class ActiveBillOperation(
    val kind: BillOperationKind,
    val entityId: String? = null,
)

data class ActiveEvidenceOperation(
    val kind: EvidenceOperationKind,
    val rawEventId: String? = null,
)

data class EvidenceSettingsUiState(
    val overview: SourceEvidenceOverview? = null,
    val items: List<SourceEvidenceItem> = emptyList(),
    val nextCursor: SourceEvidenceCursor? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasFatalError: Boolean = false,
    val activeOperation: ActiveEvidenceOperation? = null,
)

data class BillUiState(
    val snapshot: BillSnapshot? = null,
    val isLoading: Boolean = true,
    val hasFatalError: Boolean = false,
    val amountsMasked: Boolean = true,
    val isPrivacyCovered: Boolean = true,
    val activeOperation: ActiveBillOperation? = null,
    val operationError: OperationError? = null,
    val evidence: EvidenceSettingsUiState = EvidenceSettingsUiState(),
)

sealed interface BillUiEvent {
    data class OperationSucceeded(
        val kind: BillOperationKind,
        val entityId: String?,
    ) : BillUiEvent

    data class OperationFailed(
        val kind: BillOperationKind,
        val error: OperationError,
    ) : BillUiEvent

    data class SourceReviewReady(
        val commandId: String,
        val proposalId: String,
        val alreadyPresent: Boolean,
    ) : BillUiEvent

    data class SourceCaptureFailed(
        val commandId: String,
        val error: SourceCaptureError,
    ) : BillUiEvent

    data class EvidenceOperationSucceeded(
        val kind: EvidenceOperationKind,
        val rawEventId: String?,
    ) : BillUiEvent

    data class EvidenceOperationFailed(
        val kind: EvidenceOperationKind,
        val error: SourceEvidenceOperationError,
    ) : BillUiEvent
}

class BillViewModel(
    private val service: BillService,
    private val sharedTextIngestionService: SharedTextCapture,
    private val sourceEvidenceManager: SourceEvidenceManager = SourceEvidenceManager.Empty,
    private val selectedTextFileIngestionService: SelectedTextFileCapture =
        SelectedTextFileCapture.Unavailable,
    private val selectedTextDocumentReader: SelectedTextDocumentReader =
        SelectedTextDocumentReader.Unavailable,
    private val sharedReceiptImageIngestionService: SharedReceiptImageCapture =
        SharedReceiptImageCapture.Unavailable,
    private val sharedReceiptImageDocumentReader: SharedReceiptImageDocumentReader =
        SharedReceiptImageDocumentReader.Unavailable,
    private val selectedPhotoOcrImporter: SelectedPhotoOcrImporter =
        SelectedPhotoOcrImporter.Unavailable,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(BillUiState())
    val uiState: StateFlow<BillUiState> = mutableUiState.asStateFlow()

    private val eventChannel = Channel<BillUiEvent>(capacity = Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var snapshotJob: Job? = null
    private var evidenceOverviewJob: Job? = null
    private var evidencePageJob: Job? = null
    private var evidencePageGeneration = 0L

    init {
        observeSnapshot()
        observeEvidenceOverview()
        runEvidenceMaintenance()
    }

    fun toggleAmounts() {
        mutableUiState.update { state -> state.copy(amountsMasked = !state.amountsMasked) }
    }

    fun maskAmounts() {
        mutableUiState.update { state -> state.copy(amountsMasked = true) }
    }

    fun coverForPrivacy() {
        mutableUiState.update { state ->
            state.copy(amountsMasked = true, isPrivacyCovered = true)
        }
    }

    fun revealForForeground() {
        mutableUiState.update { state -> state.copy(isPrivacyCovered = false) }
    }

    fun newCommandId(): String = service.newCommandId().value

    fun clearOperationFeedback() {
        mutableUiState.update { state -> state.copy(operationError = null) }
    }

    fun retry() {
        observeSnapshot()
    }

    fun retryEvidence() {
        observeEvidenceOverview()
        loadEvidencePage(reset = true)
    }

    fun loadMoreEvidence() {
        val evidence = mutableUiState.value.evidence
        if (evidence.nextCursor == null || evidence.isLoading || evidence.isLoadingMore) return
        loadEvidencePage(reset = false)
    }

    fun updateEvidenceRetention(retentionDays: Int?) {
        performEvidence(EvidenceOperationKind.UPDATE_RETENTION) {
            sourceEvidenceManager.updateRetentionPolicy(retentionDays)
        }
    }

    fun clearEvidence(rawEventId: String) {
        val id = try {
            RawEventId(rawEventId)
        } catch (_: IllegalArgumentException) {
            viewModelScope.launch {
                eventChannel.send(
                    BillUiEvent.EvidenceOperationFailed(
                        kind = EvidenceOperationKind.CLEAR_PAYLOAD,
                        error = SourceEvidenceOperationError.INVALID_STATE,
                    ),
                )
            }
            return
        }
        performEvidence(EvidenceOperationKind.CLEAR_PAYLOAD, rawEventId) {
            sourceEvidenceManager.clearEvidence(id)
        }
    }

    fun createAccount(
        commandId: String,
        name: String,
        type: AccountType,
        openingBalance: String,
        currency: CurrencyCode = CurrencyCode.CNY,
    ) {
        perform(BillOperationKind.CREATE_ACCOUNT) {
            service.createAccount(
                CreateAccountCommand(
                    commandId = CommandId(commandId),
                    name = name,
                    type = type,
                    openingBalanceText = openingBalance,
                    currency = currency,
                ),
            )
        }
    }

    fun createManualDraft(
        commandId: String,
        kind: DraftSummaryKind,
        amount: String,
        counterparty: String,
        note: String,
        currency: CurrencyCode = CurrencyCode.CNY,
    ) {
        perform(BillOperationKind.CREATE_DRAFT) {
            service.createManualDraft(
                CreateManualDraftCommand(
                    commandId = CommandId(commandId),
                    type = when (kind) {
                        DraftSummaryKind.EXPENSE -> TransactionType.EXPENSE
                        DraftSummaryKind.INCOME -> TransactionType.INCOME
                    },
                    amountText = amount,
                    counterparty = counterparty,
                    note = note,
                    currency = currency,
                ),
            )
        }
    }

    fun createExternalDraft(
        commandId: String,
        proposalId: String,
        kind: DraftSummaryKind,
        amount: String,
        counterparty: String,
        note: String,
        currency: CurrencyCode = CurrencyCode.CNY,
    ) {
        perform(BillOperationKind.CREATE_EXTERNAL_DRAFT, proposalId) {
            service.createExternalDraft(
                CreateExternalDraftCommand(
                    commandId = CommandId(commandId),
                    proposalId = proposalId,
                    type = when (kind) {
                        DraftSummaryKind.EXPENSE -> TransactionType.EXPENSE
                        DraftSummaryKind.INCOME -> TransactionType.INCOME
                    },
                    amountText = amount,
                    counterparty = counterparty,
                    note = note,
                    currency = currency,
                ),
            )
        }
    }

    fun ingestSharedText(commandId: String, text: CharSequence?) {
        viewModelScope.launch {
            val result = try {
                sharedTextIngestionService.ingest(commandId, text)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SourceCaptureResult.Failure(
                    error = SourceCaptureError.COMMIT_FAILED,
                    diagnosticCode = null,
                )
            }
            publishSourceCaptureResult(commandId, result)
        }
    }

    fun ingestSharedReceiptImage(
        commandId: String,
        uriString: String?,
        declaredMediaType: String?,
    ) {
        viewModelScope.launch {
            val readResult = try {
                sharedReceiptImageDocumentReader.read(uriString, declaredMediaType)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SharedReceiptImageReadResult.Failure(SourceCaptureError.COMMIT_FAILED)
            }
            val result = when (readResult) {
                is SharedReceiptImageReadResult.Failure -> SourceCaptureResult.Failure(
                    error = readResult.error,
                    diagnosticCode = null,
                )

                is SharedReceiptImageReadResult.Success -> {
                    try {
                        sharedReceiptImageIngestionService.ingest(commandId, readResult.evidence)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        SourceCaptureResult.Failure(
                            error = SourceCaptureError.COMMIT_FAILED,
                            diagnosticCode = null,
                        )
                    } finally {
                        readResult.evidence.bytes.fill(0)
                    }
                }
            }
            publishSourceCaptureResult(commandId, result)
        }
    }

    fun ingestSelectedTextFile(commandId: String, documentUri: String) {
        viewModelScope.launch {
            val readResult = try {
                selectedTextDocumentReader.read(documentUri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SelectedTextDocumentReadResult.Failure(SourceCaptureError.COMMIT_FAILED)
            }
            val result = when (readResult) {
                is SelectedTextDocumentReadResult.Failure -> SourceCaptureResult.Failure(
                    error = readResult.error,
                    diagnosticCode = null,
                )

                is SelectedTextDocumentReadResult.Success -> {
                    try {
                        selectedTextFileIngestionService.ingest(commandId, readResult.evidence)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        SourceCaptureResult.Failure(
                            error = SourceCaptureError.COMMIT_FAILED,
                            diagnosticCode = null,
                        )
                    } finally {
                        readResult.evidence.bytes.fill(0)
                    }
                }
            }
            publishSourceCaptureResult(commandId, result)
        }
    }

    fun ingestSelectedPhotos(documentUris: List<String>) {
        val boundedUris = documentUris.take(MAX_SELECTED_OCR_IMAGES)
        if (boundedUris.isEmpty()) return
        viewModelScope.launch {
            boundedUris.forEach { documentUri ->
                val commandId = service.newCommandId().value
                val result = try {
                    selectedPhotoOcrImporter.ingest(commandId, documentUri)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    SourceCaptureResult.Failure(
                        error = SourceCaptureError.COMMIT_FAILED,
                        diagnosticCode = null,
                    )
                }
                publishSourceCaptureResult(commandId, result)
            }
        }
    }

    fun dismissSourceProposal(commandId: String, proposalId: String) {
        perform(BillOperationKind.DISMISS_SOURCE_REVIEW, proposalId) {
            service.dismissSourceProposal(
                commandId = CommandId(commandId),
                proposalId = proposalId,
            )
        }
    }

    fun selectFundingAccount(draftId: String, accountId: String) {
        perform(BillOperationKind.SELECT_FUNDING_ACCOUNT, draftId) {
            service.selectFundingAccount(
                commandId = service.newCommandId(),
                draftId = DraftId(draftId),
                accountId = AccountId(accountId),
            )
        }
    }

    fun confirmDraft(draftId: String) {
        perform(BillOperationKind.CONFIRM_DRAFT, draftId) {
            service.confirmDraft(
                commandId = service.newCommandId(),
                draftId = DraftId(draftId),
            )
        }
    }

    fun dismissDraft(draftId: String) {
        perform(BillOperationKind.DISMISS_DRAFT, draftId) {
            service.dismissDraft(
                commandId = service.newCommandId(),
                draftId = DraftId(draftId),
            )
        }
    }

    fun voidTransaction(transactionId: String) {
        perform(BillOperationKind.VOID_TRANSACTION, transactionId) {
            service.voidTransaction(
                commandId = service.newCommandId(),
                transactionId = TransactionId(transactionId),
            )
        }
    }

    private fun observeSnapshot() {
        snapshotJob?.cancel()
        mutableUiState.update { state ->
            state.copy(isLoading = state.snapshot == null, hasFatalError = false)
        }
        snapshotJob = viewModelScope.launch {
            service.observeSnapshot()
                .catch {
                    mutableUiState.update { state ->
                        state.copy(
                            snapshot = null,
                            isLoading = false,
                            hasFatalError = true,
                        )
                    }
                }
                .collect { snapshot ->
                    mutableUiState.update { state ->
                        state.copy(
                            snapshot = snapshot,
                            isLoading = false,
                            hasFatalError = false,
                        )
                    }
                }
        }
    }

    private suspend fun publishSourceCaptureResult(
        commandId: String,
        result: SourceCaptureResult,
    ) {
        when (result) {
            is SourceCaptureResult.ReadyForReview -> {
                loadEvidencePage(reset = true)
                eventChannel.send(
                    BillUiEvent.SourceReviewReady(
                        commandId = commandId,
                        proposalId = result.proposalId,
                        alreadyPresent = result.alreadyPresent,
                    ),
                )
            }

            is SourceCaptureResult.Failure -> eventChannel.send(
                BillUiEvent.SourceCaptureFailed(commandId, result.error),
            )
        }
    }

    private fun observeEvidenceOverview() {
        evidenceOverviewJob?.cancel()
        evidenceOverviewJob = viewModelScope.launch {
            sourceEvidenceManager.observeOverview()
                .catch {
                    mutableUiState.update { state ->
                        state.copy(
                            evidence = state.evidence.copy(
                                isLoading = false,
                                hasFatalError = true,
                            ),
                        )
                    }
                }
                .collect { overview ->
                    mutableUiState.update { state ->
                        state.copy(
                            evidence = state.evidence.copy(
                                overview = overview,
                                hasFatalError = false,
                            ),
                        )
                    }
                }
        }
    }

    private fun runEvidenceMaintenance() {
        viewModelScope.launch {
            try {
                sourceEvidenceManager.runMaintenance()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableUiState.update { state ->
                    state.copy(
                        evidence = state.evidence.copy(
                            isLoading = false,
                            hasFatalError = true,
                        ),
                    )
                }
            }
            loadEvidencePage(reset = true)
        }
    }

    private fun loadEvidencePage(reset: Boolean) {
        if (reset) {
            evidencePageJob?.cancel()
        } else if (evidencePageJob?.isActive == true) {
            return
        }
        val current = mutableUiState.value.evidence
        val cursor = if (reset) null else current.nextCursor ?: return
        evidencePageGeneration += 1L
        val generation = evidencePageGeneration
        mutableUiState.update { state ->
            state.copy(
                evidence = state.evidence.copy(
                    isLoading = reset && state.evidence.items.isEmpty(),
                    isLoadingMore = !reset,
                    hasFatalError = false,
                ),
            )
        }
        evidencePageJob = viewModelScope.launch {
            try {
                val page = sourceEvidenceManager.page(cursor = cursor)
                if (generation != evidencePageGeneration) return@launch
                mutableUiState.update { state ->
                    val combinedItems = if (reset) {
                        page.items
                    } else {
                        (state.evidence.items + page.items)
                            .distinctBy { it.rawEventId.value }
                    }
                    state.copy(
                        evidence = state.evidence.copy(
                            items = combinedItems,
                            nextCursor = page.nextCursor,
                            isLoading = false,
                            isLoadingMore = false,
                            hasFatalError = false,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation != evidencePageGeneration) return@launch
                mutableUiState.update { state ->
                    state.copy(
                        evidence = state.evidence.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            hasFatalError = true,
                        ),
                    )
                }
            }
        }
    }

    private fun performEvidence(
        kind: EvidenceOperationKind,
        rawEventId: String? = null,
        operation: suspend () -> SourceEvidenceOperationResult,
    ) {
        if (mutableUiState.value.evidence.activeOperation != null) return
        mutableUiState.update { state ->
            state.copy(
                evidence = state.evidence.copy(
                    activeOperation = ActiveEvidenceOperation(kind, rawEventId),
                ),
            )
        }
        viewModelScope.launch {
            val result = try {
                operation()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SourceEvidenceOperationResult.Failure(SourceEvidenceOperationError.CONFLICT)
            }
            mutableUiState.update { state ->
                state.copy(
                    evidence = state.evidence.copy(activeOperation = null),
                )
            }
            when (result) {
                is SourceEvidenceOperationResult.Success -> {
                    eventChannel.send(
                        BillUiEvent.EvidenceOperationSucceeded(kind, rawEventId),
                    )
                    loadEvidencePage(reset = true)
                }

                is SourceEvidenceOperationResult.Failure -> eventChannel.send(
                    BillUiEvent.EvidenceOperationFailed(kind, result.error),
                )
            }
        }
    }

    private fun perform(
        kind: BillOperationKind,
        entityId: String? = null,
        operation: suspend () -> OperationResult,
    ) {
        if (mutableUiState.value.activeOperation != null) return
        mutableUiState.update { state ->
            state.copy(
                activeOperation = ActiveBillOperation(kind, entityId),
                operationError = null,
            )
        }
        viewModelScope.launch {
            val result = try {
                operation()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                OperationResult.Failure(OperationError.CONFLICT)
            }
            when (result) {
                is OperationResult.Success -> {
                    mutableUiState.update { state ->
                        state.copy(activeOperation = null, operationError = null)
                    }
                    eventChannel.send(
                        BillUiEvent.OperationSucceeded(
                            kind = kind,
                            entityId = result.entityId,
                        ),
                    )
                    if (
                        kind == BillOperationKind.CREATE_EXTERNAL_DRAFT ||
                        kind == BillOperationKind.DISMISS_SOURCE_REVIEW
                    ) {
                        loadEvidencePage(reset = true)
                    }
                }

                is OperationResult.Failure -> {
                    mutableUiState.update { state ->
                        state.copy(activeOperation = null, operationError = result.error)
                    }
                    eventChannel.send(BillUiEvent.OperationFailed(kind, result.error))
                }
            }
        }
    }

    class Factory(
        private val service: BillService,
        private val sharedTextIngestionService: SharedTextCapture,
        private val sourceEvidenceManager: SourceEvidenceManager = SourceEvidenceManager.Empty,
        private val selectedTextFileIngestionService: SelectedTextFileCapture =
            SelectedTextFileCapture.Unavailable,
        private val selectedTextDocumentReader: SelectedTextDocumentReader =
            SelectedTextDocumentReader.Unavailable,
        private val sharedReceiptImageIngestionService: SharedReceiptImageCapture =
            SharedReceiptImageCapture.Unavailable,
        private val sharedReceiptImageDocumentReader: SharedReceiptImageDocumentReader =
            SharedReceiptImageDocumentReader.Unavailable,
        private val selectedPhotoOcrImporter: SelectedPhotoOcrImporter =
            SelectedPhotoOcrImporter.Unavailable,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BillViewModel::class.java))
            return BillViewModel(
                service = service,
                sharedTextIngestionService = sharedTextIngestionService,
                sourceEvidenceManager = sourceEvidenceManager,
                selectedTextFileIngestionService = selectedTextFileIngestionService,
                selectedTextDocumentReader = selectedTextDocumentReader,
                sharedReceiptImageIngestionService = sharedReceiptImageIngestionService,
                sharedReceiptImageDocumentReader = sharedReceiptImageDocumentReader,
                selectedPhotoOcrImporter = selectedPhotoOcrImporter,
            ) as T
        }
    }

    private companion object {
        const val MAX_SELECTED_OCR_IMAGES = 5
    }
}
