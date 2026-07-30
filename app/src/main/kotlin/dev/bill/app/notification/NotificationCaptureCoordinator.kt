package dev.bill.app.notification

import android.app.Notification
import android.os.Bundle
import dev.bill.source.contract.NotificationField
import dev.bill.source.contract.NotificationCaptureCommandId
import dev.bill.source.contract.NotificationObservationId
import dev.bill.source.contract.NotificationObservationLeaseId
import dev.bill.source.contract.NotificationObservationRepository
import dev.bill.source.contract.NotificationObservationReservation
import dev.bill.source.contract.NotificationObservationReserveStatus
import dev.bill.source.contract.NotificationObservationWriteStatus
import dev.bill.source.genericnotification.NotificationContent
import dev.bill.source.genericnotification.NotificationEnvelope
import dev.bill.source.genericnotification.NotificationGateDecision
import dev.bill.source.genericnotification.NotificationMetadata
import dev.bill.source.genericnotification.NotificationTemplateGate
import dev.bill.source.genericnotification.VerifiedNotificationRoute
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** A transient Android-independent hand-off from the system callback to the IO evidence writer. */
internal class PreparedNotificationCapture(
    val reservation: NotificationObservationReservation,
    val route: VerifiedNotificationRoute,
    val envelope: NotificationEnvelope,
) {
    val commandId: String
        get() = reservation.commandId.value

    override fun toString(): String = "PreparedNotificationCapture(redacted=true)"
}

/**
 * Keeps the Android listener thin. The production catalog is intentionally empty until a provider
 * has sanitized replay fixtures. It accepts content only after an explicit metadata rule, and it
 * reserves a durable redacted observation before handing evidence to the ingestion pipeline.
 */
internal class NotificationCaptureCoordinator(
    private val gate: NotificationTemplateGate,
    private val observationRepository: NotificationObservationRepository =
        NotificationObservationRepository.Disabled,
    private val clock: Clock = Clock.systemUTC(),
    private val commandIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val leaseIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val leaseDuration: Duration = DefaultLeaseDuration,
    /** This release gate stays closed until the caller supplies a durable store and bounded queue. */
    private val hasDurableUpdateDedupe: Boolean = false,
) {
    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) {
            "Notification observation lease duration must be positive"
        }
    }

    /** Safe to call from the listener callback: this checks only transient metadata. */
    fun acceptsMetadata(metadata: NotificationMetadata): Boolean =
        hasDurableUpdateDedupe && gate.hasMetadataCandidate(metadata)

    /**
     * Runs on the bounded IO worker. A matching notification only receives a RawEvent command
     * after its redacted identity has an acquired durable lease.
     */
    suspend fun prepare(
        observationId: NotificationObservationId,
        metadata: NotificationMetadata,
        postedAtEpochMillis: Long,
        content: NotificationContent,
    ): PreparedNotificationCapture? {
        if (!acceptsMetadata(metadata)) return null
        if (postedAtEpochMillis < 0L) return null
        val decision = gate.evaluate(metadata) { content }
        if (decision !is NotificationGateDecision.Accepted) return null
        val commandId = commandIdFactory().toCommandIdOrNull()
            ?: return null
        val leaseId = leaseIdFactory().toLeaseIdOrNull()
            ?: return null
        val now = clock.instant()
        val reservation = try {
            NotificationObservationReservation(
                observationId = observationId,
                commandId = commandId,
                leaseId = leaseId,
                createdAt = now,
                expiresAt = now.plus(leaseDuration),
            )
        } catch (_: RuntimeException) {
            return null
        }
        val reserve = observationRepository.reserve(reservation, now)
        val acquired = when (reserve.status) {
            NotificationObservationReserveStatus.RESERVED,
            NotificationObservationReserveStatus.RESUMED,
            -> reserve.reservation

            else -> null
        } ?: return null

        return PreparedNotificationCapture(
            reservation = acquired,
            route = decision.route,
            envelope = NotificationEnvelope(
                templateId = decision.route.routeId,
                templateVersion = decision.route.template.version,
                postedAtEpochMillis = postedAtEpochMillis,
                content = decision.content,
            ),
        )
    }

    /** Marks only a successful source review hand-off as captured; failures release the lease. */
    suspend fun complete(
        prepared: PreparedNotificationCapture,
        captured: Boolean,
    ): NotificationObservationWriteStatus =
        if (captured) {
            observationRepository.markCaptured(prepared.reservation, clock.instant()).status
        } else {
            observationRepository.release(prepared.reservation).status
        }

    private fun String.toCommandIdOrNull(): NotificationCaptureCommandId? = try {
        NotificationCaptureCommandId(this)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun String.toLeaseIdOrNull(): NotificationObservationLeaseId? = try {
        NotificationObservationLeaseId(this)
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        val DefaultLeaseDuration: Duration = Duration.ofMinutes(2)
    }
}

/**
 * This is only invoked after the production coordinator or the debug-only package/time sampling
 * gate succeeds. RemoteViews, actions, messages, URIs and notification keys are deliberately never
 * inspected by the extractor.
 */
internal object AndroidNotificationContentExtractor {
    fun extract(notification: Notification): NotificationContent? = try {
        val extras = notification.extras ?: return null
        NotificationContent.from(
            mapOf(
                NotificationField.TITLE to extras.boundedText(Notification.EXTRA_TITLE),
                NotificationField.TEXT to extras.boundedText(Notification.EXTRA_TEXT),
                NotificationField.SUB_TEXT to extras.boundedText(Notification.EXTRA_SUB_TEXT),
                NotificationField.BIG_TEXT to extras.boundedText(Notification.EXTRA_BIG_TEXT),
                NotificationField.SUMMARY_TEXT to extras.boundedText(Notification.EXTRA_SUMMARY_TEXT),
            ),
        )
    } catch (_: RuntimeException) {
        null
    }

    private fun Bundle.boundedText(key: String): String? {
        val value = getCharSequence(key) ?: return null
        if (value.length > NotificationContent.MAX_FIELD_CHARACTERS) return null
        return value.toString()
    }
}
