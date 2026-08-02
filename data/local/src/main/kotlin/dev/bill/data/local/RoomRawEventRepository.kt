package dev.bill.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventAppendResult
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.RawEventRepository

class RoomRawEventRepository(
    private val database: BillDatabase,
) : RawEventRepository {
    private val dao = database.rawEventDao()
    private val evidenceDao = database.sourceEvidenceDao()
    private val stagingDao = database.sourceEvidenceStagingDao()

    override suspend fun append(event: RawEvent): RawEventAppendResult {
        val entity = RawEventEntityMapper.toEntity(event)
        return try {
            database.withTransaction {
                dao.findById(entity.id)?.let { existing ->
                    val lifecycle = requireLifecycle(existing.id, existing.payloadReference)
                    return@withTransaction if (
                        existing == entity &&
                        lifecycle.canReplay(event.payloadSizeBytes) &&
                        consumeReplayStaging(entity, event.payloadSizeBytes)
                    ) {
                        RawEventAppendResult.AlreadyPresent
                    } else {
                        RawEventAppendResult.IdCollision
                    }
                }

                evidenceDao.findPayloadByPayloadId(entity.payloadReference)?.let {
                    return@withTransaction RawEventAppendResult.IdCollision
                }
                val staging = stagingForNewEvent(entity, event.payloadSizeBytes)
                    ?: if (entity.requiresStaging()) {
                        return@withTransaction RawEventAppendResult.IdCollision
                    } else {
                        null
                    }
                val existingObservationCount = dao.countObservations(
                    connectorId = entity.connectorId,
                    contentHash = entity.contentHash,
                    captureScope = entity.captureScope,
                )
                val rowId = dao.insertIfNewId(entity)
                if (rowId != InsertIgnored) {
                    evidenceDao.insertPayload(
                        SourceEvidencePayloadEntity(
                            rawEventId = event.id.value,
                            payloadId = event.payloadId.value,
                            payloadSizeBytes = event.payloadSizeBytes,
                            state = PayloadAvailable,
                            clearCommandId = null,
                            clearReason = null,
                            clearRequestedAtEpochMillis = null,
                            clearedAtEpochMillis = null,
                        ),
                    )
                    if (staging != null && !consumeActiveStaging(staging)) {
                        throw ConcurrentStagingStateChange()
                    }
                    return@withTransaction if (existingObservationCount == 0L) {
                        RawEventAppendResult.Inserted
                    } else {
                        RawEventAppendResult.DuplicateObservation(
                            existingObservationCount = existingObservationCount.toSafeCount(),
                        )
                    }
                }

                // INSERT OR IGNORE can lose a same-id race. Re-read the winner instead of
                // turning an ignored insert into a false success.
                val winner = dao.findById(entity.id)
                    ?: throw LocalDataIntegrityException("raw event")
                val lifecycle = requireLifecycle(winner.id, winner.payloadReference)
                if (
                    winner == entity &&
                    lifecycle.canReplay(event.payloadSizeBytes) &&
                    consumeReplayStaging(entity, event.payloadSizeBytes)
                ) {
                    RawEventAppendResult.AlreadyPresent
                } else {
                    RawEventAppendResult.IdCollision
                }
            }
        } catch (_: ConcurrentStagingStateChange) {
            RawEventAppendResult.IdCollision
        } catch (constraint: SQLiteConstraintException) {
            database.withTransaction {
                val existing = dao.findById(entity.id)
                if (existing != null) {
                    val lifecycle = requireLifecycle(existing.id, existing.payloadReference)
                    return@withTransaction if (
                        existing == entity &&
                        lifecycle.canReplay(event.payloadSizeBytes) &&
                        consumeReplayStaging(entity, event.payloadSizeBytes)
                    ) {
                        RawEventAppendResult.AlreadyPresent
                    } else {
                        RawEventAppendResult.IdCollision
                    }
                }
                if (evidenceDao.findPayloadByPayloadId(entity.payloadReference) != null) {
                    return@withTransaction RawEventAppendResult.IdCollision
                }
                throw constraint
            }
        }
    }

    override suspend fun findById(id: RawEventId): RawEvent? =
        database.withTransaction {
            val entity = dao.findById(id.value) ?: return@withTransaction null
            val lifecycle = requireLifecycle(entity.id, entity.payloadReference)
            RawEventEntityMapper.toDomain(entity).copy(
                payloadSizeBytes = lifecycle.payloadSizeBytes,
            )
        }

    private suspend fun requireLifecycle(
        rawEventId: String,
        payloadReference: String,
    ): SourceEvidencePayloadEntity {
        val lifecycle = evidenceDao.findPayload(rawEventId)
            ?: throw LocalDataIntegrityException("source evidence lifecycle")
        if (lifecycle.payloadId != payloadReference) {
            throw LocalDataIntegrityException("source evidence lifecycle")
        }
        return lifecycle
    }

    private suspend fun stagingForNewEvent(
        event: RawEventEntity,
        expectedPayloadSizeBytes: Long?,
    ): SourceEvidenceStagingEntity? {
        val byPayload = stagingDao.findByPayloadId(event.payloadReference)
        val byRaw = stagingDao.findByRawEventId(event.id)
        if (!event.requiresStaging()) {
            if (byPayload != null || byRaw != null) {
                throw ConcurrentStagingStateChange()
            }
            return null
        }
        if (
            byPayload == null ||
            byRaw == null ||
            byPayload != byRaw ||
            !byPayload.matchesActive(event, expectedPayloadSizeBytes)
        ) {
            return null
        }
        return byPayload
    }

    private suspend fun consumeReplayStaging(
        event: RawEventEntity,
        expectedPayloadSizeBytes: Long?,
    ): Boolean {
        val byPayload = stagingDao.findByPayloadId(event.payloadReference)
        val byRaw = stagingDao.findByRawEventId(event.id)
        if (byPayload == null && byRaw == null) return true
        if (byPayload == null || byRaw == null || byPayload != byRaw) return false
        val staging = byPayload
        if (!staging.matchesTracked(event, expectedPayloadSizeBytes)) return false
        return when (staging.state) {
            StagingActive -> consumeActiveStaging(staging)
            StagingRecovery ->
                stagingDao.deleteRecovery(staging.payloadId, staging.leaseId) == 1

            else -> false
        }
    }

    private suspend fun consumeActiveStaging(
        staging: SourceEvidenceStagingEntity,
    ): Boolean = stagingDao.deleteActive(
        payloadId = staging.payloadId,
        rawEventId = staging.rawEventId ?: return false,
        contentHash = staging.contentHash ?: return false,
        payloadSizeBytes = staging.payloadSizeBytes ?: return false,
        leaseId = staging.leaseId,
    ) == 1

    private companion object {
        const val InsertIgnored = -1L
        const val PayloadAvailable = "AVAILABLE"
        const val StagingActive = "ACTIVE"
        const val StagingRecovery = "RECOVERY"
    }
}

private class ConcurrentStagingStateChange : RuntimeException()

private fun SourceEvidencePayloadEntity.canReplay(expectedSize: Long?): Boolean =
    state == "AVAILABLE" &&
        (expectedSize == null || payloadSizeBytes == null || payloadSizeBytes == expectedSize)

/**
 * These capture methods copy external payload bytes into app-private evidence before the RawEvent
 * transaction. They must consume the matching staging lease atomically with that transaction.
 */
private fun RawEventEntity.requiresStaging(): Boolean = captureMethod in stagedCaptureMethods

private val stagedCaptureMethods = setOf(
    CaptureMethod.SHARE_TEXT.name,
    CaptureMethod.SHARE_FILE.name,
    CaptureMethod.STATEMENT_IMPORT.name,
    CaptureMethod.NOTIFICATION.name,
    CaptureMethod.PHOTO_OCR.name,
)

private fun SourceEvidenceStagingEntity.matchesActive(
    event: RawEventEntity,
    expectedPayloadSizeBytes: Long?,
): Boolean = state == "ACTIVE" && matchesTracked(event, expectedPayloadSizeBytes)

private fun SourceEvidenceStagingEntity.matchesTracked(
    event: RawEventEntity,
    expectedPayloadSizeBytes: Long?,
): Boolean =
    payloadId == event.payloadReference &&
        (rawEventId == null || rawEventId == event.id) &&
        (contentHash == null || contentHash == event.contentHash) &&
        (payloadSizeBytes == null || payloadSizeBytes == expectedPayloadSizeBytes)

private fun Long.toSafeCount(): Int {
    if (this !in 1..Int.MAX_VALUE.toLong()) {
        throw LocalDataIntegrityException("raw event observations")
    }
    return toInt()
}
