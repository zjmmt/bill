package dev.bill.source.contract

import java.time.Instant

/**
 * A one-way, install-scoped digest of the Android notification identity. The source notification
 * key, package metadata and body text must never be persisted here.
 */
@JvmInline
value class NotificationObservationId(val value: String) {
    init {
        require(value.matches(HexDigest)) {
            "Notification observation id must be a lowercase SHA-256-sized digest"
        }
    }

    override fun toString(): String = "NotificationObservationId(redacted=true)"

    private companion object {
        val HexDigest = Regex("[0-9a-f]{64}")
    }
}

/** A stable local command used to make a recovered notification capture idempotent. */
@JvmInline
value class NotificationCaptureCommandId(val value: String) {
    init {
        require(value.matches(OpaqueCommand)) {
            "Notification capture command id must be an opaque ASCII token"
        }
    }

    override fun toString(): String = "NotificationCaptureCommandId(redacted=true)"

    private companion object {
        // `notification-` is prepended when creating the RawEvent and Payload ids.
        val OpaqueCommand = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,115}")
    }
}

/** Owns a short-lived attempt to ingest one notification observation. */
@JvmInline
value class NotificationObservationLeaseId(val value: String) {
    init {
        require(value.matches(OpaqueLease)) {
            "Notification observation lease id must be an opaque ASCII token"
        }
    }

    override fun toString(): String = "NotificationObservationLeaseId(redacted=true)"

    private companion object {
        val OpaqueLease = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

/**
 * The durable minimum needed to suppress notification updates and resume after process death.
 * It intentionally contains no raw Android notification key, package, channel, category or body.
 */
data class NotificationObservationReservation(
    val observationId: NotificationObservationId,
    val commandId: NotificationCaptureCommandId,
    val leaseId: NotificationObservationLeaseId,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(expiresAt.isAfter(createdAt)) {
            "Notification observation lease must expire after it is created"
        }
    }
}

enum class NotificationObservationReserveStatus {
    RESERVED,
    RESUMED,
    ALREADY_CAPTURED,
    IN_PROGRESS,
    ID_COLLISION,
    DISABLED,
}

data class NotificationObservationReserveResult(
    val status: NotificationObservationReserveStatus,
    val reservation: NotificationObservationReservation? = null,
) {
    init {
        require(
            (status == NotificationObservationReserveStatus.RESERVED ||
                status == NotificationObservationReserveStatus.RESUMED) ==
                (reservation != null),
        ) {
            "Only an acquired notification observation returns a lease"
        }
    }
}

enum class NotificationObservationWriteStatus {
    APPLIED,
    ALREADY_APPLIED,
    INVALID_STATE,
    DISABLED,
}

data class NotificationObservationWriteResult(
    val status: NotificationObservationWriteStatus,
)

/**
 * Persists only a redacted observation digest and a local idempotency command. The implementation
 * must preserve the command id when an expired lease is resumed, so a process restart cannot
 * create a second RawEvent for the same Android notification update.
 */
interface NotificationObservationRepository {
    suspend fun reserve(
        requested: NotificationObservationReservation,
        now: Instant,
    ): NotificationObservationReserveResult

    suspend fun markCaptured(
        reservation: NotificationObservationReservation,
        capturedAt: Instant,
    ): NotificationObservationWriteResult

    suspend fun release(
        reservation: NotificationObservationReservation,
    ): NotificationObservationWriteResult

    data object Disabled : NotificationObservationRepository {
        override suspend fun reserve(
            requested: NotificationObservationReservation,
            now: Instant,
        ): NotificationObservationReserveResult = NotificationObservationReserveResult(
            status = NotificationObservationReserveStatus.DISABLED,
        )

        override suspend fun markCaptured(
            reservation: NotificationObservationReservation,
            capturedAt: Instant,
        ): NotificationObservationWriteResult = NotificationObservationWriteResult(
            NotificationObservationWriteStatus.DISABLED,
        )

        override suspend fun release(
            reservation: NotificationObservationReservation,
        ): NotificationObservationWriteResult = NotificationObservationWriteResult(
            NotificationObservationWriteStatus.DISABLED,
        )
    }
}
