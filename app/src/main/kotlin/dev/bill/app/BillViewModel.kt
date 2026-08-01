package dev.bill.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bill.app.quickcapture.SelectedPhotoOcrImporter
import dev.bill.app.quickcapture.SelectedImageOcrReadResult
import dev.bill.app.quickcapture.SelectedImageOcrReader
import dev.bill.application.BillService
import dev.bill.application.BillSnapshot
import dev.bill.application.CreateAccountCommand
import dev.bill.application.CreateBalanceSnapshotCommand
import dev.bill.application.CreateExternalDraftCommand
import dev.bill.application.CreateInvestmentPositionCommand
import dev.bill.application.CreateManualDraftCommand
import dev.bill.application.DelimitedStatementImport
import dev.bill.application.DraftSummaryKind
import dev.bill.application.LocalDelimitedStatementSession
import dev.bill.application.InvestmentPositionOcrPrefill
import dev.bill.application.InvestmentPositionOcrPrefillError
import dev.bill.application.InvestmentPositionOcrPrefillParser
import dev.bill.application.InvestmentPositionOcrPrefillResult
import dev.bill.application.OperationError
import dev.bill.application.OperationResult
import dev.bill.application.ResolveReconciliationCommand
import dev.bill.application.StatementImportConfirmationResult
import dev.bill.application.StatementImportMappingPreviewResult
import dev.bill.application.StatementImportPreviewError
import dev.bill.application.StatementImportPreviewResult
import dev.bill.application.UpdateDraftCommand
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
import dev.bill.core.domain.InvestmentPositionSourceMode
import dev.bill.core.domain.ObservedChannel
import dev.bill.core.model.AccountId
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.TransactionId
import dev.bill.core.model.TransactionType
import dev.bill.source.contract.RawEventId
import dev.bill.source.genericdelimited.DelimitedDelimiter
import dev.bill.source.genericdelimited.DelimitedReadError
import dev.bill.source.genericdelimited.DelimitedStatementMapping
import dev.bill.source.genericdelimited.StatementAmountFormat
import dev.bill.source.genericdelimited.StatementDateFormat
import dev.bill.source.genericdelimited.StatementDirection
import dev.bill.source.genericdelimited.StatementDirectionMapping
import dev.bill.source.review.SourceEvidenceCursor
import dev.bill.source.review.SourceEvidenceItem
import dev.bill.source.review.SourceEvidenceOverview
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BillOperationKind {
    CREATE_ACCOUNT,
    CREATE_BALANCE_SNAPSHOT,
    CREATE_INVESTMENT_POSITION,
    CREATE_DRAFT,
    CREATE_EXTERNAL_DRAFT,
    UPDATE_DRAFT,
    DISMISS_SOURCE_REVIEW,
    SELECT_FUNDING_ACCOUNT,
    CONFIRM_DRAFT,
    RESOLVE_RECONCILIATION,
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
    val statementImport: StatementImportUiState = StatementImportUiState.Idle,
    val photoOcrBatch: PhotoOcrBatchUiState? = null,
    val isInvestmentOcrRunning: Boolean = false,
)

enum class InvestmentOcrPrefillUiError {
    EMPTY_IMAGE,
    IMAGE_TOO_LARGE,
    UNREADABLE_IMAGE,
    NO_RECOGNIZED_FIELDS,
    AMBIGUOUS_FIELDS,
}

data class PhotoOcrBatchUiState(
    val processedCount: Int,
    val totalCount: Int,
) {
    init {
        require(totalCount in 1..5)
        require(processedCount in 0..totalCount)
    }
}

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

    data class PhotoOcrBatchCompleted(
        val firstProposalId: String?,
        val readyForReviewCount: Int,
        val failedCount: Int,
        val firstFailure: SourceCaptureError?,
    ) : BillUiEvent {
        init {
            require(readyForReviewCount >= 0)
            require(failedCount >= 0)
            require(readyForReviewCount + failedCount in 1..5)
            require((firstProposalId != null) == (readyForReviewCount > 0))
            require((firstFailure != null) == (failedCount > 0))
        }
    }

    data class InvestmentOcrPrefillReady(
        val prefill: InvestmentPositionOcrPrefill,
    ) : BillUiEvent

    data class InvestmentOcrPrefillFailed(
        val error: InvestmentOcrPrefillUiError,
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
    private val selectedImageOcrReader: SelectedImageOcrReader = SelectedImageOcrReader.Unavailable,
    private val delimitedStatementImport: DelimitedStatementImport =
        DelimitedStatementImport.Unavailable,
    private val delimitedStatementDocumentReader: SelectedDelimitedStatementDocumentReader =
        SelectedDelimitedStatementDocumentReader.Unavailable,
    private val statementImportDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(BillUiState())
    val uiState: StateFlow<BillUiState> = mutableUiState.asStateFlow()

    private val eventChannel = Channel<BillUiEvent>(capacity = Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var snapshotJob: Job? = null
    private var evidenceOverviewJob: Job? = null
    private var evidencePageJob: Job? = null
    private var evidencePageGeneration = 0L
    private var statementDocumentJob: Job? = null
    private var statementMappingJob: Job? = null
    private var statementImportJob: Job? = null
    private var photoOcrJob: Job? = null
    private var investmentOcrJob: Job? = null
    private var statementDocumentGeneration = 0L
    private var statementMappingGeneration = 0L
    private var statementSession: LocalDelimitedStatementSession? = null
    private var lastStatementMappingState: StatementImportUiState.Mapping? = null

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

    fun createBalanceSnapshot(
        commandId: String,
        accountId: String,
        observedBalance: String,
        asOf: String,
        note: String,
    ) {
        perform(BillOperationKind.CREATE_BALANCE_SNAPSHOT, entityId = accountId) {
            service.createBalanceSnapshot(
                CreateBalanceSnapshotCommand(
                    commandId = CommandId(commandId),
                    accountId = AccountId(accountId),
                    observedBalanceText = observedBalance,
                    asOfText = asOf,
                    note = note.takeIf(String::isNotBlank),
                ),
            )
        }
    }

    fun createInvestmentPosition(
        commandId: String,
        name: String,
        instrumentCode: String,
        currentValue: String,
        units: String,
        costBasis: String,
        wasOcrPrefilled: Boolean,
    ) {
        perform(BillOperationKind.CREATE_INVESTMENT_POSITION) {
            service.createInvestmentPosition(
                CreateInvestmentPositionCommand(
                    commandId = CommandId(commandId),
                    name = name,
                    instrumentCode = instrumentCode.takeIf(String::isNotBlank),
                    currentValueText = currentValue,
                    unitsText = units.takeIf(String::isNotBlank),
                    costBasisText = costBasis.takeIf(String::isNotBlank),
                    sourceMode = if (wasOcrPrefilled) {
                        InvestmentPositionSourceMode.OCR
                    } else {
                        InvestmentPositionSourceMode.MANUAL
                    },
                ),
            )
        }
    }

    fun prefillInvestmentFromScreenshot(uriString: String?) {
        if (investmentOcrJob?.isActive == true) return
        mutableUiState.update { state -> state.copy(isInvestmentOcrRunning = true) }
        investmentOcrJob = viewModelScope.launch {
            try {
                val event = try {
                    when (val read = selectedImageOcrReader.read(uriString)) {
                        is SelectedImageOcrReadResult.Lines -> when (
                            val parsed = withContext(Dispatchers.Default) {
                                InvestmentPositionOcrPrefillParser.parse(
                                    read.values.map { line -> line.value },
                                )
                            }
                        ) {
                            is InvestmentPositionOcrPrefillResult.Success ->
                                BillUiEvent.InvestmentOcrPrefillReady(parsed.prefill)

                            is InvestmentPositionOcrPrefillResult.Failure ->
                                BillUiEvent.InvestmentOcrPrefillFailed(parsed.error.toUiError())
                        }

                        is SelectedImageOcrReadResult.Failure ->
                            BillUiEvent.InvestmentOcrPrefillFailed(
                                read.error.toInvestmentOcrUiError(),
                            )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    BillUiEvent.InvestmentOcrPrefillFailed(
                        SourceCaptureError.PARSE_REJECTED.toInvestmentOcrUiError(),
                    )
                }
                eventChannel.send(event)
            } finally {
                mutableUiState.update { state -> state.copy(isInvestmentOcrRunning = false) }
            }
        }
    }

    fun createManualDraft(
        commandId: String,
        kind: DraftSummaryKind,
        amount: String,
        counterparty: String,
        note: String,
        currency: CurrencyCode = CurrencyCode.CNY,
        observedChannel: ObservedChannel = ObservedChannel.UNKNOWN,
    ) {
        perform(BillOperationKind.CREATE_DRAFT) {
            service.createManualDraft(
                CreateManualDraftCommand(
                    commandId = CommandId(commandId),
                    type = when (kind) {
                        DraftSummaryKind.EXPENSE -> TransactionType.EXPENSE
                        DraftSummaryKind.INCOME -> TransactionType.INCOME
                        DraftSummaryKind.INVEST_BUY -> TransactionType.INVEST_BUY
                    },
                    amountText = amount,
                    counterparty = counterparty,
                    note = note,
                    currency = currency,
                    observedChannel = observedChannel,
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
        occurredAt: Instant? = null,
        investmentAccountId: String? = null,
        observedChannel: ObservedChannel = ObservedChannel.UNKNOWN,
    ) {
        perform(BillOperationKind.CREATE_EXTERNAL_DRAFT, proposalId) {
            service.createExternalDraft(
                CreateExternalDraftCommand(
                    commandId = CommandId(commandId),
                    proposalId = proposalId,
                    type = when (kind) {
                        DraftSummaryKind.EXPENSE -> TransactionType.EXPENSE
                        DraftSummaryKind.INCOME -> TransactionType.INCOME
                        DraftSummaryKind.INVEST_BUY -> TransactionType.INVEST_BUY
                    },
                    amountText = amount,
                    counterparty = counterparty,
                    note = note,
                    occurredAt = occurredAt,
                    currency = currency,
                    investmentAccountId = investmentAccountId?.let(::AccountId),
                    observedChannel = observedChannel,
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

    fun ingestSelectedDelimitedStatement(
        documentUri: String,
        delimiter: DelimitedDelimiter,
    ) {
        if (
            mutableUiState.value.statementImport is StatementImportUiState.Reading ||
            mutableUiState.value.statementImport is StatementImportUiState.Importing
        ) {
            return
        }
        clearStatementImportResources()
        val generation = statementDocumentGeneration
        mutableUiState.update { state ->
            state.copy(statementImport = StatementImportUiState.Reading(delimiter))
        }
        statementDocumentJob = viewModelScope.launch {
            val readResult = try {
                delimitedStatementDocumentReader.read(documentUri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SelectedDelimitedStatementReadResult.Failure(
                    SelectedDelimitedStatementReadError.READ_FAILED,
                )
            }
            if (
                generation != statementDocumentGeneration ||
                mutableUiState.value.statementImport !is StatementImportUiState.Reading
            ) {
                (readResult as? SelectedDelimitedStatementReadResult.Success)
                    ?.evidence
                    ?.bytes
                    ?.fill(0)
                return@launch
            }
            when (readResult) {
                is SelectedDelimitedStatementReadResult.Failure -> {
                    publishStatementImportFailure(readResult.error.toUiError())
                }

                is SelectedDelimitedStatementReadResult.Success -> {
                    var producedPreview: StatementImportPreviewResult? = null
                    val previewResult = try {
                        withContext(statementImportDispatcher) {
                            delimitedStatementImport
                                .preview(readResult.evidence, delimiter)
                                .also { producedPreview = it }
                        }
                    } catch (cancellation: CancellationException) {
                        (producedPreview as? StatementImportPreviewResult.Ready)
                            ?.session
                            ?.close()
                        throw cancellation
                    } catch (_: Exception) {
                        StatementImportPreviewResult.Failure(
                            StatementImportPreviewError.UNAVAILABLE,
                        )
                    } finally {
                        readResult.evidence.bytes.fill(0)
                    }
                    if (generation != statementDocumentGeneration) {
                        (previewResult as? StatementImportPreviewResult.Ready)
                            ?.session
                            ?.close()
                        return@launch
                    }
                    when (previewResult) {
                        is StatementImportPreviewResult.Ready -> {
                            statementSession = previewResult.session
                            val mappingState = previewResult.session.initialMappingState()
                            lastStatementMappingState = mappingState
                            mutableUiState.update { state ->
                                state.copy(statementImport = mappingState)
                            }
                            scheduleStatementMappingPreview(mappingState)
                        }

                        is StatementImportPreviewResult.InvalidDocument -> {
                            publishStatementImportFailure(previewResult.error.toUiError())
                        }

                        is StatementImportPreviewResult.Failure -> {
                            publishStatementImportFailure(previewResult.error.toUiError())
                        }
                    }
                }
            }
        }
    }

    fun updateStatementImportMapping(input: StatementImportMappingInput) {
        val current = mutableUiState.value.statementImport as? StatementImportUiState.Mapping
            ?: return
        val next = current.copy(
            input = input,
            preview = null,
            isPreviewing = true,
            configurationIssue = null,
            operationError = null,
        )
        lastStatementMappingState = next
        mutableUiState.update { state -> state.copy(statementImport = next) }
        scheduleStatementMappingPreview(next)
    }

    fun confirmStatementImport() {
        val current = mutableUiState.value.statementImport as? StatementImportUiState.Mapping
            ?: return
        val session = statementSession ?: run {
            publishStatementImportFailure(StatementImportUiError.CLOSED_SESSION)
            return
        }
        val mapping = when (
            val build = buildStatementMapping(
                delimiter = current.delimiter,
                input = current.input,
                columnCount = current.columns.size,
            )
        ) {
            is StatementMappingBuildResult.Invalid -> {
                val invalid = current.copy(
                    preview = null,
                    isPreviewing = false,
                    configurationIssue = build.issue,
                    operationError = null,
                )
                lastStatementMappingState = invalid
                mutableUiState.update { state -> state.copy(statementImport = invalid) }
                return
            }

            is StatementMappingBuildResult.Ready -> build.mapping
        }
        if (current.preview?.validRowCount == null || current.preview.validRowCount <= 0) return

        statementMappingJob?.cancel()
        val totalRows = current.totalDataRowCount
        lastStatementMappingState = current.copy(
            isPreviewing = false,
            operationError = null,
        )
        mutableUiState.update { state ->
            state.copy(statementImport = StatementImportUiState.Importing(0, totalRows))
        }
        statementImportJob = viewModelScope.launch {
            val result = try {
                withContext(statementImportDispatcher) {
                    delimitedStatementImport.confirm(
                        session = session,
                        mapping = mapping,
                        onProgress = { progress ->
                            mutableUiState.update { state ->
                                if (
                                    statementSession === session &&
                                    state.statementImport is StatementImportUiState.Importing
                                ) {
                                    state.copy(
                                        statementImport = StatementImportUiState.Importing(
                                            processedRowCount = progress.processedRowCount,
                                            totalRowCount = progress.totalRowCount,
                                        ),
                                    )
                                } else {
                                    state
                                }
                            }
                        },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                StatementImportConfirmationResult.Interrupted(
                    batchId = null,
                    tableRowIndex = null,
                    error = null,
                )
            }
            if (statementSession !== session) return@launch
            when (result) {
                is StatementImportConfirmationResult.Completed -> {
                    session.close()
                    statementSession = null
                    lastStatementMappingState = null
                    mutableUiState.update { state ->
                        state.copy(
                            statementImport = StatementImportUiState.Completed(
                                readyForReviewCount = result.batch.readyForReviewCount,
                                rejectedRowCount = result.batch.rejectedRowCount,
                                resumedRowCount = result.resumedRowCount,
                            ),
                        )
                    }
                    loadEvidencePage(reset = true)
                }

                is StatementImportConfirmationResult.Interrupted -> {
                    restoreStatementMappingWithError(
                        StatementImportUiError.IMPORT_INTERRUPTED,
                    )
                }

                is StatementImportConfirmationResult.InvalidMapping -> {
                    restoreStatementMappingWithIssue(
                        StatementImportConfigurationIssue.INVALID_MAPPING,
                    )
                }

                StatementImportConfirmationResult.ClosedSession -> {
                    publishStatementImportFailure(StatementImportUiError.CLOSED_SESSION)
                }

                StatementImportConfirmationResult.BatchIdentityCollision -> {
                    publishStatementImportFailure(
                        StatementImportUiError.BATCH_IDENTITY_COLLISION,
                    )
                }

                is StatementImportConfirmationResult.RowIdentityCollision -> {
                    publishStatementImportFailure(
                        StatementImportUiError.ROW_IDENTITY_COLLISION,
                    )
                }
            }
        }
    }

    fun cancelStatementImport() {
        clearStatementImportResources()
        mutableUiState.update { state ->
            state.copy(statementImport = StatementImportUiState.Idle)
        }
    }

    fun ingestSelectedPhotos(documentUris: List<String>) {
        val boundedUris = documentUris.take(MAX_SELECTED_OCR_IMAGES)
        if (boundedUris.isEmpty() || photoOcrJob?.isActive == true) return
        mutableUiState.update { state ->
            state.copy(
                photoOcrBatch = PhotoOcrBatchUiState(
                    processedCount = 0,
                    totalCount = boundedUris.size,
                ),
            )
        }
        photoOcrJob = viewModelScope.launch {
            var firstProposalId: String? = null
            var readyForReviewCount = 0
            var failedCount = 0
            var firstFailure: SourceCaptureError? = null
            try {
                boundedUris.forEachIndexed { index, documentUri ->
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
                    when (result) {
                        is SourceCaptureResult.ReadyForReview -> {
                            readyForReviewCount += 1
                            if (firstProposalId == null) {
                                firstProposalId = result.proposalId
                            }
                        }

                        is SourceCaptureResult.Failure -> {
                            failedCount += 1
                            if (firstFailure == null) {
                                firstFailure = result.error
                            }
                        }
                    }
                    mutableUiState.update { state ->
                        state.copy(
                            photoOcrBatch = PhotoOcrBatchUiState(
                                processedCount = index + 1,
                                totalCount = boundedUris.size,
                            ),
                        )
                    }
                }
                if (readyForReviewCount > 0) {
                    loadEvidencePage(reset = true)
                }
                eventChannel.send(
                    BillUiEvent.PhotoOcrBatchCompleted(
                        firstProposalId = firstProposalId,
                        readyForReviewCount = readyForReviewCount,
                        failedCount = failedCount,
                        firstFailure = firstFailure,
                    ),
                )
            } finally {
                mutableUiState.update { state ->
                    state.copy(photoOcrBatch = null)
                }
                photoOcrJob = null
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

    fun updateDraft(
        commandId: String,
        draftId: String,
        kind: DraftSummaryKind,
        amount: String,
        counterparty: String,
        note: String,
        occurredAt: Instant,
        observedChannel: ObservedChannel,
        fundingAccountId: String?,
        investmentAccountId: String?,
    ) {
        perform(BillOperationKind.UPDATE_DRAFT, draftId) {
            service.updateDraft(
                UpdateDraftCommand(
                    commandId = CommandId(commandId),
                    draftId = DraftId(draftId),
                    type = when (kind) {
                        DraftSummaryKind.EXPENSE -> TransactionType.EXPENSE
                        DraftSummaryKind.INCOME -> TransactionType.INCOME
                        DraftSummaryKind.INVEST_BUY -> TransactionType.INVEST_BUY
                    },
                    amountText = amount,
                    counterparty = counterparty,
                    note = note,
                    occurredAt = occurredAt,
                    observedChannel = observedChannel,
                    fundingAccountId = fundingAccountId?.let(::AccountId),
                    investmentAccountId = investmentAccountId?.let(::AccountId),
                ),
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

    fun resolveReconciliation(commandId: String, caseId: String) {
        perform(BillOperationKind.RESOLVE_RECONCILIATION, caseId) {
            service.resolveReconciliation(
                ResolveReconciliationCommand(
                    commandId = CommandId(commandId),
                    caseId = caseId,
                ),
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

    private fun scheduleStatementMappingPreview(
        mappingState: StatementImportUiState.Mapping,
    ) {
        statementMappingJob?.cancel()
        statementMappingGeneration += 1L
        val generation = statementMappingGeneration
        val session = statementSession
        if (session == null) {
            publishStatementImportFailure(StatementImportUiError.CLOSED_SESSION)
            return
        }
        val mapping = when (
            val build = buildStatementMapping(
                delimiter = mappingState.delimiter,
                input = mappingState.input,
                columnCount = mappingState.columns.size,
            )
        ) {
            is StatementMappingBuildResult.Invalid -> {
                val invalid = mappingState.copy(
                    preview = null,
                    isPreviewing = false,
                    configurationIssue = build.issue,
                    operationError = null,
                )
                lastStatementMappingState = invalid
                mutableUiState.update { state -> state.copy(statementImport = invalid) }
                return
            }

            is StatementMappingBuildResult.Ready -> build.mapping
        }
        val previewing = mappingState.copy(
            preview = null,
            isPreviewing = true,
            configurationIssue = null,
            operationError = null,
        )
        lastStatementMappingState = previewing
        mutableUiState.update { state -> state.copy(statementImport = previewing) }
        statementMappingJob = viewModelScope.launch {
            delay(STATEMENT_MAPPING_PREVIEW_DEBOUNCE_MILLIS)
            val result = try {
                withContext(statementImportDispatcher) {
                    delimitedStatementImport.previewMapping(session, mapping)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                StatementImportMappingPreviewResult.InvalidMapping(
                    dev.bill.source.genericdelimited.StatementMappingError.COLUMN_OUT_OF_RANGE,
                )
            }
            if (
                generation != statementMappingGeneration ||
                statementSession !== session
            ) {
                return@launch
            }
            when (result) {
                is StatementImportMappingPreviewResult.Ready -> {
                    val ready = previewing.copy(
                        preview = StatementImportMappingPreviewUi(
                            validRowCount = result.preview.validRowCount,
                            invalidRowCount = result.preview.invalidRowCount,
                            rowOutcomes = result.preview.previewRows.map { row ->
                                StatementImportRowOutcomeUi(
                                    tableRowIndex = row.tableRowIndex,
                                    error = row.error,
                                )
                            },
                        ),
                        isPreviewing = false,
                        configurationIssue = null,
                    )
                    lastStatementMappingState = ready
                    mutableUiState.update { state -> state.copy(statementImport = ready) }
                }

                is StatementImportMappingPreviewResult.InvalidMapping -> {
                    restoreStatementMappingWithIssue(
                        StatementImportConfigurationIssue.INVALID_MAPPING,
                    )
                }

                StatementImportMappingPreviewResult.ClosedSession -> {
                    publishStatementImportFailure(StatementImportUiError.CLOSED_SESSION)
                }
            }
        }
    }

    private fun restoreStatementMappingWithError(error: StatementImportUiError) {
        val restored = lastStatementMappingState?.copy(
            isPreviewing = false,
            operationError = error,
        ) ?: run {
            publishStatementImportFailure(error)
            return
        }
        lastStatementMappingState = restored
        mutableUiState.update { state -> state.copy(statementImport = restored) }
    }

    private fun restoreStatementMappingWithIssue(
        issue: StatementImportConfigurationIssue,
    ) {
        val restored = lastStatementMappingState?.copy(
            preview = null,
            isPreviewing = false,
            configurationIssue = issue,
            operationError = null,
        ) ?: run {
            publishStatementImportFailure(StatementImportUiError.CLOSED_SESSION)
            return
        }
        lastStatementMappingState = restored
        mutableUiState.update { state -> state.copy(statementImport = restored) }
    }

    private fun publishStatementImportFailure(error: StatementImportUiError) {
        clearStatementImportResources()
        mutableUiState.update { state ->
            state.copy(statementImport = StatementImportUiState.Failed(error))
        }
    }

    private fun clearStatementImportResources() {
        statementDocumentJob?.cancel()
        statementMappingJob?.cancel()
        statementImportJob?.cancel()
        statementDocumentJob = null
        statementMappingJob = null
        statementImportJob = null
        statementDocumentGeneration += 1L
        statementMappingGeneration += 1L
        statementSession?.close()
        statementSession = null
        lastStatementMappingState = null
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

    override fun onCleared() {
        clearStatementImportResources()
        super.onCleared()
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
        private val selectedImageOcrReader: SelectedImageOcrReader =
            SelectedImageOcrReader.Unavailable,
        private val delimitedStatementImport: DelimitedStatementImport =
            DelimitedStatementImport.Unavailable,
        private val delimitedStatementDocumentReader: SelectedDelimitedStatementDocumentReader =
            SelectedDelimitedStatementDocumentReader.Unavailable,
        private val statementImportDispatcher: CoroutineDispatcher = Dispatchers.Default,
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
                selectedImageOcrReader = selectedImageOcrReader,
                delimitedStatementImport = delimitedStatementImport,
                delimitedStatementDocumentReader = delimitedStatementDocumentReader,
                statementImportDispatcher = statementImportDispatcher,
            ) as T
        }
    }

    private companion object {
        const val MAX_SELECTED_OCR_IMAGES = 5
        const val STATEMENT_MAPPING_PREVIEW_DEBOUNCE_MILLIS = 250L
    }
}

private fun SourceCaptureError.toInvestmentOcrUiError(): InvestmentOcrPrefillUiError = when (this) {
    SourceCaptureError.EMPTY_CONTENT -> InvestmentOcrPrefillUiError.EMPTY_IMAGE
    SourceCaptureError.CONTENT_TOO_LARGE -> InvestmentOcrPrefillUiError.IMAGE_TOO_LARGE
    else -> InvestmentOcrPrefillUiError.UNREADABLE_IMAGE
}

private fun InvestmentPositionOcrPrefillError.toUiError(): InvestmentOcrPrefillUiError =
    when (this) {
        InvestmentPositionOcrPrefillError.NO_RECOGNIZED_FIELDS ->
            InvestmentOcrPrefillUiError.NO_RECOGNIZED_FIELDS

        InvestmentPositionOcrPrefillError.AMBIGUOUS_FIELDS ->
            InvestmentOcrPrefillUiError.AMBIGUOUS_FIELDS
    }

private sealed interface StatementMappingBuildResult {
    data class Ready(
        val mapping: DelimitedStatementMapping,
    ) : StatementMappingBuildResult

    data class Invalid(
        val issue: StatementImportConfigurationIssue,
    ) : StatementMappingBuildResult
}

private fun buildStatementMapping(
    delimiter: DelimitedDelimiter,
    input: StatementImportMappingInput,
    columnCount: Int,
): StatementMappingBuildResult {
    val requiredColumns = listOf(
        input.dateColumnIndex,
        input.amountColumnIndex,
        input.counterpartyColumnIndex,
    )
    if (requiredColumns.any { it == null }) {
        return StatementMappingBuildResult.Invalid(
            StatementImportConfigurationIssue.INCOMPLETE_FIELDS,
        )
    }
    val mappedColumns = buildList {
        requiredColumns.filterNotNull().forEach(::add)
        input.referenceColumnIndex?.let(::add)
        if (input.directionMode == StatementImportDirectionMode.DIRECTION_COLUMN) {
            val directionColumn = input.directionColumnIndex
                ?: return StatementMappingBuildResult.Invalid(
                    StatementImportConfigurationIssue.INCOMPLETE_FIELDS,
                )
            add(directionColumn)
        }
    }
    if (mappedColumns.any { it !in 0 until columnCount }) {
        return StatementMappingBuildResult.Invalid(
            StatementImportConfigurationIssue.INVALID_MAPPING,
        )
    }
    if (mappedColumns.distinct().size != mappedColumns.size) {
        return StatementMappingBuildResult.Invalid(
            StatementImportConfigurationIssue.DUPLICATE_COLUMNS,
        )
    }

    val directionMapping = when (input.directionMode) {
        StatementImportDirectionMode.SIGNED_AMOUNT -> StatementDirectionMapping.SignedAmount(
            positiveDirection = input.positiveDirection,
        )

        StatementImportDirectionMode.DIRECTION_COLUMN -> {
            val inbound = parseDirectionTokens(input.inboundTokens)
            val outbound = parseDirectionTokens(input.outboundTokens)
            if (inbound.isEmpty() || outbound.isEmpty()) {
                return StatementMappingBuildResult.Invalid(
                    StatementImportConfigurationIssue.EMPTY_DIRECTION_TOKENS,
                )
            }
            if (inbound.size > MAX_DIRECTION_TOKEN_COUNT || outbound.size > MAX_DIRECTION_TOKEN_COUNT) {
                return StatementMappingBuildResult.Invalid(
                    StatementImportConfigurationIssue.TOO_MANY_DIRECTION_TOKENS,
                )
            }
            if ((inbound + outbound).any { it.length > MAX_DIRECTION_TOKEN_LENGTH || '\u0000' in it }) {
                return StatementMappingBuildResult.Invalid(
                    StatementImportConfigurationIssue.INVALID_DIRECTION_TOKEN,
                )
            }
            val normalizedInbound = inbound.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
            val normalizedOutbound = outbound.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
            if (
                normalizedInbound.size != inbound.size ||
                normalizedOutbound.size != outbound.size ||
                normalizedInbound.intersect(normalizedOutbound).isNotEmpty()
            ) {
                return StatementMappingBuildResult.Invalid(
                    StatementImportConfigurationIssue.OVERLAPPING_DIRECTION_TOKENS,
                )
            }
            StatementDirectionMapping.DirectionColumn(
                columnIndex = checkNotNull(input.directionColumnIndex),
                inboundTokens = inbound,
                outboundTokens = outbound,
                caseSensitive = false,
            )
        }
    }

    val mapping = try {
        DelimitedStatementMapping(
            delimiter = delimiter,
            dateColumnIndex = checkNotNull(input.dateColumnIndex),
            dateFormat = input.dateFormat,
            amountColumnIndex = checkNotNull(input.amountColumnIndex),
            amountFormat = input.amountFormat,
            directionMapping = directionMapping,
            counterpartyColumnIndex = checkNotNull(input.counterpartyColumnIndex),
            referenceColumnIndex = input.referenceColumnIndex,
            currency = input.currency,
        )
    } catch (_: IllegalArgumentException) {
        return StatementMappingBuildResult.Invalid(
            StatementImportConfigurationIssue.INVALID_MAPPING,
        )
    }
    return StatementMappingBuildResult.Ready(mapping)
}

private fun parseDirectionTokens(value: String): Set<String> =
    value.split(directionTokenSeparator)
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toCollection(linkedSetOf())

private fun LocalDelimitedStatementSession.initialMappingState(): StatementImportUiState.Mapping {
    val input = StatementImportMappingInput(
        dateColumnIndex = null,
        dateFormat = StatementDateFormat.DATE_DASH,
        amountColumnIndex = null,
        amountFormat = StatementAmountFormat.DOT_DECIMAL,
        directionMode = StatementImportDirectionMode.SIGNED_AMOUNT,
        positiveDirection = StatementDirection.INBOUND,
        directionColumnIndex = null,
        inboundTokens = DEFAULT_INBOUND_TOKENS,
        outboundTokens = DEFAULT_OUTBOUND_TOKENS,
        counterpartyColumnIndex = null,
        referenceColumnIndex = null,
        currency = CurrencyCode.CNY,
    )
    return StatementImportUiState.Mapping(
        delimiter = delimiter,
        columns = header.mapIndexed { index, label ->
            StatementImportColumnUi(
                index = index,
                label = label.safePreviewText(fallback = "#${index + 1}"),
            )
        },
        sampleRows = previewRows.take(MAX_UI_SAMPLE_ROWS).map { row ->
            StatementImportSampleRowUi(
                tableRowIndex = row.tableRowIndex,
                cells = row.cells.map { cell -> cell.safePreviewText() },
            )
        },
        totalDataRowCount = totalDataRowCount,
        input = input,
        preview = null,
        isPreviewing = false,
        configurationIssue = StatementImportConfigurationIssue.INCOMPLETE_FIELDS,
    )
}

private fun String.safePreviewText(fallback: String = "—"): String {
    val normalized = trim().replace(previewWhitespace, " ")
    if (normalized.isEmpty()) return fallback
    return normalized.take(MAX_UI_PREVIEW_CHARS)
}

private fun SelectedDelimitedStatementReadError.toUiError(): StatementImportUiError = when (this) {
    SelectedDelimitedStatementReadError.INVALID_DOCUMENT ->
        StatementImportUiError.INVALID_DOCUMENT

    SelectedDelimitedStatementReadError.UNSUPPORTED_MEDIA_TYPE ->
        StatementImportUiError.UNSUPPORTED_MEDIA_TYPE

    SelectedDelimitedStatementReadError.CONTENT_TOO_LARGE ->
        StatementImportUiError.CONTENT_TOO_LARGE

    SelectedDelimitedStatementReadError.READ_FAILED ->
        StatementImportUiError.READ_FAILED
}

private fun StatementImportPreviewError.toUiError(): StatementImportUiError = when (this) {
    StatementImportPreviewError.UNAVAILABLE -> StatementImportUiError.READER_UNAVAILABLE
    StatementImportPreviewError.UNSUPPORTED_MEDIA_TYPE ->
        StatementImportUiError.UNSUPPORTED_MEDIA_TYPE

    StatementImportPreviewError.DELIMITER_MISMATCH ->
        StatementImportUiError.DELIMITER_MISMATCH

    StatementImportPreviewError.NO_DATA_ROWS -> StatementImportUiError.NO_DATA_ROWS
}

private fun DelimitedReadError.toUiError(): StatementImportUiError = when (this) {
    DelimitedReadError.EMPTY_DOCUMENT -> StatementImportUiError.INVALID_DOCUMENT
    DelimitedReadError.CONTENT_TOO_LARGE -> StatementImportUiError.CONTENT_TOO_LARGE
    DelimitedReadError.MALFORMED_UTF8 -> StatementImportUiError.MALFORMED_UTF8
    DelimitedReadError.NUL_CHARACTER,
    DelimitedReadError.UNEXPECTED_QUOTE,
    DelimitedReadError.CHARACTERS_AFTER_CLOSING_QUOTE,
    DelimitedReadError.UNCLOSED_QUOTED_FIELD,
    -> StatementImportUiError.MALFORMED_DOCUMENT

    DelimitedReadError.TOO_MANY_RECORDS -> StatementImportUiError.TOO_MANY_RECORDS
    DelimitedReadError.TOO_MANY_COLUMNS -> StatementImportUiError.TOO_MANY_COLUMNS
    DelimitedReadError.CELL_TOO_LONG -> StatementImportUiError.CELL_TOO_LONG
    DelimitedReadError.RECORD_TOO_LONG -> StatementImportUiError.RECORD_TOO_LONG
}

private val directionTokenSeparator = Regex("[,，;；、\\r\\n]+")
private val previewWhitespace = Regex("[\\r\\n\\t ]+")

private const val DEFAULT_INBOUND_TOKENS = "收入，收款，入账，IN，INCOME，CREDIT"
private const val DEFAULT_OUTBOUND_TOKENS = "支出，付款，出账，OUT，EXPENSE，DEBIT"
private const val MAX_DIRECTION_TOKEN_COUNT = 16
private const val MAX_DIRECTION_TOKEN_LENGTH = 64
private const val MAX_UI_SAMPLE_ROWS = 3
private const val MAX_UI_PREVIEW_CHARS = 160
