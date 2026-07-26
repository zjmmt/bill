package dev.bill.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.bill.source.contract.NotificationCaptureCommandId
import dev.bill.source.contract.NotificationObservationId
import dev.bill.source.contract.NotificationObservationLeaseId
import dev.bill.source.contract.NotificationObservationRepository
import dev.bill.source.contract.NotificationObservationReservation
import dev.bill.source.contract.NotificationObservationReserveResult
import dev.bill.source.contract.NotificationObservationReserveStatus
import dev.bill.source.contract.NotificationObservationWriteResult
import dev.bill.source.contract.NotificationObservationWriteStatus
import java.time.Duration
import java.time.Instant

/**
 * Durable notification-update dedupe. It deliberately knows nothing about Android notification
 * keys or content: the app layer supplies only an install-scoped opaque digest.
 */
class RoomNotificationObservationRepository(
    private val database: BillDatabase,
) : NotificationObservationRepository {
    private val observationDao = database.notificationObservationDao()

    override suspend fun reserve(
        requested: NotificationObservationReservation,
        now: Instant,
    ): NotificationObservationReserveResult {
        require(!requested.createdAt.isAfter(now)) {
            "Notification observation cannot be created in the future"
        }
        require(requested.expiresAt.isAfter(now)) {
            "Notification observation lease must still be active"
        }
        return try {
            database.withTransaction {
                ensureIntegrity()
                pruneExpiredObservations(now)
                val existing = observationDao.findByObservationId(requested.observationId.value)
                if (existing == null) {
                    observationDao.insert(requested.toEntity())
                    return@withTransaction reserveResult(
                        NotificationObservationReserveStatus.RESERVED,
                        requested,
                    )
                }

                validate(existing)
                when (existing.state) {
                    ActiveState -> {
                        if (existing.leaseExpiresAtEpochMillis > now.toEpochMilli()) {
                            reserveResult(NotificationObservationReserveStatus.IN_PROGRESS)
                        } else if (
                            observationDao.takeOverExpiredActive(
                                observationId = existing.observationId,
                                expectedLeaseId = existing.leaseId,
                                newLeaseId = requested.leaseId.value,
                                newExpiresAtEpochMillis = requested.expiresAt.toEpochMilli(),
                                nowEpochMillis = now.toEpochMilli(),
                            ) == 1
                        ) {
                            reserveResult(
                                NotificationObservationReserveStatus.RESUMED,
                                requested.copy(
                                    commandId = NotificationCaptureCommandId(existing.commandId),
                                    createdAt = Instant.ofEpochMilli(existing.createdAtEpochMillis),
                                ),
                            )
                        } else {
                            reserveResult(NotificationObservationReserveStatus.IN_PROGRESS)
                        }
                    }

                    CapturedState -> reserveResult(
                        NotificationObservationReserveStatus.ALREADY_CAPTURED,
                    )

                    else -> throw LocalDataIntegrityException("notification observations")
                }
            }
        } catch (_: SQLiteConstraintException) {
            reserveResult(NotificationObservationReserveStatus.ID_COLLISION)
        }
    }

    override suspend fun markCaptured(
        reservation: NotificationObservationReservation,
        capturedAt: Instant,
    ): NotificationObservationWriteResult = database.withTransaction {
        ensureIntegrity()
        val current = observationDao.findByObservationId(reservation.observationId.value)
            ?: return@withTransaction writeResult(NotificationObservationWriteStatus.ALREADY_APPLIED)
        validate(current)
        if (
            current.state == CapturedState &&
            current.commandId == reservation.commandId.value
        ) {
            return@withTransaction writeResult(NotificationObservationWriteStatus.ALREADY_APPLIED)
        }
        if (!current.matchesActive(reservation)) {
            return@withTransaction writeResult(NotificationObservationWriteStatus.INVALID_STATE)
        }
        if (
            observationDao.markCaptured(
                observationId = reservation.observationId.value,
                commandId = reservation.commandId.value,
                leaseId = reservation.leaseId.value,
                capturedAtEpochMillis = maxOf(capturedAt, reservation.createdAt).toEpochMilli(),
            ) != 1
        ) {
            return@withTransaction writeResult(NotificationObservationWriteStatus.INVALID_STATE)
        }
        writeResult(NotificationObservationWriteStatus.APPLIED)
    }

    override suspend fun release(
        reservation: NotificationObservationReservation,
    ): NotificationObservationWriteResult = database.withTransaction {
        ensureIntegrity()
        val current = observationDao.findByObservationId(reservation.observationId.value)
            ?: return@withTransaction writeResult(NotificationObservationWriteStatus.ALREADY_APPLIED)
        validate(current)
        if (
            current.state == CapturedState &&
            current.commandId == reservation.commandId.value
        ) {
            return@withTransaction writeResult(NotificationObservationWriteStatus.ALREADY_APPLIED)
        }
        if (!current.matchesActive(reservation)) {
            return@withTransaction writeResult(NotificationObservationWriteStatus.INVALID_STATE)
        }
        if (
            observationDao.deleteActive(
                observationId = reservation.observationId.value,
                commandId = reservation.commandId.value,
                leaseId = reservation.leaseId.value,
            ) != 1
        ) {
            return@withTransaction writeResult(NotificationObservationWriteStatus.INVALID_STATE)
        }
        writeResult(NotificationObservationWriteStatus.APPLIED)
    }

    private fun validate(entity: NotificationObservationEntity) {
        try {
            NotificationObservationId(entity.observationId)
            NotificationCaptureCommandId(entity.commandId)
            NotificationObservationLeaseId(entity.leaseId)
            val createdAt = Instant.ofEpochMilli(entity.createdAtEpochMillis)
            val expiresAt = Instant.ofEpochMilli(entity.leaseExpiresAtEpochMillis)
            require(expiresAt.isAfter(createdAt))
            when (entity.state) {
                ActiveState -> require(entity.capturedAtEpochMillis == null)
                CapturedState -> {
                    val capturedAt = Instant.ofEpochMilli(requireNotNull(entity.capturedAtEpochMillis))
                    require(!capturedAt.isBefore(createdAt))
                }

                else -> error("Unknown notification observation state")
            }
        } catch (_: RuntimeException) {
            throw LocalDataIntegrityException("notification observations")
        }
    }

    private suspend fun ensureIntegrity() {
        if (observationDao.integrityIssueCount() != 0L) {
            throw LocalDataIntegrityException("notification observations")
        }
    }

    /**
     * Recovery state must not create a background maintenance job. A later candidate callback
     * performs a small indexed cleanup instead. Retaining abandoned leases for the same period as
     * captured observations preserves command recovery across ordinary process deaths while
     * preventing an unbounded journal when no later update arrives.
     */
    private suspend fun pruneExpiredObservations(now: Instant) {
        val retentionStart = now.minus(ObservationRetention).toEpochMilli()
        observationDao.deleteExpiredActiveBefore(
            leaseExpiresBeforeEpochMillis = retentionStart,
            limit = MaxPrunedRowsPerReserve,
        )
        observationDao.deleteCapturedBefore(
            capturedBeforeEpochMillis = retentionStart,
            limit = MaxPrunedRowsPerReserve,
        )
    }

    private companion object {
        const val ActiveState = "ACTIVE"
        const val CapturedState = "CAPTURED"
        const val MaxPrunedRowsPerReserve = 64
        val ObservationRetention: Duration = Duration.ofDays(90)
    }
}

private fun NotificationObservationReservation.toEntity() = NotificationObservationEntity(
    observationId = observationId.value,
    commandId = commandId.value,
    state = "ACTIVE",
    leaseId = leaseId.value,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    leaseExpiresAtEpochMillis = expiresAt.toEpochMilli(),
    capturedAtEpochMillis = null,
)

private fun NotificationObservationEntity.matchesActive(
    reservation: NotificationObservationReservation,
): Boolean =
    state == "ACTIVE" &&
        observationId == reservation.observationId.value &&
        commandId == reservation.commandId.value &&
        leaseId == reservation.leaseId.value

private fun reserveResult(
    status: NotificationObservationReserveStatus,
    reservation: NotificationObservationReservation? = null,
) = NotificationObservationReserveResult(status, reservation)

private fun writeResult(status: NotificationObservationWriteStatus) =
    NotificationObservationWriteResult(status)
