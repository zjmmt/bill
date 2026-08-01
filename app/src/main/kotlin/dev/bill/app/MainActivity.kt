package dev.bill.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bill.application.SourceCaptureError
import dev.bill.application.SourceEvidenceOperationError
import dev.bill.application.InvestmentPositionOcrPrefill
import dev.bill.app.notification.NotificationCaptureHealth
import dev.bill.app.notification.NotificationCaptureHealthSnapshot
import dev.bill.app.notification.NotificationCaptureHealthState
import dev.bill.app.notification.NotificationRouteSettings
import dev.bill.app.notification.NotificationRouteSettingsSnapshot
import dev.bill.app.notification.openNotificationListenerSettings
import dev.bill.app.quickcapture.BillQuickCaptureRuntime
import dev.bill.app.quickcapture.BillQuickCaptureTileService
import dev.bill.app.quickcapture.ContentResolverSelectedPhotoOcrImporter
import dev.bill.app.quickcapture.ContentResolverSelectedImageOcrReader
import dev.bill.app.quickcapture.QuickCaptureConnectionState
import dev.bill.app.quickcapture.openQuickCaptureAccessibilitySettings
import dev.bill.app.quickcapture.requestQuickCaptureTile
import dev.bill.core.designsystem.component.PosterPanel
import dev.bill.core.designsystem.theme.BillTheme
import dev.bill.feature.accounts.AccountsScreen
import dev.bill.feature.accounts.CreateInvestmentPositionInput
import dev.bill.feature.ledger.LedgerScreen
import dev.bill.feature.overview.OverviewAction
import dev.bill.feature.overview.OverviewPresenter
import dev.bill.feature.overview.OverviewScreen
import dev.bill.feature.overview.OverviewUiState
import dev.bill.feature.review.DraftsScreen
import dev.bill.feature.review.EditDraftSheet
import dev.bill.feature.review.ManualDraftSheet
import dev.bill.feature.review.ReconciliationBottomSheet
import dev.bill.feature.review.ReviewAction
import dev.bill.feature.review.ReviewBottomSheet
import dev.bill.feature.review.ReviewPresenter
import dev.bill.feature.review.SourceDraftSheet
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.SourceFamily
import dev.bill.source.contract.TextEvidenceMediaTypes
import dev.bill.source.genericdelimited.DelimitedDelimiter
import dev.bill.source.review.EvidencePayloadState
import dev.bill.source.review.SourceEvidenceItem
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val MAX_SELECTED_OCR_IMAGES = 5

class MainActivity : ComponentActivity() {
    private var activeShareCommandId: String? = null
    private var requestedDestination by mutableStateOf<AppDestination?>(null)

    private val billViewModel: BillViewModel by viewModels {
        val application = application as BillApplication
        val selectedImageOcrReader = ContentResolverSelectedImageOcrReader(
            applicationContext = applicationContext,
            contentResolver = contentResolver,
        )
        BillViewModel.Factory(
            service = application.container.billService,
            sharedTextIngestionService = application.container.sharedTextIngestionService,
            sourceEvidenceManager = application.container.sourceEvidenceLifecycleService,
            selectedTextFileIngestionService = application.container.selectedTextFileIngestionService,
            selectedTextDocumentReader = ContentResolverSelectedTextDocumentReader(contentResolver),
            delimitedStatementImport = application.container.localDelimitedStatementImportService,
            delimitedStatementDocumentReader =
                ContentResolverSelectedDelimitedStatementDocumentReader(contentResolver),
            sharedReceiptImageIngestionService = application.container
                .sharedReceiptImageIngestionService,
            sharedReceiptImageDocumentReader = ContentResolverSharedReceiptImageDocumentReader(
                contentResolver,
            ),
            selectedPhotoOcrImporter = ContentResolverSelectedPhotoOcrImporter(
                reader = selectedImageOcrReader,
                capture = application.container.photoOcrTranscriptIngestionService,
            ),
            selectedImageOcrReader = selectedImageOcrReader,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            BillTheme {
                BillApp(
                    viewModel = billViewModel,
                    onSourceCaptureHandled = ::clearIncomingShareIntent,
                    notificationCaptureHealth = (application as BillApplication)
                        .container
                        .notificationCaptureHealth,
                    notificationRouteSettings = (application as BillApplication)
                        .container
                        .notificationRouteSettings,
                    requestedDestination = requestedDestination,
                    onRequestedDestinationHandled = { handled ->
                        if (requestedDestination == handled) {
                            requestedDestination = null
                        }
                    },
                )
            }
        }
        handleNavigationIntent(intent)
        handleIncomingShareIntent(
            incomingIntent = intent,
            restoredCommandId = savedInstanceState?.getString(STATE_SHARE_COMMAND_ID),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
        handleIncomingShareIntent(intent, restoredCommandId = null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        activeShareCommandId?.let { commandId ->
            outState.putString(STATE_SHARE_COMMAND_ID, commandId)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        (application as BillApplication).container.refreshNotificationCaptureConfiguration()
        billViewModel.revealForForeground()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        billViewModel.coverForPrivacy()
        super.onPause()
    }

    private fun handleIncomingShareIntent(
        incomingIntent: Intent?,
        restoredCommandId: String?,
    ) {
        if (incomingIntent?.action != Intent.ACTION_SEND) {
            return
        }
        val commandId = restoredCommandId ?: billViewModel.newCommandId()
        activeShareCommandId = commandId
        when {
            incomingIntent.type == TEXT_PLAIN -> {
                val sharedText = try {
                    incomingIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                } catch (_: RuntimeException) {
                    null
                }
                billViewModel.ingestSharedText(
                    commandId = commandId,
                    text = sharedText,
                )
            }

            incomingIntent.type?.startsWith(IMAGE_PREFIX) == true -> {
                billViewModel.ingestSharedReceiptImage(
                    commandId = commandId,
                    uriString = incomingIntent.sharedImageUriOrNull()?.toString(),
                    declaredMediaType = incomingIntent.type,
                )
            }

            else -> activeShareCommandId = null
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.sharedImageUriOrNull(): Uri? = try {
        getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    } catch (_: RuntimeException) {
        null
    }

    private fun handleNavigationIntent(incomingIntent: Intent?) {
        val destination = destinationForIntentAction(incomingIntent?.action) ?: return
        requestedDestination = destination
        incomingIntent?.action = null
    }

    private fun clearIncomingShareIntent(commandId: String): Boolean {
        if (activeShareCommandId != commandId) return false
        activeShareCommandId = null
        setIntent(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
            },
        )
        return true
    }

    private companion object {
        const val TEXT_PLAIN = "text/plain"
        const val IMAGE_PREFIX = "image/"
        const val STATE_SHARE_COMMAND_ID = "active_share_command_id"
    }
}

internal enum class AppDestination(
    val labelRes: Int,
    val icon: ImageVector,
) {
    OVERVIEW(R.string.nav_overview, Icons.Outlined.Home),
    DRAFTS(R.string.nav_drafts, Icons.Outlined.Description),
    LEDGER(R.string.nav_ledger, Icons.AutoMirrored.Outlined.ReceiptLong),
    ACCOUNTS(R.string.nav_accounts, Icons.Outlined.AccountBalanceWallet),
    SETTINGS(R.string.settings, Icons.Outlined.Settings),
}

internal fun destinationForIntentAction(action: String?): AppDestination? = when (action) {
    BillQuickCaptureTileService.ACTION_OPEN_QUICK_CAPTURE_SETUP -> AppDestination.SETTINGS
    else -> null
}

private val primaryDestinations = listOf(
    AppDestination.OVERVIEW,
    AppDestination.DRAFTS,
    AppDestination.LEDGER,
    AppDestination.ACCOUNTS,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillApp(
    viewModel: BillViewModel,
    onSourceCaptureHandled: (String) -> Boolean,
    notificationCaptureHealth: NotificationCaptureHealth,
    notificationRouteSettings: NotificationRouteSettings,
    requestedDestination: AppDestination?,
    onRequestedDestinationHandled: (AppDestination) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notificationHealth by notificationCaptureHealth.state.collectAsStateWithLifecycle()
    val routeSettings by notificationRouteSettings.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val application = context.applicationContext as? BillApplication
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    var destination by rememberSaveable { mutableStateOf(AppDestination.OVERVIEW) }
    var showCreateAccount by rememberSaveable { mutableStateOf(false) }
    var showCreateInvestment by rememberSaveable { mutableStateOf(false) }
    var showManualDraft by rememberSaveable { mutableStateOf(false) }
    var createAccountCommandId by rememberSaveable { mutableStateOf<String?>(null) }
    var createInvestmentCommandId by rememberSaveable { mutableStateOf<String?>(null) }
    var investmentFormSeed by remember { mutableStateOf(CreateInvestmentPositionInput()) }
    var manualDraftCommandId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var editDraftCommandId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSourceReviewId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedReconciliationCaseId by rememberSaveable { mutableStateOf<String?>(null) }
    var reconciliationCommandId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceDraftCommandId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTextFilePickerCommandId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeSelectedTextFileCommandId by rememberSaveable { mutableStateOf<String?>(null) }
    var isSelectedTextFilePickerOpen by remember { mutableStateOf(false) }
    var showImportMethod by rememberSaveable { mutableStateOf(false) }
    var pendingStatementDelimiter by rememberSaveable { mutableStateOf<String?>(null) }
    var isStatementPickerOpen by remember { mutableStateOf(false) }
    var showLocalOnlyDeclaration by remember(application) {
        mutableStateOf(application?.localOnlyDeclarationState?.shouldShow() != false)
    }
    val successMessage = stringResource(R.string.operation_succeeded)
    val failureMessage = stringResource(R.string.operation_failed)
    val sourceReadyMessage = stringResource(R.string.source_review_ready)

    fun openCreateAccount() {
        createAccountCommandId = createAccountCommandId ?: viewModel.newCommandId()
        showCreateAccount = true
    }

    fun openCreateInvestment(
        seed: CreateInvestmentPositionInput = CreateInvestmentPositionInput(),
    ) {
        createInvestmentCommandId = createInvestmentCommandId ?: viewModel.newCommandId()
        investmentFormSeed = seed
        showCreateInvestment = true
    }

    fun openManualDraft() {
        manualDraftCommandId = manualDraftCommandId ?: viewModel.newCommandId()
        showManualDraft = true
    }

    val selectedTextFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        isSelectedTextFilePickerOpen = false
        val commandId = selectedTextFilePickerCommandId
        selectedTextFilePickerCommandId = null
        if (uri != null && commandId != null) {
            activeSelectedTextFileCommandId = commandId
            viewModel.ingestSelectedTextFile(commandId, uri.toString())
        }
    }
    val selectedStatementLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        isStatementPickerOpen = false
        val delimiter = pendingStatementDelimiter
            ?.let { name -> runCatching { DelimitedDelimiter.valueOf(name) }.getOrNull() }
        pendingStatementDelimiter = null
        if (uri != null && delimiter != null) {
            viewModel.ingestSelectedDelimitedStatement(
                documentUri = uri.toString(),
                delimiter = delimiter,
            )
        }
    }
    val selectedPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_SELECTED_OCR_IMAGES),
    ) { uris ->
        viewModel.ingestSelectedPhotos(uris.map(Uri::toString))
    }
    val investmentOcrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.prefillInvestmentFromScreenshot(uri.toString())
        }
    }

    fun openSelectedTextFile() {
        if (isSelectedTextFilePickerOpen || activeSelectedTextFileCommandId != null) return
        isSelectedTextFilePickerOpen = true
        selectedTextFilePickerCommandId = viewModel.newCommandId()
        selectedTextFileLauncher.launch(TextEvidenceMediaTypes.USER_SELECTED_TEXT_FILE.toTypedArray())
    }

    fun openStructuredStatement(delimiter: DelimitedDelimiter) {
        if (isStatementPickerOpen) return
        isStatementPickerOpen = true
        pendingStatementDelimiter = delimiter.name
        val mediaTypes = when (delimiter) {
            DelimitedDelimiter.COMMA -> arrayOf(
                TextEvidenceMediaTypes.TEXT_CSV,
                TextEvidenceMediaTypes.APPLICATION_CSV,
                TextEvidenceMediaTypes.TEXT_PLAIN,
            )

            DelimitedDelimiter.TAB -> arrayOf(
                TextEvidenceMediaTypes.TEXT_TAB_SEPARATED,
                TextEvidenceMediaTypes.TEXT_PLAIN,
            )
        }
        selectedStatementLauncher.launch(mediaTypes)
    }

    fun consumeSourceCapture(commandId: String): Boolean {
        if (onSourceCaptureHandled(commandId)) return true
        if (activeSelectedTextFileCommandId != commandId) return false
        activeSelectedTextFileCommandId = null
        return true
    }

    LaunchedEffect(requestedDestination) {
        val requested = requestedDestination ?: return@LaunchedEffect
        destination = requested
        onRequestedDestinationHandled(requested)
    }

    LaunchedEffect(showCreateAccount) {
        if (showCreateAccount && createAccountCommandId == null) {
            createAccountCommandId = viewModel.newCommandId()
        }
    }
    LaunchedEffect(showCreateInvestment) {
        if (showCreateInvestment && createInvestmentCommandId == null) {
            createInvestmentCommandId = viewModel.newCommandId()
        }
    }
    LaunchedEffect(showManualDraft) {
        if (showManualDraft && manualDraftCommandId == null) {
            manualDraftCommandId = viewModel.newCommandId()
        }
    }

    BackHandler(enabled = destination == AppDestination.SETTINGS) {
        destination = AppDestination.OVERVIEW
    }

    HandleUiEvents(
        events = viewModel.events,
        onEvent = { event ->
            when (event) {
                is BillUiEvent.OperationSucceeded -> {
                    when (event.kind) {
                        BillOperationKind.CREATE_ACCOUNT -> {
                            showCreateAccount = false
                            createAccountCommandId = null
                        }
                        BillOperationKind.CREATE_INVESTMENT_POSITION -> {
                            showCreateInvestment = false
                            createInvestmentCommandId = null
                            investmentFormSeed = CreateInvestmentPositionInput()
                        }
                        BillOperationKind.CREATE_DRAFT -> {
                            showManualDraft = false
                            manualDraftCommandId = null
                            destination = AppDestination.DRAFTS
                        }
                        BillOperationKind.CREATE_EXTERNAL_DRAFT -> {
                            selectedSourceReviewId = null
                            sourceDraftCommandId = null
                            selectedDraftId = event.entityId
                            destination = AppDestination.DRAFTS
                        }
                        BillOperationKind.UPDATE_DRAFT -> {
                            editingDraftId = null
                            editDraftCommandId = null
                            selectedDraftId = event.entityId
                            destination = AppDestination.DRAFTS
                        }
                        BillOperationKind.DISMISS_SOURCE_REVIEW -> {
                            selectedSourceReviewId = null
                            sourceDraftCommandId = null
                            destination = AppDestination.DRAFTS
                        }
                        BillOperationKind.SELECT_FUNDING_ACCOUNT -> Unit
                        BillOperationKind.CONFIRM_DRAFT -> {
                            selectedDraftId = null
                            destination = AppDestination.LEDGER
                        }
                        BillOperationKind.RESOLVE_RECONCILIATION -> {
                            selectedReconciliationCaseId = null
                            reconciliationCommandId = null
                            destination = AppDestination.LEDGER
                        }
                        BillOperationKind.DISMISS_DRAFT -> selectedDraftId = null
                        BillOperationKind.VOID_TRANSACTION -> destination = AppDestination.DRAFTS
                    }
                    snackbarHostState.showSnackbar(successMessage)
                }

                is BillUiEvent.OperationFailed -> {
                    snackbarHostState.showSnackbar(failureMessage)
                }

                is BillUiEvent.SourceReviewReady -> {
                    if (consumeSourceCapture(event.commandId)) {
                        selectedReconciliationCaseId = null
                        reconciliationCommandId = null
                        selectedSourceReviewId = event.proposalId
                        sourceDraftCommandId = viewModel.newCommandId()
                        destination = AppDestination.DRAFTS
                        snackbarHostState.showSnackbar(sourceReadyMessage)
                    }
                }

                is BillUiEvent.SourceCaptureFailed -> {
                    if (consumeSourceCapture(event.commandId)) {
                        snackbarHostState.showSnackbar(
                            resources.getString(event.error.messageRes()),
                        )
                    }
                }

                is BillUiEvent.PhotoOcrBatchCompleted -> {
                    if (event.firstProposalId != null) {
                        selectedDraftId = null
                        selectedReconciliationCaseId = null
                        reconciliationCommandId = null
                        selectedSourceReviewId = event.firstProposalId
                        sourceDraftCommandId = viewModel.newCommandId()
                        destination = AppDestination.DRAFTS
                    }
                    val message = if (
                        event.readyForReviewCount == 0 &&
                        event.firstFailure != null
                    ) {
                        resources.getString(event.firstFailure.messageRes())
                    } else {
                        resources.getString(
                            R.string.photo_ocr_batch_completed,
                            event.readyForReviewCount,
                            event.failedCount,
                        )
                    }
                    snackbarHostState.showSnackbar(message)
                }

                is BillUiEvent.InvestmentOcrPrefillReady -> {
                    investmentFormSeed = investmentFormSeed.merge(event.prefill)
                    showCreateInvestment = true
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.investment_ocr_prefill_ready),
                    )
                }

                is BillUiEvent.InvestmentOcrPrefillFailed -> {
                    snackbarHostState.showSnackbar(
                        resources.getString(event.error.messageRes()),
                    )
                }

                is BillUiEvent.EvidenceOperationSucceeded -> {
                    snackbarHostState.showSnackbar(
                        resources.getString(
                            when (event.kind) {
                                EvidenceOperationKind.UPDATE_RETENTION ->
                                    R.string.evidence_operation_saved

                                EvidenceOperationKind.CLEAR_PAYLOAD ->
                                    R.string.evidence_operation_cleared
                            },
                        ),
                    )
                }

                is BillUiEvent.EvidenceOperationFailed -> {
                    snackbarHostState.showSnackbar(
                        resources.getString(event.error.messageRes()),
                    )
                }
            }
        },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(9.dp)
                                .height(30.dp)
                                .graphicsLayer { rotationZ = -18f }
                                .background(MaterialTheme.colorScheme.secondary),
                        )
                        Text(
                            text = stringResource(R.string.app_name).uppercase(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                },
                actions = {
                    Surface(
                        modifier = Modifier.padding(end = 8.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        IconButton(onClick = { destination = AppDestination.SETTINGS }) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.settings),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    primaryDestinations.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(28.dp)
                                            .height(3.dp)
                                            .background(
                                                if (destination == item) {
                                                    MaterialTheme.colorScheme.secondary
                                                } else {
                                                    MaterialTheme.colorScheme.surface
                                                },
                                            ),
                                    )
                                    Icon(imageVector = item.icon, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(item.labelRes)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.surface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val snapshot = state.snapshot
        if (state.isLoading && snapshot == null) {
            LoadingScreen(innerPadding)
        } else if (state.hasFatalError && snapshot == null) {
            OverviewScreen(
                state = OverviewUiState.Error,
                onAction = { action -> if (action == OverviewAction.Retry) viewModel.retry() },
                modifier = Modifier.padding(innerPadding),
            )
        } else if (snapshot != null) {
            when (destination) {
                AppDestination.OVERVIEW -> OverviewScreen(
                    state = OverviewPresenter.present(snapshot.overview, state.amountsMasked),
                    onAction = { action ->
                        when (action) {
                            OverviewAction.ToggleAmounts -> viewModel.toggleAmounts()
                            OverviewAction.ReviewDrafts -> {
                                destination = AppDestination.DRAFTS
                                selectedDraftId = snapshot.pendingDrafts.firstOrNull()?.id
                                if (selectedDraftId == null) {
                                    selectedSourceReviewId =
                                        snapshot.pendingSourceReviews.firstOrNull()?.id
                                    sourceDraftCommandId = selectedSourceReviewId?.let {
                                        viewModel.newCommandId()
                                    }
                                }
                            }
                            OverviewAction.AddAccount -> {
                                viewModel.clearOperationFeedback()
                                destination = AppDestination.ACCOUNTS
                                openCreateAccount()
                            }
                            OverviewAction.AddManualDraft -> {
                                viewModel.clearOperationFeedback()
                                destination = AppDestination.DRAFTS
                                openManualDraft()
                            }
                            OverviewAction.Retry -> viewModel.retry()
                        }
                    },
                    modifier = Modifier.padding(innerPadding),
                )

                AppDestination.DRAFTS -> DraftsScreen(
                    drafts = snapshot.pendingDrafts,
                    sourceReviews = snapshot.pendingSourceReviews,
                    reconciliationCases = snapshot.reconciliationCases,
                    amountsMasked = state.amountsMasked,
                    onAddManualDraft = {
                        viewModel.clearOperationFeedback()
                        openManualDraft()
                    },
                    onImportTextFile = {
                        viewModel.clearOperationFeedback()
                        showImportMethod = true
                    },
                    onReviewDraft = { draftId ->
                        viewModel.clearOperationFeedback()
                        selectedReconciliationCaseId = null
                        reconciliationCommandId = null
                        selectedSourceReviewId = null
                        sourceDraftCommandId = null
                        selectedDraftId = draftId
                    },
                    onReviewSource = { proposalId ->
                        viewModel.clearOperationFeedback()
                        selectedReconciliationCaseId = null
                        reconciliationCommandId = null
                        selectedDraftId = null
                        selectedSourceReviewId = proposalId
                        sourceDraftCommandId = viewModel.newCommandId()
                    },
                    onReviewReconciliation = { caseId ->
                        viewModel.clearOperationFeedback()
                        selectedDraftId = null
                        selectedSourceReviewId = null
                        sourceDraftCommandId = null
                        selectedReconciliationCaseId = caseId
                        reconciliationCommandId = viewModel.newCommandId()
                    },
                    contentPadding = innerPadding,
                )

                AppDestination.LEDGER -> LedgerScreen(
                    transactions = snapshot.overview.recentTransactions,
                    amountsMasked = state.amountsMasked,
                    voidingTransactionId = state.activeOperation
                        ?.takeIf { it.kind == BillOperationKind.VOID_TRANSACTION }
                        ?.entityId,
                    onVoidTransaction = viewModel::voidTransaction,
                    onAddManualDraft = {
                        viewModel.clearOperationFeedback()
                        destination = AppDestination.DRAFTS
                        openManualDraft()
                    },
                    contentPadding = innerPadding,
                )

                AppDestination.ACCOUNTS -> AccountsScreen(
                    accounts = snapshot.accounts,
                    investmentPositions = snapshot.investmentPositions,
                    amountsMasked = state.amountsMasked,
                    isSubmitting = state.activeOperation?.kind == BillOperationKind.CREATE_ACCOUNT,
                    isInvestmentSubmitting =
                        state.activeOperation?.kind == BillOperationKind.CREATE_INVESTMENT_POSITION,
                    isInvestmentOcrRunning = state.isInvestmentOcrRunning,
                    operationError = state.operationError,
                    showCreateSheet = showCreateAccount,
                    showInvestmentSheet = showCreateInvestment,
                    investmentFormSeed = investmentFormSeed,
                    onCreateRequested = {
                        viewModel.clearOperationFeedback()
                        openCreateAccount()
                    },
                    onInvestmentCreateRequested = {
                        viewModel.clearOperationFeedback()
                        openCreateInvestment()
                    },
                    onInvestmentOcrRequested = { currentInput ->
                        investmentFormSeed = currentInput
                        investmentOcrLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onDismissCreate = {
                        showCreateAccount = false
                        createAccountCommandId = null
                        viewModel.clearOperationFeedback()
                    },
                    onDismissInvestment = {
                        showCreateInvestment = false
                        createInvestmentCommandId = null
                        investmentFormSeed = CreateInvestmentPositionInput()
                        viewModel.clearOperationFeedback()
                    },
                    onCreateAccount = { input ->
                        createAccountCommandId?.let { commandId ->
                            viewModel.createAccount(
                                commandId = commandId,
                                name = input.name,
                                type = input.type,
                                openingBalance = input.openingBalance,
                                currency = input.currency,
                            )
                        }
                    },
                    onCreateInvestment = { input ->
                        createInvestmentCommandId?.let { commandId ->
                            viewModel.createInvestmentPosition(
                                commandId = commandId,
                                name = input.name,
                                instrumentCode = input.instrumentCode,
                                currentValue = input.currentValue,
                                units = input.units,
                                costBasis = input.costBasis,
                                wasOcrPrefilled = input.wasOcrPrefilled,
                            )
                        }
                    },
                    contentPadding = innerPadding,
                )

                AppDestination.SETTINGS -> EvidenceSettingsScreen(
                    state = state.evidence,
                    photoOcrBatch = state.photoOcrBatch,
                    notificationHealth = notificationHealth,
                    notificationRouteSettings = routeSettings,
                    contentPadding = innerPadding,
                    onRetentionSelected = viewModel::updateEvidenceRetention,
                    onClearEvidence = viewModel::clearEvidence,
                    onLoadMore = viewModel::loadMoreEvidence,
                    onRetry = viewModel::retryEvidence,
                    onSelectPhotos = {
                        selectedPhotoLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onNotificationRouteEnabledChange = { routeId, enabled ->
                        coroutineScope.launch {
                            notificationRouteSettings.setEnabled(routeId, enabled)
                        }
                    },
                    onRetryNotificationRouteUpdate = {
                        coroutineScope.launch {
                            notificationRouteSettings.retryLastUpdate()
                        }
                    },
                    onOpenNotificationAccessSettings = {
                        if (!openNotificationListenerSettings(context)) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    resources.getString(
                                        R.string.notification_access_open_failed,
                                    ),
                                )
                            }
                        }
                    },
                )
            }
        }
    }

        if (showImportMethod) {
            ImportMethodSheet(
                onCsvSelected = {
                    showImportMethod = false
                    openStructuredStatement(DelimitedDelimiter.COMMA)
                },
                onTsvSelected = {
                    showImportMethod = false
                    openStructuredStatement(DelimitedDelimiter.TAB)
                },
                onPlainTextSelected = {
                    showImportMethod = false
                    openSelectedTextFile()
                },
                onDismiss = { showImportMethod = false },
            )
        }

        StatementImportSheet(
            state = state.statementImport,
            onInputChanged = viewModel::updateStatementImportMapping,
            onConfirm = viewModel::confirmStatementImport,
            onDismiss = viewModel::cancelStatementImport,
        )

        val snapshot = state.snapshot
        if (showManualDraft && snapshot != null) {
            ManualDraftSheet(
            isSubmitting = state.activeOperation?.kind == BillOperationKind.CREATE_DRAFT,
            operationError = state.operationError,
            onDismiss = {
                showManualDraft = false
                manualDraftCommandId = null
                viewModel.clearOperationFeedback()
            },
            onSubmit = { input ->
                manualDraftCommandId?.let { commandId ->
                    viewModel.createManualDraft(
                        commandId = commandId,
                        kind = input.kind,
                        amount = input.amount,
                        counterparty = input.counterparty,
                        note = input.note,
                        currency = input.currency,
                        observedChannel = input.observedChannel,
                    )
                }
            },
            )
        }

        val selectedSourceReview = snapshot?.pendingSourceReviews
            ?.firstOrNull { it.id == selectedSourceReviewId }
        if (selectedSourceReview != null) {
            SourceDraftSheet(
                sourceReview = selectedSourceReview,
                investmentPositions = snapshot.investmentPositions,
                isSubmitting = state.activeOperation?.let { operation ->
                    operation.kind in sourceReviewOperationKinds &&
                        operation.entityId == selectedSourceReview.id
                } == true,
                operationError = state.operationError,
                onDismiss = {
                    selectedSourceReviewId = null
                    sourceDraftCommandId = null
                    viewModel.clearOperationFeedback()
                },
                onSubmit = { input ->
                    sourceDraftCommandId?.let { commandId ->
                        viewModel.createExternalDraft(
                            commandId = commandId,
                            proposalId = selectedSourceReview.id,
                            kind = input.kind,
                            amount = input.amount,
                            counterparty = input.counterparty,
                            note = input.note,
                            currency = input.currency,
                            occurredAt = selectedSourceReview.suggestedOccurredAt,
                            investmentAccountId = input.investmentAccountId,
                            observedChannel = input.observedChannel,
                        )
                    }
                },
                onIgnore = {
                    viewModel.dismissSourceProposal(
                        commandId = viewModel.newCommandId(),
                        proposalId = selectedSourceReview.id,
                    )
                },
            )
        }

        val selectedDraft = snapshot?.pendingDrafts?.firstOrNull { it.id == selectedDraftId }
        if (selectedDraft != null) {
            val isSavingReview = state.activeOperation?.let { operation ->
                operation.entityId == selectedDraft.id && operation.kind in reviewOperationKinds
            } == true
            ReviewBottomSheet(
            state = ReviewPresenter.present(
                draft = selectedDraft,
                accounts = snapshot.accounts,
                isSaving = isSavingReview,
                operationError = state.operationError,
            ),
            amountsMasked = state.amountsMasked,
            onAction = { action ->
                when (action) {
                    is ReviewAction.SelectFundingAccount -> {
                        viewModel.selectFundingAccount(selectedDraft.id, action.accountId)
                    }
                    ReviewAction.Confirm -> viewModel.confirmDraft(selectedDraft.id)
                    ReviewAction.Edit -> {
                        editingDraftId = selectedDraft.id
                        editDraftCommandId = viewModel.newCommandId()
                        selectedDraftId = null
                        viewModel.clearOperationFeedback()
                    }
                    ReviewAction.Ignore -> viewModel.dismissDraft(selectedDraft.id)
                    ReviewAction.SaveForLater,
                    ReviewAction.Dismiss,
                    -> {
                        selectedDraftId = null
                        viewModel.clearOperationFeedback()
                    }
                }
            },
            )
        }

        val editingDraft = snapshot?.pendingDrafts?.firstOrNull { it.id == editingDraftId }
        if (editingDraft != null) {
            EditDraftSheet(
                draft = editingDraft,
                accounts = snapshot.accounts,
                investmentPositions = snapshot.investmentPositions,
                isSaving = state.activeOperation?.let { operation ->
                    operation.kind == BillOperationKind.UPDATE_DRAFT &&
                        operation.entityId == editingDraft.id
                } == true,
                operationError = state.operationError,
                onSubmit = { input ->
                    editDraftCommandId?.let { commandId ->
                        viewModel.updateDraft(
                            commandId = commandId,
                            draftId = editingDraft.id,
                            kind = input.kind,
                            amount = input.amount,
                            counterparty = input.counterparty,
                            note = input.note,
                            occurredAt = input.occurredAt,
                            observedChannel = input.observedChannel,
                            fundingAccountId = input.fundingAccountId,
                            investmentAccountId = input.investmentAccountId,
                        )
                    }
                },
                onInputChanged = {
                    editDraftCommandId = viewModel.newCommandId()
                    viewModel.clearOperationFeedback()
                },
                onDismiss = {
                    editingDraftId = null
                    editDraftCommandId = null
                    selectedDraftId = editingDraft.id
                    viewModel.clearOperationFeedback()
                },
            )
        }

        val selectedReconciliation = snapshot?.reconciliationCases
            ?.firstOrNull { it.id == selectedReconciliationCaseId }
        if (selectedReconciliation != null) {
            val isSavingReconciliation = state.activeOperation?.let { operation ->
                operation.kind == BillOperationKind.RESOLVE_RECONCILIATION &&
                    operation.entityId == selectedReconciliation.id
            } == true
            ReconciliationBottomSheet(
                case = selectedReconciliation,
                amountsMasked = state.amountsMasked,
                isSaving = isSavingReconciliation,
                operationError = state.operationError,
                onConfirm = {
                    reconciliationCommandId?.let { commandId ->
                        viewModel.resolveReconciliation(
                            commandId = commandId,
                            caseId = selectedReconciliation.id,
                        )
                    }
                },
                onDismiss = {
                    selectedReconciliationCaseId = null
                    reconciliationCommandId = null
                    viewModel.clearOperationFeedback()
                },
            )
        }

        if (state.isPrivacyCovered) {
            PrivacyCurtain()
        }

        if (showLocalOnlyDeclaration && !state.isPrivacyCovered) {
            LocalOnlyStartupDeclaration(
                onContinue = {
                    application?.localOnlyDeclarationState?.acknowledge()
                    showLocalOnlyDeclaration = false
                },
                onOpenPermissionTutorial = {
                    application?.localOnlyDeclarationState?.acknowledge()
                    showLocalOnlyDeclaration = false
                    destination = AppDestination.SETTINGS
                },
            )
        }
    }
}

private val reviewOperationKinds = setOf(
    BillOperationKind.UPDATE_DRAFT,
    BillOperationKind.SELECT_FUNDING_ACCOUNT,
    BillOperationKind.CONFIRM_DRAFT,
    BillOperationKind.DISMISS_DRAFT,
)

private val sourceReviewOperationKinds = setOf(
    BillOperationKind.CREATE_EXTERNAL_DRAFT,
    BillOperationKind.DISMISS_SOURCE_REVIEW,
)

@Composable
private fun HandleUiEvents(
    events: Flow<BillUiEvent>,
    onEvent: suspend (BillUiEvent) -> Unit,
) {
    LaunchedEffect(events) {
        events.collect(onEvent)
    }
}

@Composable
private fun LoadingScreen(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PrivacyCurtain() {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            PosterPanel(
                modifier = Modifier.widthIn(max = 420.dp),
                contentPadding = PaddingValues(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.privacy_marker),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.72f),
                )
                Box(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.privacy_cover_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
                Box(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.privacy_cover_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.82f),
                )
            }
        }
    }
}

@Composable
private fun LocalOnlyStartupDeclaration(
    onContinue: () -> Unit,
    onOpenPermissionTutorial: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(200f),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.local_only_marker),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = stringResource(R.string.local_only_headline),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = stringResource(R.string.local_only_body),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PosterPanel(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(20.dp),
                ) {
                    Text(
                        text = stringResource(R.string.local_only_boundary_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Box(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.local_only_boundary_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.84f),
                    )
                }
                Button(
                    onClick = onOpenPermissionTutorial,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp),
                ) {
                    Text(stringResource(R.string.local_only_open_tutorial))
                }
                OutlinedButton(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp),
                ) {
                    Text(stringResource(R.string.local_only_continue))
                }
            }
        }
    }
}

@Composable
private fun EvidenceSettingsScreen(
    state: EvidenceSettingsUiState,
    photoOcrBatch: PhotoOcrBatchUiState?,
    notificationHealth: NotificationCaptureHealthSnapshot,
    notificationRouteSettings: NotificationRouteSettingsSnapshot,
    contentPadding: PaddingValues,
    onRetentionSelected: (Int?) -> Unit,
    onClearEvidence: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSelectPhotos: () -> Unit,
    onNotificationRouteEnabledChange: (String, Boolean) -> Unit,
    onRetryNotificationRouteUpdate: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
) {
    var pendingClearId by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PosterPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.privacy_marker),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.72f),
                )
                Box(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.settings_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
                Box(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.82f),
                )
            }
        }

        item {
            CapturePermissionTutorialPanel(
                notificationHealth = notificationHealth,
                notificationRouteSettings = notificationRouteSettings,
                photoOcrBatch = photoOcrBatch,
                onSelectPhotos = onSelectPhotos,
                onNotificationRouteEnabledChange = onNotificationRouteEnabledChange,
                onRetryNotificationRouteUpdate = onRetryNotificationRouteUpdate,
                onOpenNotificationAccessSettings = onOpenNotificationAccessSettings,
            )
        }

        item {
            EvidencePolicyPanel(
                state = state,
                onRetentionSelected = onRetentionSelected,
            )
        }

        item {
            Text(
                text = stringResource(R.string.evidence_list_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        if (state.hasFatalError) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.evidence_error))
                        Button(onClick = onRetry) {
                            Text(stringResource(R.string.evidence_retry))
                        }
                    }
                }
            }
        }

        if (state.isLoading && state.items.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.items.isEmpty() && !state.hasFatalError) {
            item {
                Text(
                    text = stringResource(R.string.evidence_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(
            items = state.items,
            key = { item -> item.rawEventId.value },
        ) { item ->
            EvidenceItemCard(
                item = item,
                isBusy = state.activeOperation?.rawEventId == item.rawEventId.value,
                actionsEnabled = state.activeOperation == null &&
                    !state.isLoading &&
                    !state.isLoadingMore,
                onClearRequested = {
                    if (item.state == EvidencePayloadState.AVAILABLE) {
                        pendingClearId = item.rawEventId.value
                    } else {
                        onClearEvidence(item.rawEventId.value)
                    }
                },
            )
        }

        if (state.nextCursor != null) {
            item {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !state.isLoadingMore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isLoadingMore) {
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(R.string.evidence_load_more))
                    }
                }
            }
        }
    }

    pendingClearId?.let { rawEventId ->
        AlertDialog(
            onDismissRequest = { pendingClearId = null },
            title = { Text(stringResource(R.string.evidence_clear_dialog_title)) },
            text = { Text(stringResource(R.string.evidence_clear_dialog_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingClearId = null
                        onClearEvidence(rawEventId)
                    },
                ) {
                    Text(stringResource(R.string.evidence_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearId = null }) {
                    Text(stringResource(R.string.evidence_cancel))
                }
            },
        )
    }
}

@Composable
private fun CapturePermissionTutorialPanel(
    notificationHealth: NotificationCaptureHealthSnapshot,
    notificationRouteSettings: NotificationRouteSettingsSnapshot,
    photoOcrBatch: PhotoOcrBatchUiState?,
    onSelectPhotos: () -> Unit,
    onNotificationRouteEnabledChange: (String, Boolean) -> Unit,
    onRetryNotificationRouteUpdate: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
) {
    val context = LocalContext.current
    val quickCaptureConnection by BillQuickCaptureRuntime
        .controller
        .connectionState
        .collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.capture_tutorial_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(R.string.capture_tutorial_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text(
                text = stringResource(R.string.capture_tutorial_notification_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    if (notificationRouteSettings.hasVerifiedRoutes) {
                        R.string.capture_tutorial_notification_body_available
                    } else {
                        R.string.capture_tutorial_notification_body
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notificationRouteSettings.routes.forEach { route ->
                val routeToggleEnabled =
                    notificationRouteSettings.updatingRouteId == null &&
                        !notificationRouteSettings.lastUpdateFailed
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .semantics(mergeDescendants = true) {}
                            .toggleable(
                                value = route.enabled,
                                enabled = routeToggleEnabled,
                                role = Role.Switch,
                                onValueChange = { enabled ->
                                    onNotificationRouteEnabledChange(route.routeId, enabled)
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = route.safeLabel,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Switch(
                            checked = route.enabled,
                            onCheckedChange = null,
                            modifier = Modifier.clearAndSetSemantics {},
                            enabled = routeToggleEnabled,
                        )
                    }
                }
            }
            if (notificationRouteSettings.lastUpdateFailed) {
                Text(
                    text = stringResource(R.string.notification_route_update_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = onRetryNotificationRouteUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = notificationRouteSettings.updatingRouteId == null,
                ) {
                    Text(stringResource(R.string.notification_route_retry))
                }
            }
            if (
                notificationRouteSettings.hasEnabledRoutes ||
                notificationHealth.hasSystemAccess
            ) {
                Text(
                    text = stringResource(R.string.notification_access_scope_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onOpenNotificationAccessSettings,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = notificationRouteSettings.updatingRouteId == null,
                ) {
                    Text(
                        stringResource(
                            if (notificationHealth.hasSystemAccess) {
                                R.string.notification_access_manage_settings
                            } else {
                                R.string.notification_access_grant_settings
                            },
                        ),
                    )
                }
            }
            Text(
                text = notificationHealth.message(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = when (notificationHealth.state) {
                    NotificationCaptureHealthState.NO_VERIFIED_TEMPLATES,
                    NotificationCaptureHealthState.NO_ENABLED_ROUTES,
                    NotificationCaptureHealthState.SYSTEM_ACCESS_REQUIRED,
                    NotificationCaptureHealthState.LISTENER_CONNECTION_PENDING,
                    NotificationCaptureHealthState.READY,
                    -> MaterialTheme.colorScheme.primary

                    NotificationCaptureHealthState.BACKPRESSURE,
                    NotificationCaptureHealthState.RECENT_FAILURE,
                    -> MaterialTheme.colorScheme.error
                },
            )
            HorizontalDivider()
            Text(
                text = stringResource(R.string.capture_tutorial_accessibility_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.capture_tutorial_accessibility_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text(
                text = stringResource(R.string.capture_tutorial_screenshot_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.capture_tutorial_screenshot_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (quickCaptureConnection == QuickCaptureConnectionState.CONNECTED) {
                        R.string.quick_capture_accessibility_status_ready
                    } else {
                        R.string.quick_capture_accessibility_status_off
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (
                    quickCaptureConnection == QuickCaptureConnectionState.CONNECTED
                ) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Button(
                onClick = { openQuickCaptureAccessibilitySettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.quick_capture_open_accessibility))
            }
            OutlinedButton(
                onClick = { requestQuickCaptureTile(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.quick_capture_add_tile))
            }
            OutlinedButton(
                onClick = onSelectPhotos,
                modifier = Modifier.fillMaxWidth(),
                enabled = photoOcrBatch == null,
            ) {
                Text(
                    if (photoOcrBatch == null) {
                        stringResource(R.string.quick_capture_select_images)
                    } else {
                        stringResource(
                            R.string.photo_ocr_batch_progress,
                            photoOcrBatch.processedCount,
                            photoOcrBatch.totalCount,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun EvidencePolicyPanel(
    state: EvidenceSettingsUiState,
    onRetentionSelected: (Int?) -> Unit,
) {
    val overview = state.overview
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.evidence_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(R.string.evidence_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (overview != null) {
                HorizontalDivider()
                Text(
                    text = stringResource(
                        R.string.evidence_storage_summary,
                        overview.storage.storedCount,
                        formatEvidenceBytes(overview.storage.storedBytes),
                    ),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.evidence_cleared_summary,
                        overview.storage.clearedCount,
                        overview.storage.clearPendingCount,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.evidence_retention_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                RetentionButtonRow(
                    currentDays = overview.policy.retentionDays,
                    options = listOf(
                        7 to R.string.evidence_retention_7,
                        30 to R.string.evidence_retention_30,
                    ),
                    enabled = state.activeOperation == null &&
                        !state.isLoading &&
                        !state.isLoadingMore,
                    onSelected = onRetentionSelected,
                )
                RetentionButtonRow(
                    currentDays = overview.policy.retentionDays,
                    options = listOf(
                        90 to R.string.evidence_retention_90,
                        null to R.string.evidence_retention_forever,
                    ),
                    enabled = state.activeOperation == null &&
                        !state.isLoading &&
                        !state.isLoadingMore,
                    onSelected = onRetentionSelected,
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun RetentionButtonRow(
    currentDays: Int?,
    options: List<Pair<Int?, Int>>,
    enabled: Boolean,
    onSelected: (Int?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { (days, labelRes) ->
            val modifier = Modifier.weight(1f)
            if (currentDays == days) {
                Button(
                    onClick = { onSelected(days) },
                    enabled = enabled,
                    modifier = modifier,
                ) {
                    Text(stringResource(labelRes))
                }
            } else {
                OutlinedButton(
                    onClick = { onSelected(days) },
                    enabled = enabled,
                    modifier = modifier,
                ) {
                    Text(stringResource(labelRes))
                }
            }
        }
    }
}

@Composable
private fun EvidenceItemCard(
    item: SourceEvidenceItem,
    isBusy: Boolean,
    actionsEnabled: Boolean,
    onClearRequested: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.evidence_item_title,
                    stringResource(item.sourceFamily.labelRes()),
                    stringResource(item.captureMethod.labelRes()),
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(
                    R.string.evidence_captured_at,
                    EvidenceTimeFormatter.format(item.capturedAt),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = item.payloadSizeBytes?.let { bytes ->
                    stringResource(
                        R.string.evidence_payload_size,
                        formatEvidenceBytes(bytes),
                    )
                } ?: stringResource(R.string.evidence_payload_size_unknown),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(item.statusRes()),
                color = when (item.state) {
                    EvidencePayloadState.AVAILABLE -> MaterialTheme.colorScheme.primary
                    EvidencePayloadState.CLEAR_PENDING -> MaterialTheme.colorScheme.error
                    EvidencePayloadState.CLEARED -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.Bold,
            )
            if (item.state != EvidencePayloadState.CLEARED) {
                OutlinedButton(
                    onClick = onClearRequested,
                    enabled = actionsEnabled && !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isBusy) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            stringResource(
                                if (item.state == EvidencePayloadState.CLEAR_PENDING) {
                                    R.string.evidence_retry_clear
                                } else {
                                    R.string.evidence_clear
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun SourceCaptureError.messageRes(): Int = when (this) {
    SourceCaptureError.EMPTY_CONTENT ->
        R.string.source_capture_empty

    SourceCaptureError.CONTENT_TOO_LARGE ->
        R.string.source_capture_too_large

    SourceCaptureError.EVIDENCE_WRITE_FAILED ->
        R.string.source_capture_write_failed

    SourceCaptureError.EVIDENCE_ALREADY_CLEARED ->
        R.string.source_capture_already_cleared

    SourceCaptureError.STORAGE_LIMIT_REACHED ->
        R.string.source_capture_storage_limit

    SourceCaptureError.STAGING_RECOVERY_IN_PROGRESS ->
        R.string.source_capture_recovery_in_progress

    SourceCaptureError.INVALID_COMMAND,
    SourceCaptureError.EVIDENCE_COLLISION,
    SourceCaptureError.RAW_EVENT_COLLISION,
    -> R.string.source_capture_collision

    SourceCaptureError.PARSER_UNAVAILABLE,
    SourceCaptureError.PARSE_REJECTED,
    -> R.string.source_capture_unreadable

    SourceCaptureError.COMMIT_FAILED ->
        R.string.source_capture_commit_failed

    SourceCaptureError.COMMIT_STATUS_UNKNOWN ->
        R.string.quick_capture_result_unconfirmed
}

private fun InvestmentOcrPrefillUiError.messageRes(): Int = when (this) {
    InvestmentOcrPrefillUiError.EMPTY_IMAGE -> R.string.investment_ocr_empty
    InvestmentOcrPrefillUiError.IMAGE_TOO_LARGE -> R.string.investment_ocr_too_large
    InvestmentOcrPrefillUiError.UNREADABLE_IMAGE -> R.string.investment_ocr_unreadable
    InvestmentOcrPrefillUiError.NO_RECOGNIZED_FIELDS -> R.string.investment_ocr_no_fields
    InvestmentOcrPrefillUiError.AMBIGUOUS_FIELDS -> R.string.investment_ocr_ambiguous
}

private fun CreateInvestmentPositionInput.merge(
    prefill: InvestmentPositionOcrPrefill,
): CreateInvestmentPositionInput {
    val usedOcrValue =
        (name.isBlank() && prefill.name != null) ||
            (instrumentCode.isBlank() && prefill.instrumentCode != null) ||
            (currentValue.isBlank() && prefill.currentValue != null) ||
            (units.isBlank() && prefill.units != null) ||
            (costBasis.isBlank() && prefill.costBasis != null)
    return copy(
        name = name.ifBlank { prefill.name.orEmpty() },
        instrumentCode = instrumentCode.ifBlank { prefill.instrumentCode.orEmpty() },
        currentValue = currentValue.ifBlank { prefill.currentValue.orEmpty() },
        units = units.ifBlank { prefill.units.orEmpty() },
        costBasis = costBasis.ifBlank { prefill.costBasis.orEmpty() },
        wasOcrPrefilled = wasOcrPrefilled || usedOcrValue,
    )
}

@Composable
private fun NotificationCaptureHealthSnapshot.message(): String = when (state) {
    NotificationCaptureHealthState.NO_VERIFIED_TEMPLATES ->
        stringResource(R.string.capture_health_no_templates)

    NotificationCaptureHealthState.NO_ENABLED_ROUTES ->
        stringResource(R.string.capture_health_no_enabled_routes)

    NotificationCaptureHealthState.SYSTEM_ACCESS_REQUIRED ->
        stringResource(R.string.capture_health_system_access_required)

    NotificationCaptureHealthState.LISTENER_CONNECTION_PENDING ->
        stringResource(R.string.capture_health_listener_connection_pending)

    NotificationCaptureHealthState.READY ->
        stringResource(R.string.capture_health_ready)

    NotificationCaptureHealthState.BACKPRESSURE ->
        stringResource(R.string.capture_health_backpressure, droppedInThisProcess)

    NotificationCaptureHealthState.RECENT_FAILURE ->
        stringResource(R.string.capture_health_failure, failuresInThisProcess)
}

private fun SourceEvidenceOperationError.messageRes(): Int = when (this) {
    SourceEvidenceOperationError.NOT_FOUND -> R.string.evidence_operation_not_found
    SourceEvidenceOperationError.CONFLICT -> R.string.evidence_operation_conflict
    SourceEvidenceOperationError.INVALID_STATE -> R.string.evidence_operation_invalid
    SourceEvidenceOperationError.PAYLOAD_DELETE_FAILED ->
        R.string.evidence_operation_delete_failed
}

private fun SourceFamily.labelRes(): Int = when (this) {
    SourceFamily.ALIPAY -> R.string.source_family_alipay
    SourceFamily.WECHAT -> R.string.source_family_wechat
    SourceFamily.BANK -> R.string.source_family_bank
    SourceFamily.GENERIC -> R.string.source_family_generic
    SourceFamily.MANUAL -> R.string.source_family_manual
}

private fun CaptureMethod.labelRes(): Int = when (this) {
    CaptureMethod.NOTIFICATION -> R.string.capture_method_notification
    CaptureMethod.STATEMENT_IMPORT -> R.string.capture_method_statement
    CaptureMethod.SHARE_TEXT -> R.string.capture_method_share_text
    CaptureMethod.SHARE_FILE -> R.string.capture_method_share_file
    CaptureMethod.PHOTO_OCR -> R.string.capture_method_photo
    CaptureMethod.MANUAL -> R.string.capture_method_manual
}

private fun SourceEvidenceItem.statusRes(): Int = when (state) {
    EvidencePayloadState.AVAILABLE -> if (hasPendingReview) {
        R.string.evidence_status_pending_review
    } else {
        R.string.evidence_status_available
    }

    EvidencePayloadState.CLEAR_PENDING -> R.string.evidence_status_clear_pending
    EvidencePayloadState.CLEARED -> R.string.evidence_status_cleared
}

private fun formatEvidenceBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

private val EvidenceTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
