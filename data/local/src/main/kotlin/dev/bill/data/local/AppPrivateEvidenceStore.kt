package dev.bill.data.local

import android.content.Context
import android.util.AtomicFile
import dev.bill.source.review.EvidenceArtifactScanResult
import dev.bill.source.review.EvidenceArtifactStore
import dev.bill.source.review.EvidenceStageResult
import dev.bill.source.review.EvidenceStagingStore
import dev.bill.source.review.EvidenceDeleteResult
import dev.bill.source.review.EvidenceLifecycleStore
import dev.bill.source.review.EvidenceMeasureResult
import dev.bill.source.contract.DiagnosticCode
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.EvidenceReadResult
import dev.bill.source.contract.EvidenceReader
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.SafeDiagnostic
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Small, app-private staging store for explicitly shared evidence.
 *
 * Files live under noBackupFilesDir, use opaque names, and are atomically replaced. The payload
 * hash is still verified by SourceIngestionService before parsing.
 */
class AppPrivateEvidenceStore(
    context: Context,
) : EvidenceReader(), EvidenceStagingStore, EvidenceLifecycleStore, EvidenceArtifactStore {
    private val directory = File(context.noBackupFilesDir, DirectoryName)
    private val mutex = Mutex()

    override suspend fun stage(
        payloadId: PayloadId,
        mediaType: String,
        bytes: ByteArray,
        maxBytes: Long,
    ): EvidenceStageResult = withContext(Dispatchers.IO) {
        if (maxBytes <= 0L || bytes.size.toLong() > maxBytes) {
            return@withContext EvidenceStageResult.TooLarge
        }
        try {
            EvidenceInput(mediaType, byteArrayOf())
        } catch (_: IllegalArgumentException) {
            return@withContext EvidenceStageResult.Failed
        }

        mutex.withLock {
            try {
                ensureDirectory()
                val target = targetFile(payloadId)
                if (target.exists()) {
                    val existing = readEnvelope(target, maxBytes)
                        ?: return@withLock EvidenceStageResult.Failed
                    return@withLock try {
                        if (
                            existing.mediaType == mediaType &&
                            MessageDigest.isEqual(existing.bytes, bytes)
                        ) {
                            EvidenceStageResult.AlreadyStored
                        } else {
                            EvidenceStageResult.IdCollision
                        }
                    } finally {
                        existing.bytes.fill(0)
                    }
                }

                val atomicFile = AtomicFile(target)
                val stream = atomicFile.startWrite()
                try {
                    val output = DataOutputStream(BufferedOutputStream(stream))
                    output.writeInt(Magic)
                    output.writeInt(Version)
                    output.writeBoundedString(mediaType)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                    atomicFile.finishWrite(stream)
                    EvidenceStageResult.Stored
                } catch (_: RuntimeException) {
                    atomicFile.failWrite(stream)
                    EvidenceStageResult.Failed
                } catch (_: IOException) {
                    atomicFile.failWrite(stream)
                    EvidenceStageResult.Failed
                }
            } catch (_: RuntimeException) {
                EvidenceStageResult.Failed
            } catch (_: IOException) {
                EvidenceStageResult.Failed
            }
        }
    }

    override suspend fun readBounded(
        payloadId: PayloadId,
        maxBytes: Long,
    ): EvidenceReadResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = try {
                targetFile(payloadId)
            } catch (_: RuntimeException) {
                return@withLock failed(recoverable = false)
            }
            if (!target.exists()) return@withLock EvidenceReadResult.NotFound
            val envelope = try {
                readEnvelope(target, maxBytes)
            } catch (_: RuntimeException) {
                null
            } catch (_: IOException) {
                null
            } ?: return@withLock failed(recoverable = false)
            try {
                EvidenceReadResult.Found(
                    EvidenceInput(envelope.mediaType, envelope.bytes),
                )
            } finally {
                envelope.bytes.fill(0)
            }
        }
    }

    override suspend fun delete(payloadId: PayloadId): EvidenceDeleteResult =
        withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = try {
                targetFile(payloadId)
            } catch (_: RuntimeException) {
                return@withLock EvidenceDeleteResult.Failed
            }
            try {
                var deleted = true
                atomicArtifacts(target).forEach { artifact ->
                    if (artifact.exists() && !artifact.delete()) {
                        deleted = false
                    }
                }
                if (deleted) {
                    EvidenceDeleteResult.DeletedOrAbsent
                } else {
                    EvidenceDeleteResult.Failed
                }
            } catch (_: RuntimeException) {
                EvidenceDeleteResult.Failed
            }
        }
    }

    override suspend fun scanOrphanCandidates(
        olderThanOrEqualTo: Instant,
        excludedPayloadIds: Set<PayloadId>,
        limit: Int,
        inspectionLimit: Int,
    ): EvidenceArtifactScanResult = withContext(Dispatchers.IO) {
        require(limit > 0)
        require(inspectionLimit >= limit)
        mutex.withLock {
            if (!directory.exists()) {
                return@withLock EvidenceArtifactScanResult(
                    payloadIds = emptyList(),
                    inspectedEntryCount = 0,
                    truncated = false,
                )
            }
            if (!directory.isDirectory) {
                return@withLock EvidenceArtifactScanResult(
                    payloadIds = emptyList(),
                    inspectedEntryCount = 0,
                    truncated = true,
                )
            }

            val payloadIds = linkedSetOf<PayloadId>()
            var inspected = 0
            var truncated = false
            try {
                Files.newDirectoryStream(directory.toPath()).use { entries ->
                    for (entry in entries) {
                        if (inspected >= inspectionLimit || payloadIds.size >= limit) {
                            truncated = true
                            break
                        }
                        inspected += 1
                        val payloadId = artifactPayloadId(entry.fileName.toString()) ?: continue
                        if (payloadId in excludedPayloadIds) continue
                        val modifiedAt = Files.getLastModifiedTime(entry).toInstant()
                        if (!modifiedAt.isAfter(olderThanOrEqualTo)) {
                            payloadIds += payloadId
                        }
                    }
                }
            } catch (_: IOException) {
                truncated = true
            } catch (_: RuntimeException) {
                truncated = true
            }
            EvidenceArtifactScanResult(
                payloadIds = payloadIds.toList(),
                inspectedEntryCount = inspected,
                truncated = truncated,
            )
        }
    }

    override suspend fun measure(
        payloadId: PayloadId,
        maxBytes: Long,
    ): EvidenceMeasureResult = withContext(Dispatchers.IO) {
        if (maxBytes < 0L) return@withContext EvidenceMeasureResult.Failed
        mutex.withLock {
            val target = try {
                targetFile(payloadId)
            } catch (_: RuntimeException) {
                return@withLock EvidenceMeasureResult.Failed
            }
            if (!target.exists()) return@withLock EvidenceMeasureResult.NotFound
            val payloadSize = try {
                readEnvelopeSize(target, maxBytes)
            } catch (_: RuntimeException) {
                null
            } catch (_: IOException) {
                null
            } ?: return@withLock EvidenceMeasureResult.Failed
            EvidenceMeasureResult.Found(payloadSize)
        }
    }

    private fun readEnvelope(file: File, maxBytes: Long): Envelope? {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != Magic || input.readInt() != Version) return null
            val mediaType = input.readBoundedString()
            val payloadSize = input.readInt()
            if (payloadSize < 0 || payloadSize.toLong() > maxBytes) return null
            val bytes = ByteArray(payloadSize)
            return try {
                input.readFully(bytes)
                if (input.read() != -1) {
                    bytes.fill(0)
                    return null
                }
                EvidenceInput(mediaType.value, byteArrayOf())
                Envelope(mediaType.value, bytes)
            } catch (_: EOFException) {
                bytes.fill(0)
                null
            } catch (_: RuntimeException) {
                bytes.fill(0)
                null
            }
        }
    }

    private fun readEnvelopeSize(file: File, maxBytes: Long): Long? {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != Magic || input.readInt() != Version) return null
            val mediaType = input.readBoundedString()
            EvidenceInput(mediaType.value, byteArrayOf())
            val payloadSize = input.readInt()
            if (payloadSize < 0 || payloadSize.toLong() > maxBytes) return null
            val expectedSize = EnvelopeFixedBytes
                .plus(mediaType.encodedSize.toLong())
                .plus(payloadSize.toLong())
            if (file.length() != expectedSize) return null
            return payloadSize.toLong()
        }
    }

    private fun targetFile(payloadId: PayloadId): File {
        val target = File(directory, "${payloadId.value}.blob")
        require(target.parentFile == directory)
        return target
    }

    private fun artifactPayloadId(fileName: String): PayloadId? {
        val payloadValue = ArtifactSuffixes.firstNotNullOfOrNull { suffix ->
            fileName
                .takeIf { it.endsWith(suffix) }
                ?.removeSuffix(suffix)
        } ?: return null
        return try {
            PayloadId(payloadValue)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun atomicArtifacts(target: File): List<File> = listOf(
        target,
        File("${target.path}.new"),
        File("${target.path}.bak"),
    )

    private fun ensureDirectory() {
        if (!directory.exists()) require(directory.mkdirs())
        require(directory.isDirectory)
    }

    private fun DataOutputStream.writeBoundedString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size in 1..MaxMediaTypeBytes)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(): BoundedString {
        val size = readInt()
        require(size in 1..MaxMediaTypeBytes)
        val bytes = ByteArray(size)
        readFully(bytes)
        return BoundedString(
            value = String(bytes, StandardCharsets.US_ASCII),
            encodedSize = size,
        )
    }

    private fun failed(recoverable: Boolean) = EvidenceReadResult.Failed(
        SafeDiagnostic(
            code = DiagnosticCode.EVIDENCE_READ_FAILED,
            recoverable = recoverable,
        ),
    )

    private data class Envelope(
        val mediaType: String,
        val bytes: ByteArray,
    )

    private data class BoundedString(
        val value: String,
        val encodedSize: Int,
    )

    private companion object {
        const val DirectoryName = "source-evidence"
        const val Magic = 0x42455644
        const val Version = 1
        const val MaxMediaTypeBytes = 255
        const val EnvelopeFixedBytes = 4L * 4L
        val ArtifactSuffixes = listOf(".blob", ".blob.new", ".blob.bak")
    }
}
