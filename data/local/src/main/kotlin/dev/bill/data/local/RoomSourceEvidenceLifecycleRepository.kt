package dev.bill.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.bill.core.domain.AuditAction
import dev.bill.core.domain.AuditRecord
import dev.bill.core.domain.CommandId
import dev.bill.core.domain.RepositoryWriteResult
import dev.bill.core.domain.RepositoryWriteStatus
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import dev.bill.source.review.EvidenceClearReason
import dev.bill.source.review.EvidenceClearRequestResult
import dev.bill.source.review.EvidenceClearRequestStatus
import dev.bill.source.review.EvidenceClearWork
import dev.bill.source.review.EvidencePayloadState
import dev.bill.source.review.SourceEvidenceCursor
import dev.bill.source.review.SourceEvidenceItem
import dev.bill.source.review.SourceEvidenceLifecycleRepository
import dev.bill.source.review.SourceEvidenceOverview
import dev.bill.source.review.SourceEvidencePage
import dev.bill.source.review.SourceEvidencePolicy
import dev.bill.source.review.SourceEvidenceStorageSummary
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class RoomSourceEvidenceLifecycleRepository(
    private val database: BillDatabase,
) : SourceEvidenceLifecycleRepository {
    private val evidenceDao = database.sourceEvidenceDao()
    private val ledgerDao = database.ledgerDao()

    override fun observeOverview(): Flow<SourceEvidenceOverview> = combine(
        evidenceDao.observePolicy(),
        evidenceDao.observeStorageSummary(),
        evidenceDao.observeEvidenceIntegrityIssueCount(),
    ) { policy, storage, integrityIssueCount ->
        if (integrityIssueCount != 0L) {
            throw LocalDataIntegrityException("source evidence lifecycle")
        }
        SourceEvidenceOverview(
            policy = mapPolicy(
                policy ?: throw LocalDataIntegrityException("source evidence policy"),
            ),
            storage = mapStorage(storage),
        )
    }

    override suspend fun getOverview(): SourceEvidenceOverview =
        database.withTransaction {
            ensureIntegrity()
            SourceEvidenceOverview(
                policy = mapPolicy(
                    evidenceDao.findPolicy()
                        ?: throw LocalDataIntegrityException("source evidence policy"),
                ),
                storage = mapStorage(evidenceDao.storageSummary()),
            )
        }

    override suspend fun page(
        limit: Int,
        cursor: SourceEvidenceCursor?,
    ): SourceEvidencePage {
        require(limit in 1..MaxPageSize)
        val cursorMillis = cursor?.capturedAt?.toEpochMilli()
        val rows = database.withTransaction {
            ensureIntegrity()
            evidenceDao.pageRows(
                limit = limit + 1,
                beforeCapturedAtEpochMillis = cursorMillis,
                beforeRawEventId = cursor?.rawEventId?.value,
            )
        }
        val hasMore = rows.size > limit
        val items = rows.take(limit).map(::mapItem)
        val nextCursor = if (hasMore) {
            items.lastOrNull()?.let { item ->
                SourceEvidenceCursor(
                    capturedAt = item.capturedAt,
                    rawEventId = item.rawEventId,
                )
            }
        } else {
            null
        }
        return SourceEvidencePage(items = items, nextCursor = nextCursor)
    }

    override suspend fun find(rawEventId: RawEventId): SourceEvidenceItem? =
        database.withTransaction {
            ensureIntegrity()
            evidenceDao.findRow(rawEventId.value)?.let(::mapItem)
        }

    override suspend fun findByPayloadId(payloadId: PayloadId): SourceEvidenceItem? =
        database.withTransaction {
            ensureIntegrity()
            val lifecycle = evidenceDao.findPayloadByPayloadId(payloadId.value)
                ?: return@withTransaction null
            evidenceDao.findRow(lifecycle.rawEventId)?.let(::mapItem)
                ?: throw LocalDataIntegrityException("source evidence lifecycle")
        }

    override suspend fun updateRetentionPolicy(
        commandId: CommandId,
        retentionDays: Int?,
        updatedAt: Instant,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        SourceEvidencePolicy(retentionDays = retentionDays, updatedAt = updatedAt)
        val updatedAtMillis = updatedAt.toEpochMilli()
        if (!auditRecord.matchesPolicy(commandId, updatedAt)) {
            return result(RepositoryWriteStatus.INVALID_STATE)
        }
        val fingerprint = EvidenceFingerprint()
            .add(UpdateRetentionOperation)
            .addNullable(retentionDays?.toString())
            .finish()
        return try {
            database.withTransaction {
                ensureIntegrity()
                ledgerDao.findCommandReceipt(commandId.value)?.let { existing ->
                    return@withTransaction if (
                        existing.operation == UpdateRetentionOperation &&
                        existing.targetId == PolicyEntityId &&
                        existing.resultEntityId == PolicyEntityId &&
                        existing.payloadFingerprint == fingerprint
                    ) {
                        result(RepositoryWriteStatus.ALREADY_APPLIED, existing.resultEntityId)
                    } else {
                        result(RepositoryWriteStatus.COMMAND_COLLISION)
                    }
                }
                if (
                    evidenceDao.findPolicy() == null ||
                    ledgerDao.countAuditEvents(listOf(auditRecord.id.value)) != 0 ||
                    evidenceDao.updateRetentionPolicy(retentionDays, updatedAtMillis) != 1
                ) {
                    return@withTransaction result(RepositoryWriteStatus.INVALID_STATE)
                }
                ledgerDao.insertAuditEvents(
                    listOf(LedgerEntityMapper.auditToEntity(auditRecord)),
                )
                ledgerDao.insertCommandReceipt(
                    CommandReceiptEntity(
                        commandId = commandId.value,
                        operation = UpdateRetentionOperation,
                        targetId = PolicyEntityId,
                        resultEntityId = PolicyEntityId,
                        payloadFingerprint = fingerprint,
                        appliedAtEpochMillis = updatedAtMillis,
                    ),
                )
                result(RepositoryWriteStatus.APPLIED, PolicyEntityId)
            }
        } catch (_: SQLiteConstraintException) {
            result(RepositoryWriteStatus.COMMAND_COLLISION)
        }
    }

    override suspend fun requestClear(
        commandId: CommandId,
        rawEventId: RawEventId,
        reason: EvidenceClearReason,
        requestedAt: Instant,
        auditRecord: AuditRecord,
    ): EvidenceClearRequestResult {
        if (!auditRecord.matchesClearRequest(commandId, rawEventId, requestedAt)) {
            return clearResult(EvidenceClearRequestStatus.INVALID_STATE)
        }
        val requestedAtMillis = requestedAt.toEpochMilli()
        val fingerprint = clearFingerprint(rawEventId, reason)
        return try {
            database.withTransaction {
                ensureIntegrity()
                ledgerDao.findCommandReceipt(commandId.value)?.let { existing ->
                    if (
                        existing.operation != RequestClearOperation ||
                        existing.targetId != rawEventId.value ||
                        existing.resultEntityId != rawEventId.value ||
                        existing.payloadFingerprint != fingerprint
                    ) {
                        return@withTransaction clearResult(
                            EvidenceClearRequestStatus.COMMAND_COLLISION,
                        )
                    }
                    val row = evidenceDao.findRow(rawEventId.value)
                        ?: return@withTransaction clearResult(
                            EvidenceClearRequestStatus.INVALID_STATE,
                        )
                    return@withTransaction replayClearRequest(row, commandId)
                }

                val row = evidenceDao.findRow(rawEventId.value)
                    ?: return@withTransaction clearResult(EvidenceClearRequestStatus.NOT_FOUND)
                val item = mapItem(row)
                when (item.state) {
                    EvidencePayloadState.CLEAR_PENDING ->
                        return@withTransaction clearResult(
                            EvidenceClearRequestStatus.INVALID_STATE,
                        )

                    EvidencePayloadState.CLEARED -> {
                        ledgerDao.insertCommandReceipt(
                            clearReceipt(
                                commandId = commandId,
                                rawEventId = rawEventId,
                                fingerprint = fingerprint,
                                appliedAtMillis = requestedAtMillis,
                            ),
                        )
                        return@withTransaction clearResult(
                            EvidenceClearRequestStatus.ALREADY_CLEARED,
                        )
                    }

                    EvidencePayloadState.AVAILABLE -> Unit
                }

                if (
                    ledgerDao.countAuditEvents(listOf(auditRecord.id.value)) != 0 ||
                    evidenceDao.requestClear(
                        rawEventId = rawEventId.value,
                        commandId = commandId.value,
                        reason = reason.name,
                        requestedAtEpochMillis = requestedAtMillis,
                    ) != 1
                ) {
                    return@withTransaction clearResult(
                        EvidenceClearRequestStatus.INVALID_STATE,
                    )
                }
                evidenceDao.dismissPendingProposals(rawEventId.value)
                ledgerDao.insertAuditEvents(
                    listOf(LedgerEntityMapper.auditToEntity(auditRecord)),
                )
                ledgerDao.insertCommandReceipt(
                    clearReceipt(
                        commandId = commandId,
                        rawEventId = rawEventId,
                        fingerprint = fingerprint,
                        appliedAtMillis = requestedAtMillis,
                    ),
                )
                val updated = evidenceDao.findRow(rawEventId.value)
                    ?: throw LocalDataIntegrityException("source evidence lifecycle")
                EvidenceClearRequestResult(
                    status = EvidenceClearRequestStatus.REQUESTED,
                    work = mapClearWork(updated),
                )
            }
        } catch (_: SQLiteConstraintException) {
            clearResult(EvidenceClearRequestStatus.COMMAND_COLLISION)
        }
    }

    override suspend fun markCleared(
        work: EvidenceClearWork,
        clearedAt: Instant,
        auditRecord: AuditRecord,
    ): RepositoryWriteResult {
        if (
            clearedAt.isBefore(work.requestedAt) ||
            !auditRecord.matchesCleared(work, clearedAt)
        ) {
            return result(RepositoryWriteStatus.INVALID_STATE)
        }
        val clearedAtMillis = clearedAt.toEpochMilli()
        return try {
            database.withTransaction {
                ensureIntegrity()
                val row = evidenceDao.findRow(work.rawEventId.value)
                    ?: return@withTransaction result(RepositoryWriteStatus.NOT_FOUND)
                val item = mapItem(row)
                if (item.state == EvidencePayloadState.CLEARED) {
                    return@withTransaction if (
                        row.matches(work) &&
                        ledgerDao.findAuditEvent(auditRecord.id.value) ==
                        LedgerEntityMapper.auditToEntity(auditRecord)
                    ) {
                        result(RepositoryWriteStatus.ALREADY_APPLIED, work.rawEventId.value)
                    } else {
                        result(RepositoryWriteStatus.INVALID_STATE)
                    }
                }
                if (
                    item.state != EvidencePayloadState.CLEAR_PENDING ||
                    !row.matches(work) ||
                    ledgerDao.countAuditEvents(listOf(auditRecord.id.value)) != 0 ||
                    evidenceDao.markCleared(
                        rawEventId = work.rawEventId.value,
                        commandId = work.commandId.value,
                        clearedAtEpochMillis = clearedAtMillis,
                    ) != 1
                ) {
                    return@withTransaction result(RepositoryWriteStatus.INVALID_STATE)
                }
                ledgerDao.insertAuditEvents(
                    listOf(LedgerEntityMapper.auditToEntity(auditRecord)),
                )
                result(RepositoryWriteStatus.APPLIED, work.rawEventId.value)
            }
        } catch (_: SQLiteConstraintException) {
            result(RepositoryWriteStatus.COMMAND_COLLISION)
        }
    }

    override suspend fun pendingClearWork(limit: Int): List<EvidenceClearWork> {
        require(limit in 1..MaxBatchSize)
        return database.withTransaction {
            ensureIntegrity()
            evidenceDao.pendingClearRows(limit).map(::mapClearWork)
        }
    }

    override suspend fun findPendingClearWork(
        rawEventId: RawEventId,
    ): EvidenceClearWork? =
        database.withTransaction {
            ensureIntegrity()
            val row = evidenceDao.findRow(rawEventId.value)
                ?: return@withTransaction null
            if (mapItem(row).state != EvidencePayloadState.CLEAR_PENDING) {
                return@withTransaction null
            }
            mapClearWork(row)
        }

    override suspend fun retentionCandidates(
        capturedBefore: Instant,
        limit: Int,
    ): List<SourceEvidenceItem> {
        require(limit in 1..MaxBatchSize)
        val capturedBeforeMillis = capturedBefore.toEpochMilli()
        return database.withTransaction {
            ensureIntegrity()
            evidenceDao.retentionCandidateRows(capturedBeforeMillis, limit).map(::mapItem)
        }
    }

    override suspend fun capacityCandidates(limit: Int): List<SourceEvidenceItem> {
        require(limit in 1..MaxBatchSize)
        return database.withTransaction {
            ensureIntegrity()
            evidenceDao.capacityCandidateRows(limit).map(::mapItem)
        }
    }

    override suspend fun unknownSizeItems(limit: Int): List<SourceEvidenceItem> {
        require(limit in 1..MaxBatchSize)
        return database.withTransaction {
            ensureIntegrity()
            evidenceDao.unknownSizeRows(limit).map(::mapItem)
        }
    }

    override suspend fun recordMeasuredSize(
        rawEventId: RawEventId,
        payloadSizeBytes: Long,
    ): RepositoryWriteResult {
        require(payloadSizeBytes >= 0L)
        return database.withTransaction {
            ensureIntegrity()
            val row = evidenceDao.findRow(rawEventId.value)
                ?: return@withTransaction result(RepositoryWriteStatus.NOT_FOUND)
            val item = mapItem(row)
            if (item.state != EvidencePayloadState.AVAILABLE) {
                return@withTransaction result(RepositoryWriteStatus.INVALID_STATE)
            }
            item.payloadSizeBytes?.let { current ->
                return@withTransaction if (current == payloadSizeBytes) {
                    result(RepositoryWriteStatus.ALREADY_APPLIED, rawEventId.value)
                } else {
                    result(RepositoryWriteStatus.INVALID_STATE)
                }
            }
            if (evidenceDao.recordMeasuredSize(rawEventId.value, payloadSizeBytes) != 1) {
                return@withTransaction result(RepositoryWriteStatus.INVALID_STATE)
            }
            result(RepositoryWriteStatus.APPLIED, rawEventId.value)
        }
    }

    private suspend fun ensureIntegrity() {
        if (evidenceDao.evidenceIntegrityIssueCount() != 0L) {
            throw LocalDataIntegrityException("source evidence lifecycle")
        }
    }

    private fun replayClearRequest(
        row: SourceEvidenceRow,
        commandId: CommandId,
    ): EvidenceClearRequestResult {
        val item = mapItem(row)
        return when {
            item.state == EvidencePayloadState.CLEAR_PENDING &&
                row.clearCommandId == commandId.value ->
                EvidenceClearRequestResult(
                    status = EvidenceClearRequestStatus.ALREADY_PENDING,
                    work = mapClearWork(row),
                )

            item.state == EvidencePayloadState.CLEARED &&
                row.clearCommandId == commandId.value ->
                clearResult(EvidenceClearRequestStatus.ALREADY_CLEARED)

            else -> clearResult(EvidenceClearRequestStatus.INVALID_STATE)
        }
    }

    private fun mapPolicy(entity: SourceEvidencePolicyEntity): SourceEvidencePolicy = try {
        require(entity.id == PolicyRowId)
        SourceEvidencePolicy(
            retentionDays = entity.retentionDays,
            updatedAt = Instant.ofEpochMilli(entity.updatedAtEpochMillis),
        )
    } catch (_: RuntimeException) {
        throw LocalDataIntegrityException("source evidence policy")
    }

    private fun mapStorage(row: SourceEvidenceStorageRow): SourceEvidenceStorageSummary = try {
        SourceEvidenceStorageSummary(
            storedCount = row.storedCount,
            storedBytes = row.storedBytes,
            unknownSizeCount = row.unknownSizeCount,
            clearPendingCount = row.clearPendingCount,
            clearedCount = row.clearedCount,
        )
    } catch (_: RuntimeException) {
        throw LocalDataIntegrityException("source evidence storage")
    }

    private fun mapItem(row: SourceEvidenceRow): SourceEvidenceItem = try {
        require(row.payloadId == row.rawPayloadReference)
        require(row.pendingReviewCount >= 0L)
        val state = enumValueOf<EvidencePayloadState>(row.state)
        val reason = row.clearReason?.let { enumValueOf<EvidenceClearReason>(it) }
        when (state) {
            EvidencePayloadState.AVAILABLE -> require(
                row.clearCommandId == null &&
                    reason == null &&
                    row.clearRequestedAtEpochMillis == null &&
                    row.clearedAtEpochMillis == null,
            )

            EvidencePayloadState.CLEAR_PENDING -> require(
                row.clearCommandId != null &&
                    reason != null &&
                    row.clearRequestedAtEpochMillis != null &&
                    row.clearedAtEpochMillis == null &&
                    row.pendingReviewCount == 0L,
            )

            EvidencePayloadState.CLEARED -> require(
                row.clearCommandId != null &&
                    reason != null &&
                    row.clearRequestedAtEpochMillis != null &&
                    row.clearedAtEpochMillis != null &&
                    row.pendingReviewCount == 0L,
            )
        }
        SourceEvidenceItem(
            rawEventId = RawEventId(row.rawEventId),
            payloadId = PayloadId(row.payloadId),
            sourceFamily = enumValueOf<SourceFamily>(row.sourceFamily),
            captureMethod = enumValueOf<CaptureMethod>(row.captureMethod),
            capturedAt = Instant.ofEpochMilli(row.capturedAtEpochMillis),
            payloadSizeBytes = row.payloadSizeBytes,
            state = state,
            hasPendingReview = row.pendingReviewCount > 0L,
            clearReason = reason,
            clearedAt = row.clearedAtEpochMillis?.let(Instant::ofEpochMilli),
        )
    } catch (_: RuntimeException) {
        throw LocalDataIntegrityException("source evidence lifecycle")
    }

    private fun mapClearWork(row: SourceEvidenceRow): EvidenceClearWork {
        val item = mapItem(row)
        if (
            item.state != EvidencePayloadState.CLEAR_PENDING &&
            item.state != EvidencePayloadState.CLEARED
        ) {
            throw LocalDataIntegrityException("source evidence clear work")
        }
        return try {
            EvidenceClearWork(
                rawEventId = item.rawEventId,
                payloadId = item.payloadId,
                commandId = CommandId(requireNotNull(row.clearCommandId)),
                reason = requireNotNull(item.clearReason),
                requestedAt = Instant.ofEpochMilli(
                    requireNotNull(row.clearRequestedAtEpochMillis),
                ),
            )
        } catch (_: RuntimeException) {
            throw LocalDataIntegrityException("source evidence clear work")
        }
    }

    private fun clearReceipt(
        commandId: CommandId,
        rawEventId: RawEventId,
        fingerprint: String,
        appliedAtMillis: Long,
    ) = CommandReceiptEntity(
        commandId = commandId.value,
        operation = RequestClearOperation,
        targetId = rawEventId.value,
        resultEntityId = rawEventId.value,
        payloadFingerprint = fingerprint,
        appliedAtEpochMillis = appliedAtMillis,
    )

    private fun clearFingerprint(
        rawEventId: RawEventId,
        reason: EvidenceClearReason,
    ): String = EvidenceFingerprint()
        .add(RequestClearOperation)
        .add(rawEventId.value)
        .add(reason.name)
        .finish()

    private fun AuditRecord.matchesPolicy(
        expectedCommandId: CommandId,
        expectedAt: Instant,
    ): Boolean =
        commandId == expectedCommandId &&
            action == AuditAction.SOURCE_EVIDENCE_RETENTION_CHANGED &&
            entityType == PolicyEntityType &&
            entityId == PolicyEntityId &&
            occurredAt == expectedAt

    private fun AuditRecord.matchesClearRequest(
        expectedCommandId: CommandId,
        expectedRawEventId: RawEventId,
        expectedAt: Instant,
    ): Boolean =
        commandId == expectedCommandId &&
            action == AuditAction.SOURCE_EVIDENCE_CLEAR_REQUESTED &&
            entityType == EvidenceEntityType &&
            entityId == expectedRawEventId.value &&
            occurredAt == expectedAt

    private fun AuditRecord.matchesCleared(
        work: EvidenceClearWork,
        expectedAt: Instant,
    ): Boolean =
        commandId == work.commandId &&
            action == AuditAction.SOURCE_EVIDENCE_CLEARED &&
            entityType == EvidenceEntityType &&
            entityId == work.rawEventId.value &&
            occurredAt == expectedAt

    private fun SourceEvidenceRow.matches(work: EvidenceClearWork): Boolean =
        rawEventId == work.rawEventId.value &&
            payloadId == work.payloadId.value &&
            clearCommandId == work.commandId.value &&
            clearReason == work.reason.name &&
            clearRequestedAtEpochMillis == work.requestedAt.toEpochMilli()

    private companion object {
        const val MaxPageSize = 100
        const val MaxBatchSize = 512
        const val PolicyRowId = 1
        const val PolicyEntityId = "default"
        const val PolicyEntityType = "source_evidence_policy"
        const val EvidenceEntityType = "source_evidence"
        const val UpdateRetentionOperation = "UPDATE_SOURCE_EVIDENCE_RETENTION"
        const val RequestClearOperation = "REQUEST_SOURCE_EVIDENCE_CLEAR"
    }
}

private class EvidenceFingerprint {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun add(value: String): EvidenceFingerprint = apply {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    fun addNullable(value: String?): EvidenceFingerprint =
        if (value == null) add("<null>") else add("<value>").add(value)

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private fun clearResult(status: EvidenceClearRequestStatus) =
    EvidenceClearRequestResult(status = status)

private fun result(
    status: RepositoryWriteStatus,
    entityId: String? = null,
) = RepositoryWriteResult(status = status, entityId = entityId)
