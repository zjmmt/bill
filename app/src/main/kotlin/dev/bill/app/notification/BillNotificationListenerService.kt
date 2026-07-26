package dev.bill.app.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.bill.app.BillApplication
import dev.bill.source.contract.NotificationObservationId
import dev.bill.source.contract.NotificationObservationWriteStatus
import dev.bill.source.genericnotification.NotificationContent
import dev.bill.source.genericnotification.NotificationMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * System-event entry point for future verified notification templates.
 *
 * It does not read historical/active notifications, alter external notifications, start UI or use
 * a foreground service. With the current empty template catalog it returns after metadata gating
 * and never asks for a title, message body or other `extras` value.
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
        if (!container.notificationCaptureCoordinator.acceptsMetadata(metadata)) return
        val observationId = container.notificationObservationIdDeriver.derive(
            notificationKey = sbn.key,
            postedAtEpochMillis = sbn.postTime,
        ) ?: return
        val content = AndroidNotificationContentExtractor.extract(notification) ?: return

        // Never launch one coroutine per callback. A full queue drops this event safely; a later
        // notification update can still arrive, and no notification body is written or logged.
        val offer = captureQueue.offer(
            QueuedNotificationCapture(
                observationId = observationId,
                metadata = metadata,
                postedAtEpochMillis = sbn.postTime,
                content = content,
            ),
        )
        if (offer == NotificationQueueOfferResult.FULL) {
            container.notificationCaptureHealth.onQueueDropped()
        }
    }

    override fun onDestroy() {
        captureQueue.close()
        queueWorker?.cancel()
        ioScope.cancel()
        super.onDestroy()
    }

    private suspend fun ingestQueued(work: QueuedNotificationCapture) {
        val application = application as? BillApplication ?: return
        val container = application.container
        try {
            val prepared = container.notificationCaptureCoordinator.prepare(
                observationId = work.observationId,
                metadata = work.metadata,
                postedAtEpochMillis = work.postedAtEpochMillis,
                content = work.content,
            ) ?: return
            val result = container.notificationEvidenceIngestionService.ingest(
                commandId = prepared.commandId,
                envelope = prepared.envelope,
            )
            val captured = result is dev.bill.application.NotificationCaptureResult.ReadyForReview
            val completion = container.notificationCaptureCoordinator.complete(
                prepared = prepared,
                captured = captured,
            )
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
        val observationId: NotificationObservationId,
        val metadata: NotificationMetadata,
        val postedAtEpochMillis: Long,
        val content: NotificationContent,
    ) {
        override fun toString(): String = "QueuedNotificationCapture(redacted=true)"
    }

    private companion object {
        const val QueueCapacity = 16
    }
}
