package dev.bill.app

import android.app.Application
import android.content.Context
import dev.bill.application.BillService
import dev.bill.application.NotificationEvidenceIngestionService
import dev.bill.application.LocalDelimitedStatementImportService
import dev.bill.application.NotificationRouteLabelResolver
import dev.bill.application.PhotoOcrTranscriptIngestionService
import dev.bill.application.SelectedTextFileIngestionService
import dev.bill.application.SharedReceiptImageIngestionService
import dev.bill.application.SharedTextIngestionService
import dev.bill.application.SourceEvidenceLifecycleService
import dev.bill.data.local.AppPrivateEvidenceStore
import dev.bill.data.local.BillDatabaseFactory
import dev.bill.data.local.RoomLedgerRepository
import dev.bill.data.local.RoomNotificationObservationRepository
import dev.bill.data.local.RoomStatementImportBatchRepository
import dev.bill.data.local.RoomRawEventRepository
import dev.bill.data.local.RoomSourceRepository
import dev.bill.data.local.RoomSourceEvidenceLifecycleRepository
import dev.bill.data.local.RoomSourceEvidenceStagingRepository
import dev.bill.source.genericsharetext.GenericSelectedTextFileParser
import dev.bill.source.genericsharetext.GenericShareTextParser
import dev.bill.source.genericdelimited.GenericDelimitedStatementParser
import dev.bill.source.genericphotoocr.GenericPhotoOcrParser
import dev.bill.source.genericreceiptimage.GenericSharedReceiptImageParser
import dev.bill.source.genericnotification.GenericNotificationParser
import dev.bill.source.genericnotification.NotificationTemplateGate
import dev.bill.app.notification.NotificationCaptureCoordinator
import dev.bill.app.notification.AppPrivateNotificationObservationIdDeriver
import dev.bill.app.notification.AndroidNotificationListenerAccess
import dev.bill.app.notification.NotificationCaptureHealth
import dev.bill.app.notification.NotificationRouteSettings
import dev.bill.app.notification.SharedPreferencesNotificationRouteEnablement
import dev.bill.app.notification.BuildVariantNotificationTemplateSamplingController
import dev.bill.source.contract.NotificationObservationRepository
import dev.bill.source.contract.NotificationObservationReservation
import dev.bill.source.contract.NotificationObservationReserveResult
import dev.bill.source.contract.NotificationObservationWriteResult
import dev.bill.source.pipeline.ParserRegistry
import dev.bill.source.pipeline.SourceIngestionService
import java.time.Clock

class BillApplication : Application() {
    internal val localOnlyDeclarationState = ProcessLocalOnlyDeclarationState()

    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(applicationContext)
    }

}

/** Kept in memory so the notice returns after a fresh process, not after a rotation. */
internal class ProcessLocalOnlyDeclarationState {
    @Volatile
    private var acknowledged = false

    fun shouldShow(): Boolean = !acknowledged

    fun acknowledge() {
        acknowledged = true
    }
}

class AppContainer(context: Context) {
    internal val notificationTemplateSamplingController =
        BuildVariantNotificationTemplateSamplingController(context)

    private val database by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BillDatabaseFactory.create(context)
    }

    private val rawEventRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomRawEventRepository(database)
    }

    private val sourceRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomSourceRepository(database)
    }

    private val evidenceStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppPrivateEvidenceStore(context.applicationContext)
    }

    /** One catalog owns metadata gating, parser registration and safe UI labels. */
    private val notificationRouteCatalog = ProductionNotificationRoutes.catalog

    private val notificationRouteEnablement by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SharedPreferencesNotificationRouteEnablement(context, notificationRouteCatalog)
    }

    private val notificationListenerAccess = AndroidNotificationListenerAccess(context)

    val sourceEvidenceLifecycleService: SourceEvidenceLifecycleService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        SourceEvidenceLifecycleService(
            repository = RoomSourceEvidenceLifecycleRepository(database),
            store = evidenceStore,
            stagingRepository = RoomSourceEvidenceStagingRepository(database),
            artifactStore = evidenceStore,
        )
    }

    private val sourceIngestionService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SourceIngestionService(
            rawEventRepository = rawEventRepository,
            evidenceReader = evidenceStore,
            parserRegistry = ParserRegistry(
                listOf(
                    GenericNotificationParser(),
                ) + notificationRouteCatalog.parsers() + listOf(
                    GenericPhotoOcrParser(),
                    GenericDelimitedStatementParser(),
                    GenericShareTextParser(),
                    GenericSelectedTextFileParser(),
                    GenericSharedReceiptImageParser(),
                ),
            ),
            commitStore = sourceRepository,
            clock = Clock.systemUTC(),
            maxEvidenceBytes = SharedReceiptImageIngestionService.MAX_SHARED_RECEIPT_IMAGE_BYTES,
        )
    }

    val billService: BillService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BillService(
            repository = RoomLedgerRepository(database),
            sourceReviewRepository = sourceRepository,
            notificationRouteLabelResolver = NotificationRouteLabelResolver { connectorId ->
                notificationRouteCatalog.safeLabelForConnector(connectorId)
            },
        )
    }

    val sharedTextIngestionService: SharedTextIngestionService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        SharedTextIngestionService(
            rawEventRepository = rawEventRepository,
            evidenceStore = evidenceStore,
            sourceIngestionService = sourceIngestionService,
            evidenceAdmission = sourceEvidenceLifecycleService,
        )
    }

    val selectedTextFileIngestionService: SelectedTextFileIngestionService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        SelectedTextFileIngestionService(
            rawEventRepository = rawEventRepository,
            evidenceStore = evidenceStore,
            sourceIngestionService = sourceIngestionService,
            evidenceAdmission = sourceEvidenceLifecycleService,
        )
    }

    val localDelimitedStatementImportService: LocalDelimitedStatementImportService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        LocalDelimitedStatementImportService(
            batchRepository = RoomStatementImportBatchRepository(database),
            rawEventRepository = rawEventRepository,
            evidenceStore = evidenceStore,
            sourceIngestionService = sourceIngestionService,
            evidenceAdmission = sourceEvidenceLifecycleService,
        )
    }

    val sharedReceiptImageIngestionService: SharedReceiptImageIngestionService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        SharedReceiptImageIngestionService(
            rawEventRepository = rawEventRepository,
            evidenceStore = evidenceStore,
            sourceIngestionService = sourceIngestionService,
            evidenceAdmission = sourceEvidenceLifecycleService,
        )
    }

    val notificationEvidenceIngestionService: NotificationEvidenceIngestionService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        NotificationEvidenceIngestionService(
            rawEventRepository = rawEventRepository,
            evidenceStore = evidenceStore,
            sourceIngestionService = sourceIngestionService,
            routeCatalog = notificationRouteCatalog,
            isRouteEnabled = notificationRouteEnablement::isEnabled,
            evidenceAdmission = sourceEvidenceLifecycleService,
        )
    }

    val photoOcrTranscriptIngestionService: PhotoOcrTranscriptIngestionService by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        PhotoOcrTranscriptIngestionService(
            rawEventRepository = rawEventRepository,
            evidenceStore = evidenceStore,
            sourceIngestionService = sourceIngestionService,
            evidenceAdmission = sourceEvidenceLifecycleService,
        )
    }

    private val notificationTemplateGate = NotificationTemplateGate(
        catalog = notificationRouteCatalog,
        isRouteEnabled = notificationRouteEnablement::isEnabled,
    )

    internal val notificationCaptureHealth = NotificationCaptureHealth(
        hasVerifiedTemplates = notificationTemplateGate.hasVerifiedTemplates(),
        hasEnabledRoutes = notificationRouteEnablement.enabledRouteIds().isNotEmpty(),
        hasSystemAccess = notificationListenerAccess.isGranted(),
    )

    internal val notificationRouteSettings = NotificationRouteSettings(
        catalog = notificationRouteCatalog,
        enablement = notificationRouteEnablement,
        onEnabledRoutesChanged = { enabledRouteIds ->
            notificationCaptureHealth.onConfigurationChanged(
                hasEnabledRoutes = enabledRouteIds.isNotEmpty(),
                hasSystemAccess = notificationListenerAccess.isGranted(),
            )
        },
    )

    internal fun refreshNotificationCaptureConfiguration(): Boolean {
        val hasSystemAccess = notificationListenerAccess.isGranted()
        notificationCaptureHealth.onConfigurationChanged(
            hasEnabledRoutes = notificationRouteEnablement.enabledRouteIds().isNotEmpty(),
            hasSystemAccess = hasSystemAccess,
        )
        return hasSystemAccess
    }

    private val notificationObservationRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomNotificationObservationRepository(database)
    }

    /** Prevents an empty metadata catalog from opening the database on every system callback. */
    private val deferredNotificationObservationRepository = object : NotificationObservationRepository {
        private val delegate: NotificationObservationRepository by lazy(
            LazyThreadSafetyMode.SYNCHRONIZED,
        ) {
            notificationObservationRepository
        }

        override suspend fun reserve(
            requested: NotificationObservationReservation,
            now: java.time.Instant,
        ): NotificationObservationReserveResult = delegate.reserve(requested, now)

        override suspend fun markCaptured(
            reservation: NotificationObservationReservation,
            capturedAt: java.time.Instant,
        ): NotificationObservationWriteResult = delegate.markCaptured(reservation, capturedAt)

        override suspend fun release(
            reservation: NotificationObservationReservation,
        ): NotificationObservationWriteResult = delegate.release(reservation)
    }

    internal val notificationObservationIdDeriver by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppPrivateNotificationObservationIdDeriver(context.applicationContext)
    }

    internal val notificationCaptureCoordinator: NotificationCaptureCoordinator by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        NotificationCaptureCoordinator(
            gate = notificationTemplateGate,
            observationRepository = deferredNotificationObservationRepository,
            hasDurableUpdateDedupe = true,
        )
    }
}
