package dev.bill.application

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.RawEventRepository
import dev.bill.source.contract.StatementImportBatchId
import dev.bill.source.contract.StatementImportBatchOpenResult
import dev.bill.source.contract.StatementImportBatchRecord
import dev.bill.source.contract.StatementImportBatchRefreshResult
import dev.bill.source.contract.StatementImportBatchRepository
import dev.bill.source.contract.StatementImportBatchRequest
import dev.bill.source.contract.StatementImportRowRecord
import dev.bill.source.contract.StatementImportRowState
import dev.bill.source.contract.StatementImportRowWriteResult
import dev.bill.source.contract.TextEvidenceMediaTypes
import dev.bill.source.genericdelimited.DelimitedDelimiter
import dev.bill.source.genericdelimited.DelimitedDocument
import dev.bill.source.genericdelimited.DelimitedReadError
import dev.bill.source.genericdelimited.DelimitedReadLimits
import dev.bill.source.genericdelimited.DelimitedReadResult
import dev.bill.source.genericdelimited.DelimitedStatementMapper
import dev.bill.source.genericdelimited.DelimitedStatementMapping
import dev.bill.source.genericdelimited.DelimitedStatementRowEvidenceCodec
import dev.bill.source.genericdelimited.DelimitedTextReader
import dev.bill.source.genericdelimited.GenericDelimitedStatementParser
import dev.bill.source.genericdelimited.StatementMappingError
import dev.bill.source.genericdelimited.StatementRowMappingResult
import dev.bill.source.pipeline.SourceIngestionService
import dev.bill.source.review.EvidenceStagingStore
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SelectedDelimitedStatementEvidence(
    val mediaType: String,
    /** Ownership transfers to preview(); it is wiped before preview() returns. */
    val bytes: ByteArray,
)

enum class StatementImportPreviewError {
    UNAVAILABLE,
    UNSUPPORTED_MEDIA_TYPE,
    DELIMITER_MISMATCH,
    NO_DATA_ROWS,
}

sealed interface StatementImportPreviewResult {
    data class Ready(val session: LocalDelimitedStatementSession) :
        StatementImportPreviewResult

    data class InvalidDocument(val error: DelimitedReadError) :
        StatementImportPreviewResult

    data class Failure(val error: StatementImportPreviewError) :
        StatementImportPreviewResult
}

/**
 * A process-local, bounded view of one user-selected file. It never contains a URI or filename,
 * and closing it drops the only application-layer reference to decoded rows.
 */
class LocalDelimitedStatementSession(
    document: DelimitedDocument,
) : AutoCloseable {
    init {
        require(document.header.size <= DelimitedReadLimits.DEFAULT_MAX_COLUMNS)
        require(document.header.all { it.length <= DelimitedReadLimits.DEFAULT_MAX_CELL_CHARS })
        require(
            document.header.sumOf(String::length) <=
                DelimitedReadLimits.DEFAULT_MAX_RECORD_CHARS,
        )
        require(document.rows.size < DelimitedReadLimits.DEFAULT_MAX_RECORDS)
        require(
            document.rows.zipWithNext().all { (previous, next) ->
                previous.tableRowIndex < next.tableRowIndex
            },
        ) {
            "Statement row indices must be strictly increasing"
        }
        require(
            document.rows.all { row ->
                row.cells.size <= DelimitedReadLimits.DEFAULT_MAX_COLUMNS &&
                    row.cells.all {
                        it.length <= DelimitedReadLimits.DEFAULT_MAX_CELL_CHARS
                    } &&
                    row.cells.sumOf(String::length) <=
                    DelimitedReadLimits.DEFAULT_MAX_RECORD_CHARS
            },
        ) {
            "Statement rows must stay within the default local import bounds"
        }
    }

    private var currentDocument: DelimitedDocument? = document
    private var currentHeader: List<String> = document.header.toList()
    private var currentPreviewRows: List<StatementImportPreviewRow> = document.rows
        .take(MAX_PREVIEW_ROWS)
        .map { StatementImportPreviewRow(it.tableRowIndex, it.cells.toList()) }

    val fileHash: EvidenceHash = document.fileHash
    val delimiter: DelimitedDelimiter = document.delimiter
    val header: List<String>
        get() = currentHeader
    val previewRows: List<StatementImportPreviewRow>
        get() = currentPreviewRows
    val totalDataRowCount: Int = document.rows.size

    internal fun requireDocument(): DelimitedDocument =
        checkNotNull(currentDocument) { "Statement import preview has been closed" }

    override fun close() {
        currentDocument = null
        currentHeader = emptyList()
        currentPreviewRows = emptyList()
    }

    override fun toString(): String =
        "LocalDelimitedStatementSession(rows=$totalDataRowCount, redacted=true)"

    private companion object {
        const val MAX_PREVIEW_ROWS = 20
    }
}

data class StatementImportPreviewRow(
    val tableRowIndex: Int,
    val cells: List<String>,
) {
    override fun toString(): String =
        "StatementImportPreviewRow(row=$tableRowIndex, redacted=true)"
}

data class StatementImportMappedPreviewRow(
    val tableRowIndex: Int,
    val error: StatementMappingError?,
)

data class StatementImportMappingPreview(
    val validRowCount: Int,
    val invalidRowCount: Int,
    val previewRows: List<StatementImportMappedPreviewRow>,
)

sealed interface StatementImportMappingPreviewResult {
    data class Ready(val preview: StatementImportMappingPreview) :
        StatementImportMappingPreviewResult

    data class InvalidMapping(val error: StatementMappingError) :
        StatementImportMappingPreviewResult

    data object ClosedSession : StatementImportMappingPreviewResult
}

data class StatementImportProgress(
    val batchId: StatementImportBatchId,
    val processedRowCount: Int,
    val totalRowCount: Int,
)

sealed interface StatementImportConfirmationResult {
    data class Completed(
        val batch: StatementImportBatchRecord,
        val resumedRowCount: Int,
    ) : StatementImportConfirmationResult

    data class Interrupted(
        val batchId: StatementImportBatchId?,
        val tableRowIndex: Int?,
        val error: SourceCaptureError?,
    ) : StatementImportConfirmationResult

    data class InvalidMapping(val error: StatementMappingError) :
        StatementImportConfirmationResult

    data object ClosedSession : StatementImportConfirmationResult
    data object BatchIdentityCollision : StatementImportConfirmationResult
    data class RowIdentityCollision(val tableRowIndex: Int) :
        StatementImportConfirmationResult
}

interface DelimitedStatementImport {
    fun preview(
        evidence: SelectedDelimitedStatementEvidence,
        delimiter: DelimitedDelimiter,
    ): StatementImportPreviewResult

    fun previewMapping(
        session: LocalDelimitedStatementSession,
        mapping: DelimitedStatementMapping,
    ): StatementImportMappingPreviewResult

    suspend fun confirm(
        session: LocalDelimitedStatementSession,
        mapping: DelimitedStatementMapping,
        onProgress: (StatementImportProgress) -> Unit = {},
    ): StatementImportConfirmationResult

    data object Unavailable : DelimitedStatementImport {
        override fun preview(
            evidence: SelectedDelimitedStatementEvidence,
            delimiter: DelimitedDelimiter,
        ): StatementImportPreviewResult = try {
            StatementImportPreviewResult.Failure(StatementImportPreviewError.UNAVAILABLE)
        } finally {
            evidence.bytes.fill(0)
        }

        override fun previewMapping(
            session: LocalDelimitedStatementSession,
            mapping: DelimitedStatementMapping,
        ): StatementImportMappingPreviewResult =
            StatementImportMappingPreviewResult.ClosedSession

        override suspend fun confirm(
            session: LocalDelimitedStatementSession,
            mapping: DelimitedStatementMapping,
            onProgress: (StatementImportProgress) -> Unit,
        ): StatementImportConfirmationResult =
            StatementImportConfirmationResult.ClosedSession
    }
}

class LocalDelimitedStatementImportService internal constructor(
    private val batchRepository: StatementImportBatchRepository,
    private val rowCapture: DelimitedStatementRowCapture,
    private val clock: Clock,
) : DelimitedStatementImport {
    private val confirmationMutex = Mutex()

    constructor(
        batchRepository: StatementImportBatchRepository,
        rawEventRepository: RawEventRepository,
        evidenceStore: EvidenceStagingStore,
        sourceIngestionService: SourceIngestionService,
        evidenceAdmission: EvidenceStorageAdmission = EvidenceStorageAdmission.AllowAll,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        batchRepository = batchRepository,
        rowCapture = PipelineDelimitedStatementRowCapture(
            rawEventRepository = rawEventRepository,
            evidenceStore = evidenceStore,
            sourceIngestionService = sourceIngestionService,
            evidenceAdmission = evidenceAdmission,
            clock = clock,
        ),
        clock = clock,
    )

    override fun preview(
        evidence: SelectedDelimitedStatementEvidence,
        delimiter: DelimitedDelimiter,
    ): StatementImportPreviewResult = try {
        val mediaType = TextEvidenceMediaTypes.canonicalize(evidence.mediaType)
        if (mediaType !in TextEvidenceMediaTypes.USER_SELECTED_TEXT_FILE) {
            return StatementImportPreviewResult.Failure(
                StatementImportPreviewError.UNSUPPORTED_MEDIA_TYPE,
            )
        }
        if (!mediaTypeAllowsDelimiter(mediaType, delimiter)) {
            return StatementImportPreviewResult.Failure(
                StatementImportPreviewError.DELIMITER_MISMATCH,
            )
        }
        when (val read = DelimitedTextReader().read(evidence.bytes, delimiter)) {
            is DelimitedReadResult.Failure ->
                StatementImportPreviewResult.InvalidDocument(read.error)

            is DelimitedReadResult.Success -> {
                if (read.document.rows.isEmpty()) {
                    StatementImportPreviewResult.Failure(
                        StatementImportPreviewError.NO_DATA_ROWS,
                    )
                } else {
                    StatementImportPreviewResult.Ready(
                        LocalDelimitedStatementSession(read.document),
                    )
                }
            }
        }
    } finally {
        evidence.bytes.fill(0)
    }

    override fun previewMapping(
        session: LocalDelimitedStatementSession,
        mapping: DelimitedStatementMapping,
    ): StatementImportMappingPreviewResult {
        val document = try {
            session.requireDocument()
        } catch (_: IllegalStateException) {
            return StatementImportMappingPreviewResult.ClosedSession
        }
        validateMapping(document, mapping)?.let {
            return StatementImportMappingPreviewResult.InvalidMapping(it)
        }

        var validCount = 0
        var invalidCount = 0
        val previews = ArrayList<StatementImportMappedPreviewRow>(
            minOf(document.rows.size, MAX_MAPPED_PREVIEW_ROWS),
        )
        document.rows.forEach { row ->
            val error = when (
                val result = DelimitedStatementMapper.map(document.fileHash, mapping, row)
            ) {
                is StatementRowMappingResult.Mapped -> {
                    validCount += 1
                    null
                }

                is StatementRowMappingResult.Invalid -> {
                    invalidCount += 1
                    result.error
                }
            }
            if (previews.size < MAX_MAPPED_PREVIEW_ROWS) {
                previews += StatementImportMappedPreviewRow(row.tableRowIndex, error)
            }
        }
        return StatementImportMappingPreviewResult.Ready(
            StatementImportMappingPreview(
                validRowCount = validCount,
                invalidRowCount = invalidCount,
                previewRows = previews,
            ),
        )
    }

    override suspend fun confirm(
        session: LocalDelimitedStatementSession,
        mapping: DelimitedStatementMapping,
        onProgress: (StatementImportProgress) -> Unit,
    ): StatementImportConfirmationResult = confirmationMutex.withLock {
        confirmLocked(session, mapping, onProgress)
    }

    private suspend fun confirmLocked(
        session: LocalDelimitedStatementSession,
        mapping: DelimitedStatementMapping,
        onProgress: (StatementImportProgress) -> Unit,
    ): StatementImportConfirmationResult {
        val document = try {
            session.requireDocument()
        } catch (_: IllegalStateException) {
            return StatementImportConfirmationResult.ClosedSession
        }
        validateMapping(document, mapping)?.let {
            return StatementImportConfirmationResult.InvalidMapping(it)
        }

        val mappingHash = DelimitedStatementRowEvidenceCodec.mappingHash(mapping)
        val batchId = deriveBatchId(document.fileHash, mappingHash)
        val opened = batchRepository.open(
            StatementImportBatchRequest(
                id = batchId,
                fileHash = document.fileHash,
                mappingHash = mappingHash,
                totalRowCount = document.rows.size,
                now = clock.instant(),
            ),
        )
        if (opened is StatementImportBatchOpenResult.IdCollision) {
            return StatementImportConfirmationResult.BatchIdentityCollision
        }
        val openedBatch = when (opened) {
            is StatementImportBatchOpenResult.Created -> opened.batch
            is StatementImportBatchOpenResult.Existing -> opened.batch
            StatementImportBatchOpenResult.IdCollision -> error("Handled above")
        }
        val existingRows = batchRepository.listRows(batchId)
            .associateBy(StatementImportRowRecord::tableRowIndex)
        var resumedRows = 0
        var processedRows = existingRows.size
        onProgress(StatementImportProgress(batchId, processedRows, document.rows.size))

        for (row in document.rows) {
            val rowEvidence = DelimitedStatementRowEvidenceCodec.encode(
                fileHash = document.fileHash,
                row = row,
                mapping = mapping,
            )
            try {
                val rowFingerprint = EvidenceHash.fromBytes(rowEvidence)
                val existing = existingRows[row.tableRowIndex]
                if (existing != null) {
                    if (existing.rowFingerprint != rowFingerprint) {
                        return StatementImportConfirmationResult.RowIdentityCollision(
                            row.tableRowIndex,
                        )
                    }
                    resumedRows += 1
                    continue
                }

                when (
                    val mapped = DelimitedStatementMapper.map(
                        document.fileHash,
                        mapping,
                        row,
                    )
                ) {
                    is StatementRowMappingResult.Invalid -> {
                        when (
                            batchRepository.recordRow(
                                StatementImportRowRecord(
                                    batchId = batchId,
                                    tableRowIndex = row.tableRowIndex,
                                    rowFingerprint = rowFingerprint,
                                    state = StatementImportRowState.REJECTED,
                                    rawEventId = null,
                                    errorCode = mapped.error.name,
                                    updatedAt = maxOf(clock.instant(), openedBatch.createdAt),
                                ),
                            )
                        ) {
                            StatementImportRowWriteResult.Inserted,
                            StatementImportRowWriteResult.AlreadyPresent,
                            -> Unit

                            StatementImportRowWriteResult.IdCollision ->
                                return StatementImportConfirmationResult.RowIdentityCollision(
                                    row.tableRowIndex,
                                )

                            StatementImportRowWriteResult.BatchNotFound ->
                                return StatementImportConfirmationResult.Interrupted(
                                    batchId = batchId,
                                    tableRowIndex = row.tableRowIndex,
                                    error = null,
                                )
                        }
                    }

                    is StatementRowMappingResult.Mapped -> {
                        val commandId = deriveRowCommandId(
                            batchId = batchId,
                            tableRowIndex = row.tableRowIndex,
                            rowFingerprint = rowFingerprint,
                        )
                        when (val capture = rowCapture.ingest(commandId, rowEvidence)) {
                            is SourceCaptureResult.ReadyForReview -> {
                                val rawEventId = try {
                                    dev.bill.source.contract.RawEventId(capture.rawEventId)
                                } catch (_: IllegalArgumentException) {
                                    return StatementImportConfirmationResult.Interrupted(
                                        batchId = batchId,
                                        tableRowIndex = row.tableRowIndex,
                                        error = null,
                                    )
                                }
                                when (
                                    batchRepository.recordRow(
                                        StatementImportRowRecord(
                                            batchId = batchId,
                                            tableRowIndex = row.tableRowIndex,
                                            rowFingerprint = rowFingerprint,
                                            state = StatementImportRowState.READY_FOR_REVIEW,
                                            rawEventId = rawEventId,
                                            errorCode = null,
                                            updatedAt = maxOf(
                                                clock.instant(),
                                                openedBatch.createdAt,
                                            ),
                                        ),
                                    )
                                ) {
                                    StatementImportRowWriteResult.Inserted,
                                    StatementImportRowWriteResult.AlreadyPresent,
                                    -> Unit

                                    StatementImportRowWriteResult.IdCollision ->
                                        return StatementImportConfirmationResult
                                            .RowIdentityCollision(row.tableRowIndex)

                                    StatementImportRowWriteResult.BatchNotFound ->
                                        return StatementImportConfirmationResult.Interrupted(
                                            batchId = batchId,
                                            tableRowIndex = row.tableRowIndex,
                                            error = null,
                                        )
                                }
                            }

                            is SourceCaptureResult.Failure ->
                                return StatementImportConfirmationResult.Interrupted(
                                    batchId = batchId,
                                    tableRowIndex = row.tableRowIndex,
                                    error = capture.error,
                                )
                        }
                    }
                }
                processedRows += 1
                onProgress(
                    StatementImportProgress(batchId, processedRows, document.rows.size),
                )
            } finally {
                rowEvidence.fill(0)
            }
        }

        val refreshed = batchRepository.refresh(
            batchId,
            maxOf(clock.instant(), openedBatch.createdAt),
        )
        val batch = (refreshed as? StatementImportBatchRefreshResult.Updated)?.batch
            ?: return StatementImportConfirmationResult.Interrupted(
                batchId = batchId,
                tableRowIndex = null,
                error = null,
            )
        return StatementImportConfirmationResult.Completed(batch, resumedRows)
    }

    private fun validateMapping(
        document: DelimitedDocument,
        mapping: DelimitedStatementMapping,
    ): StatementMappingError? {
        if (mapping.delimiter != document.delimiter) {
            return StatementMappingError.DELIMITER_MISMATCH
        }
        val columns = buildList {
            add(mapping.dateColumnIndex)
            add(mapping.amountColumnIndex)
            add(mapping.counterpartyColumnIndex)
            mapping.referenceColumnIndex?.let(::add)
            (mapping.directionMapping as?
                dev.bill.source.genericdelimited.StatementDirectionMapping.DirectionColumn)
                ?.columnIndex
                ?.let(::add)
        }
        return StatementMappingError.COLUMN_OUT_OF_RANGE.takeIf {
            columns.any { column -> column !in document.header.indices }
        }
    }

    private fun mediaTypeAllowsDelimiter(
        mediaType: String,
        delimiter: DelimitedDelimiter,
    ): Boolean = when (mediaType) {
        TextEvidenceMediaTypes.TEXT_CSV,
        TextEvidenceMediaTypes.APPLICATION_CSV,
        -> delimiter == DelimitedDelimiter.COMMA

        TextEvidenceMediaTypes.TEXT_TAB_SEPARATED ->
            delimiter == DelimitedDelimiter.TAB

        TextEvidenceMediaTypes.TEXT_PLAIN -> true
        else -> false
    }

    private fun deriveBatchId(
        fileHash: EvidenceHash,
        mappingHash: EvidenceHash,
    ) = StatementImportBatchId(
        "statement-import-${stableDigest(fileHash.value, mappingHash.value)}",
    )

    private fun deriveRowCommandId(
        batchId: StatementImportBatchId,
        tableRowIndex: Int,
        rowFingerprint: EvidenceHash,
    ): String = stableDigest(
        batchId.value,
        tableRowIndex.toString(),
        rowFingerprint.value,
    )

    private fun stableDigest(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            try {
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            } finally {
                bytes.fill(0)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private companion object {
        const val MAX_MAPPED_PREVIEW_ROWS = 20
    }
}

internal interface DelimitedStatementRowCapture {
    suspend fun ingest(
        commandId: String,
        bytes: ByteArray,
    ): SourceCaptureResult
}

private class PipelineDelimitedStatementRowCapture(
    rawEventRepository: RawEventRepository,
    evidenceStore: EvidenceStagingStore,
    sourceIngestionService: SourceIngestionService,
    evidenceAdmission: EvidenceStorageAdmission,
    clock: Clock,
) : DelimitedStatementRowCapture {
    private val evidenceIngestion = UserEvidenceIngestion(
        descriptor = UserEvidenceCaptureDescriptor(
            idPrefix = "statement-row",
            connectorId = GenericDelimitedStatementParser.CONNECTOR_ID,
            captureMethod = CaptureMethod.STATEMENT_IMPORT,
            allowedMediaTypes = setOf(DelimitedStatementRowEvidenceCodec.MEDIA_TYPE),
            maxEvidenceBytes = DelimitedStatementRowEvidenceCodec.MAX_EVIDENCE_BYTES.toLong(),
            requiresStrictUtf8Text = false,
        ),
        rawEventRepository = rawEventRepository,
        evidenceStore = evidenceStore,
        sourceIngestionService = sourceIngestionService,
        evidenceAdmission = evidenceAdmission,
        clock = clock,
    )

    override suspend fun ingest(
        commandId: String,
        bytes: ByteArray,
    ): SourceCaptureResult = evidenceIngestion.ingest(
        commandId = commandId,
        mediaType = DelimitedStatementRowEvidenceCodec.MEDIA_TYPE,
        bytes = bytes,
    )
}
