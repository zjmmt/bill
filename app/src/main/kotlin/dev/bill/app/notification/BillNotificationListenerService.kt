package dev.bill.app.notification

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.bill.app.BillApplication
import dev.bill.source.contract.NotificationObservationId
import dev.bill.source.contract.NotificationObservationWriteStatus
import dev.bill.source.genericnotification.NotificationContent
import dev.bill.source.genericnotification.NotificationMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * System-event entry point for future verified notification templates.
 *
 * It does not read historical/active notifications, alter external notifications, start UI or use
 * a foreground service. Production routes are disabled by default, so a fresh install returns
 * after metadata gating and never asks for a title, message body or other `extras` value until the
 * user explicitly enables an individual safe-labelled route. A debug build may additionally copy
 * the same bounded fields only while its user-controlled, forward-only, package-scoped
 * template-sampling window is active.
 */
class BillNotificationListenerService : NotificationListenerService() {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureQueue = BoundedNotificationWorkQueue<QueuedNotificationCapture>(QueueCapacity)
    private var queueWorker: Job? = null

    override fun onCreate() {
        super.onCreate()
        queueWorker = ioScope.launch {
            captureQueue.consumeEach(::ingestQueued)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateListenerConnection(connected = true)
    }

    override fun onListenerDisconnected() {
        updateListenerConnection(connected = false)
        try {
            requestRebind(
                ComponentName(this, BillNotificationListenerService::class.java),
            )
        } catch (_: RuntimeException) {
            // Keep health disconnected. Never log notification or package data from this service.
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val application = application as? BillApplication ?: return
        val notification = sbn.notification ?: return
        val metadata = try {
            NotificationMetadata(
                packageName = sbn.packageName,
                channelId = notification.channelId,
                category = notification.category,
            )
        } catch (_: RuntimeException) {
            return
        }
        val container = application.container
        val samplingCandidate = container.notificationTemplateSamplingController.acceptsMetadata(
            metadata = metadata,
            postedAtEpochMillis = sbn.postTime,
        )
        val productionCandidate =
            container.notificationCaptureCoordinator.acceptsMetadata(metadata)
        if (!samplingCandidate && !productionCandidate) return
        val content = AndroidNotificationContentExtractor.extract(notification)
        if (content == null) {
            if (samplingCandidate) {
                container.notificationTemplateSamplingController.onContentRejected()
            }
            return
        }
        val observationId = if (productionCandidate) {
            container.notificationObservationIdDeriver.derive(
                notificationKey = sbn.key,
                postedAtEpochMillis = sbn.postTime,
            )
        } else {
            null
        }
        if (!samplingCandidate && observationId == null) return

        // Never launch one coroutine per callback. A full queue drops this event safely; a later
        // notification update can still arrive, and no notification body is written or logged.
        val offer = captureQueue.offer(
            QueuedNotificationCapture(
                observationId = observationId,
                metadata = metadata,
                postedAtEpochMillis = sbn.postTime,
                content = content,
                isSamplingCandidate = samplingCandidate,
            ),
        )
        if (offer == NotificationQueueOfferResult.FULL) {
            if (samplingCandidate) {
                container.notificationTemplateSamplingController.onQueueDropped()
            }
            if (productionCandidate) {
                container.notificationCaptureHealth.onQueueDropped()
            }
        }
    }

    override fun onDestroy() {
        updateListenerConnection(connected = false)
        captureQueue.close()
        queueWorker?.cancel()
        ioScope.cancel()
        super.onDestroy()
    }

    private fun updateListenerConnection(connected: Boolean) {
        val application = application as? BillApplication ?: return
        val container = application.container
        publishNotificationListenerConnection(
            connected = connected,
            refreshConfiguration = { container.refreshNotificationCaptureConfiguration() },
            health = container.notificationCaptureHealth,
        )
    }

    private suspend fun ingestQueued(work: QueuedNotificationCapture) {
        val application = application as? BillApplication ?: return
        val container = application.container
        if (work.isSamplingCandidate) {
            container.notificationTemplateSamplingController.record(
                metadata = work.metadata,
                postedAtEpochMillis = work.postedAtEpochMillis,
                content = work.content,
            )
        }
        val observationId = work.observationId ?: return
        try {
            val prepared = container.notificationCaptureCoordinator.prepare(
                observationId = observationId,
                metadata = work.metadata,
                postedAtEpochMillis = work.postedAtEpochMillis,
                content = work.content,
            ) ?: return
            val result = container.notificationEvidenceIngestionService.ingest(
                commandId = prepared.commandId,
                route = prepared.route,
                envelope = prepared.envelope,
            )
            val captured = result is dev.bill.application.NotificationCaptureResult.ReadyForReview
            val completion = container.notificationCaptureCoordinator.complete(
                prepared = prepared,
                captured = captured,
            )
            if (
                result is dev.bill.application.NotificationCaptureResult.Ignored &&
                    (
                        completion == NotificationObservationWriteStatus.APPLIED ||
                            completion == NotificationObservationWriteStatus.ALREADY_APPLIED
                    )
            ) {
                return
            }
            if (
                captured &&
                (
                    completion == NotificationObservationWriteStatus.APPLIED ||
                        completion == NotificationObservationWriteStatus.ALREADY_APPLIED
                    )
            ) {
                container.notificationCaptureHealth.onCaptureRecorded()
            } else {
                container.notificationCaptureHealth.onCaptureFailure()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            // The lease expires and the same opaque command is recovered on a future callback.
            // Never log or surface raw notification data from this service.
            container.notificationCaptureHealth.onCaptureFailure()
        }
    }

    /** Bounded, in-memory only; its default toString intentionally contains no notification body. */
    private class QueuedNotificationCapture(
        val observationId: NotificationObservationId?,
        val metadata: NotificationMetadata,
        val postedAtEpochMillis: Long,
        val content: NotificationContent,
        val isSamplingCandidate: Boolean,
    ) {
        override fun toString(): String = "QueuedNotificationCapture(redacted=true)"
    }

    private companion object {
        const val QueueCapacity = 16
    }
}
