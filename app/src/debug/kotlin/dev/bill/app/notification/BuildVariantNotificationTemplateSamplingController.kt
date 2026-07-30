package dev.bill.app.notification

import android.content.Context
import dev.bill.source.contract.NotificationField
import dev.bill.source.genericnotification.NotificationContent
import dev.bill.source.genericnotification.NotificationMetadata
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class BuildVariantNotificationTemplateSamplingController(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
    sampleFileName: String = SAMPLE_FILE_NAME,
    preferencesName: String = PREFERENCES_NAME,
) : NotificationTemplateSamplingController {
    private val appContext = context.applicationContext ?: context
    private val sampleFile = File(appContext.noBackupFilesDir, sampleFileName)
    private val preferences = appContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )
    private val window = NotificationTemplateSamplingWindow()
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(restoreInitialSnapshot())

    override val state: StateFlow<NotificationTemplateSamplingSnapshot> = mutableState.asStateFlow()

    override fun acceptsMetadata(
        metadata: NotificationMetadata,
        postedAtEpochMillis: Long,
    ): Boolean = window.accepts(metadata, postedAtEpochMillis)

    override suspend fun start(
        targetPackages: Set<String>,
    ): NotificationTemplateSamplingOperationResult = operationMutex.withLock {
        // Starting is a replace operation. Close any previous runtime window before validating or
        // touching storage so a failed restart cannot leave the old package set active.
        window.stop()
        val previousWindowCleared = withContext(Dispatchers.IO) {
            clearPersistedWindow()
        }
        if (!previousWindowCleared) {
            mutableState.value = inactiveFailure(
                NotificationTemplateSamplingFailure.STORAGE_UNAVAILABLE,
            )
            return@withLock NotificationTemplateSamplingOperationResult.STORAGE_UNAVAILABLE
        }
        val startedAt = clock.millis()
        val validationWindow = NotificationTemplateSamplingWindow()
        if (!validationWindow.start(targetPackages, startedAt)) {
            mutableState.value = inactiveFailure(
                failure = NotificationTemplateSamplingFailure.INVALID_TARGET_PACKAGES,
            )
            return@withLock NotificationTemplateSamplingOperationResult.INVALID_TARGET_PACKAGES
        }
        val validatedTargets = requireNotNull(validationWindow.snapshot()).targetPackages
        validationWindow.stop()
        val existingSampleCount = withContext(Dispatchers.IO) {
            inspectStoredSampleCount()
        }
        if (existingSampleCount == null) {
            mutableState.value = inactiveFailure(
                NotificationTemplateSamplingFailure.STORAGE_UNAVAILABLE,
            )
            return@withLock NotificationTemplateSamplingOperationResult.STORAGE_UNAVAILABLE
        }

        val initialized = withContext(Dispatchers.IO) {
            try {
                sampleFile.parentFile?.mkdirs()
                FileOutputStream(sampleFile, true).use { output ->
                    output.fd.sync()
                }
                true
            } catch (_: IOException) {
                false
            } catch (_: RuntimeException) {
                false
            }
        }
        if (!initialized) {
            window.stop()
            mutableState.value = inactiveFailure(
                NotificationTemplateSamplingFailure.STORAGE_UNAVAILABLE,
            )
            return@withLock NotificationTemplateSamplingOperationResult.STORAGE_UNAVAILABLE
        }

        val persisted = withContext(Dispatchers.IO) {
            persistWindow(
                targetPackages = validatedTargets,
                startedAtEpochMillis = startedAt,
            )
        }
        if (!persisted) {
            window.stop()
            mutableState.value = inactiveFailure(
                NotificationTemplateSamplingFailure.STORAGE_UNAVAILABLE,
            )
            return@withLock NotificationTemplateSamplingOperationResult.STORAGE_UNAVAILABLE
        }
        if (!window.start(validatedTargets, startedAt)) {
            withContext(Dispatchers.IO) {
                clearPersistedWindow()
            }
            window.stop()
            mutableState.value = inactiveFailure(
                NotificationTemplateSamplingFailure.STORAGE_UNAVAILABLE,
            )
            return@withLock NotificationTemplateSamplingOperationResult.STORAGE_UNAVAILABLE
        }

        val active = requireNotNull(window.snapshot())
        mutableState.value = NotificationTemplateSamplingSnapshot(
            isAvailable = true,
            isActive = true,
            startedAtEpochMillis = active.startedAtEpochMillis,
            targetPackages = active.targetPackages,
            sampleCount = existingSampleCount,
            droppedCount = 0L,
            rejectedContentCount = 0L,
            latestSample = null,
            failure = NotificationTemplateSamplingFailure.NONE,
        )
        NotificationTemplateSamplingOperationResult.APPLIED
    }

    override suspend fun stop(): NotificationTemplateSamplingOperationResult =
        operationMutex.withLock {
            window.stop()
            val persisted = withContext(Dispatchers.IO) {
                clearPersistedWindow()
            }
            mutableState.value = mutableState.value.copy(
                isActive = false,
                startedAtEpochMillis = null,
                targetPackages = emptySet(),
                failure = if (persisted) {
                    NotificationTemplateSamplingFailure.NONE
                } else {
                    NotificationTemplateSamplingFailure.STORAGE_UNAVAILABLE
                },
            )
            if (persisted) {
                NotificationTemplateSamplingOperationResult.APPLIED
            } else {
                NotificationTemplateSamplingOperationResult.STORAGE_UNAVAILABLE
            }
        }

    override suspend fun clear(): NotificationTemplateSamplingOperationResult =
        operationMutex.withLock {
            window.stop()
            val cleared = withContext(Dispatchers.IO) {
                try {
                    val windowCleared = clearPersistedWindow()
                    val sampleDeleted = !sampleFile.exists() || sampleFile.delete()
                    windowCleared && sampleDeleted
                } catch (_: RuntimeException) {
                    false
                }
            }
            if (!cleared) {
                mutableState.value = inactiveFailure(
                    NotificationTemplateSamplingFailure.STORAGE_UNAVAILABLE,
                )
                return@withLock NotificationTemplateSamplingOperationResult.STORAGE_UNAVAILABLE
            }
            mutableState.value = inactiveSnapshot(sampleCount = 0)
            NotificationTemplateSamplingOperationResult.APPLIED
        }

    override suspend fun loadSamplePreviews(
        beforeSequenceExclusive: Int?,
        limit: Int,
    ): NotificationTemplateSamplePreviewPage {
        if (
            limit !in 1..MAX_PREVIEW_PAGE_SIZE ||
            (beforeSequenceExclusive != null && beforeSequenceExclusive <= 0)
        ) {
            return unavailablePreviewPage()
        }
        return withContext(Dispatchers.IO) {
            try {
                val snapshotLength = sampleFile.length()
                readPreviewPage(
                    snapshotLength = snapshotLength,
                    beforeSequenceExclusive = beforeSequenceExclusive,
                    limit = limit,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                unavailablePreviewPage()
            } catch (_: RuntimeException) {
                unavailablePreviewPage()
            }
        }
    }

    override suspend fun record(
        metadata: NotificationMetadata,
        postedAtEpochMillis: Long,
        content: NotificationContent,
    ) {
        operationMutex.withLock {
            if (!window.accepts(metadata, postedAtEpochMillis)) return@withLock
            val current = mutableState.value

            var encoded: ByteArray? = null
            try {
                val preview = createPreview(
                    sequence = current.sampleCount.saturatingIncrement(),
                    metadata = metadata,
                    postedAtEpochMillis = postedAtEpochMillis,
                    content = content,
                )
                encoded = encodeSample(preview)
                val storedCount = withContext(Dispatchers.IO) {
                    appendSample(
                        encoded = encoded,
                        existingSampleCount = current.sampleCount,
                    )
                } ?: run {
                    stopForStorageFailure()
                    return@withLock
                }
                mutableState.update { latest ->
                    latest.copy(
                        sampleCount = storedCount,
                        latestSample = preview,
                        failure = NotificationTemplateSamplingFailure.NONE,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                stopForStorageFailure()
            } catch (_: RuntimeException) {
                stopForStorageFailure()
            } finally {
                encoded?.fill(0)
            }
        }
    }

    override fun onQueueDropped() {
        mutableState.update { current ->
            current.copy(droppedCount = current.droppedCount.saturatingIncrement())
        }
    }

    override fun onContentRejected() {
        mutableState.update { current ->
            current.copy(
                rejectedContentCount = current.rejectedContentCount.saturatingIncrement(),
            )
        }
    }

    private fun createPreview(
        sequence: Int,
        metadata: NotificationMetadata,
        postedAtEpochMillis: Long,
        content: NotificationContent,
    ): NotificationTemplateSamplePreview = NotificationTemplateSamplePreview(
        sequence = sequence,
        packageName = metadata.packageName,
        channelId = metadata.channelId,
        category = metadata.category,
        postedAtEpochMillis = postedAtEpochMillis,
        fields = NotificationField.entries
            .mapNotNull { field -> content.field(field)?.let { field to it } }
            .toMap(),
    )

    private fun encodeSample(
        preview: NotificationTemplateSamplePreview,
    ): ByteArray {
        val fields = JSONObject()
        preview.fields.forEach { (field, value) ->
            fields.put(field.name, value)
        }
        return JSONObject()
            .put("schemaVersion", SAMPLE_SCHEMA_VERSION)
            .put("sequence", preview.sequence)
            .put("packageName", preview.packageName)
            .put("channelId", preview.channelId ?: JSONObject.NULL)
            .put("category", preview.category ?: JSONObject.NULL)
            .put("postedAtEpochMillis", preview.postedAtEpochMillis)
            .put("fields", fields)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun appendSample(
        encoded: ByteArray,
        existingSampleCount: Int,
    ): Int? = try {
        FileOutputStream(sampleFile, true).use { output ->
            output.write(encoded)
            output.write(NEWLINE)
            output.fd.sync()
        }
        existingSampleCount.saturatingIncrement()
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun readPreviewPage(
        snapshotLength: Long,
        beforeSequenceExclusive: Int?,
        limit: Int,
    ): NotificationTemplateSamplePreviewPage {
        if (snapshotLength == 0L) {
            return NotificationTemplateSamplePreviewPage(
                status = NotificationTemplateSamplePreviewStatus.AVAILABLE,
                samples = emptyList(),
                hasOlderSamples = false,
            )
        }
        val reversedLine = ByteArray(MAX_SAMPLE_LINE_BYTES)
        return try {
            RandomAccessFile(sampleFile, "r").use { input ->
                if (snapshotLength > input.length()) return unavailablePreviewPage()
                input.seek(snapshotLength - 1L)
                if (input.read() != NEWLINE) return unavailablePreviewPage()

                val samples = mutableListOf<NotificationTemplateSamplePreview>()
                var cursor = snapshotLength - 2L
                var lineLength = 0
                while (cursor >= -1L && samples.size <= limit) {
                    val nextByte = if (cursor < 0L) {
                        NEWLINE
                    } else {
                        input.seek(cursor)
                        input.read()
                    }
                    if (nextByte == NEWLINE) {
                        if (lineLength == 0) {
                            return unavailablePreviewPage()
                        } else {
                            reversedLine.reversePrefix(lineLength)
                            val preview = decodePreview(reversedLine, lineLength)
                                ?: return unavailablePreviewPage()
                            if (
                                beforeSequenceExclusive == null ||
                                preview.sequence < beforeSequenceExclusive
                            ) {
                                samples += preview
                            }
                            reversedLine.fill(0, 0, lineLength)
                            lineLength = 0
                        }
                    } else {
                        if (lineLength == reversedLine.size) return unavailablePreviewPage()
                        reversedLine[lineLength] = nextByte.toByte()
                        lineLength += 1
                    }
                    cursor -= 1L
                }

                val hasOlderSamples = samples.size > limit
                NotificationTemplateSamplePreviewPage(
                    status = NotificationTemplateSamplePreviewStatus.AVAILABLE,
                    samples = samples.take(limit),
                    hasOlderSamples = hasOlderSamples,
                )
            }
        } finally {
            reversedLine.fill(0)
        }
    }

    private fun ByteArray.reversePrefix(length: Int) {
        var left = 0
        var right = length - 1
        while (left < right) {
            val swap = this[left]
            this[left] = this[right]
            this[right] = swap
            left += 1
            right -= 1
        }
    }

    private fun decodePreview(
        encoded: ByteArray,
        length: Int,
    ): NotificationTemplateSamplePreview? = try {
        val decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded, 0, length))
            .toString()
        val json = JSONObject(decoded)
        if (json.getInt("schemaVersion") != SAMPLE_SCHEMA_VERSION) return null
        val sequence = json.getInt("sequence")
        val packageName = json.getString("packageName")
        val channelId = json.nullableString("channelId")
        val category = json.nullableString("category")
        val postedAtEpochMillis = json.getLong("postedAtEpochMillis")
        val fieldsJson = json.getJSONObject("fields")
        val fieldsByName = NotificationField.entries.associateBy(NotificationField::name)
        val decodedFields = linkedMapOf<NotificationField, String?>()
        val keys = fieldsJson.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val field = fieldsByName[name] ?: return null
            decodedFields[field] = fieldsJson.getString(name)
        }
        val content = NotificationContent.from(decodedFields) ?: return null
        val metadata = NotificationMetadata(
            packageName = packageName,
            channelId = channelId,
            category = category,
        )
        createPreview(
            sequence = sequence,
            metadata = metadata,
            postedAtEpochMillis = postedAtEpochMillis,
            content = content,
        )
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun unavailablePreviewPage() = NotificationTemplateSamplePreviewPage(
        status = NotificationTemplateSamplePreviewStatus.STORAGE_UNAVAILABLE,
        samples = emptyList(),
        hasOlderSamples = false,
    )

    private suspend fun stopForStorageFailure() {
        window.stop()
        withContext(Dispatchers.IO) {
            clearPersistedWindow()
        }
        mutableState.value = inactiveFailure(
            NotificationTemplateSamplingFailure.STORAGE_UNAVAILABLE,
        )
    }

    private fun inactiveFailure(
        failure: NotificationTemplateSamplingFailure,
    ): NotificationTemplateSamplingSnapshot = mutableState.value.copy(
        isActive = false,
        startedAtEpochMillis = null,
        targetPackages = emptySet(),
        failure = failure,
    )

    private fun restoreInitialSnapshot(): NotificationTemplateSamplingSnapshot {
        val sampleCount = inspectStoredSampleCount()
        val now = clock.millis()
        val persisted = readPersistedWindow()
        val restored = persisted?.takeIf {
            sampleCount != null &&
                now >= it.startedAtEpochMillis &&
                window.start(
                    targetPackages = it.targetPackages,
                    startedAtEpochMillis = it.startedAtEpochMillis,
                )
        }
        if (persisted != null && restored == null) {
            clearPersistedWindow()
        }
        if (restored != null) {
            val active = requireNotNull(window.snapshot())
            return NotificationTemplateSamplingSnapshot(
                isAvailable = true,
                isActive = true,
                startedAtEpochMillis = active.startedAtEpochMillis,
                targetPackages = active.targetPackages,
                sampleCount = sampleCount ?: 0,
                droppedCount = 0L,
                rejectedContentCount = 0L,
                latestSample = null,
                failure = if (sampleCount == null) {
                    NotificationTemplateSamplingFailure.STORAGE_UNAVAILABLE
                } else {
                    NotificationTemplateSamplingFailure.NONE
                },
            )
        }
        return inactiveSnapshot(
            sampleCount = sampleCount ?: 0,
            failure = if (sampleCount == null) {
                NotificationTemplateSamplingFailure.STORAGE_UNAVAILABLE
            } else {
                NotificationTemplateSamplingFailure.NONE
            },
        )
    }

    private fun inactiveSnapshot(
        sampleCount: Int,
        failure: NotificationTemplateSamplingFailure =
            NotificationTemplateSamplingFailure.NONE,
    ): NotificationTemplateSamplingSnapshot =
        NotificationTemplateSamplingSnapshot(
            isAvailable = true,
            isActive = false,
            startedAtEpochMillis = null,
            targetPackages = emptySet(),
            sampleCount = sampleCount,
            droppedCount = 0L,
            rejectedContentCount = 0L,
            latestSample = null,
            failure = failure,
        )

    private fun persistWindow(
        targetPackages: Set<String>,
        startedAtEpochMillis: Long,
    ): Boolean = try {
        preferences.edit()
            .clear()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_STARTED_AT, startedAtEpochMillis)
            .putStringSet(KEY_TARGET_PACKAGES, targetPackages)
            .commit()
    } catch (_: RuntimeException) {
        false
    }

    private fun clearPersistedWindow(): Boolean = try {
        preferences.edit().clear().commit()
    } catch (_: RuntimeException) {
        false
    }

    private fun readPersistedWindow(): PersistedWindow? = try {
        if (!preferences.getBoolean(KEY_ACTIVE, false)) return null
        val startedAt = preferences.getLong(KEY_STARTED_AT, -1L)
        val targetPackages = preferences
            .getStringSet(KEY_TARGET_PACKAGES, emptySet())
            ?.toSet()
            .orEmpty()
        PersistedWindow(
            startedAtEpochMillis = startedAt,
            targetPackages = targetPackages,
        )
    } catch (_: RuntimeException) {
        null
    }

    private fun inspectStoredSampleCount(): Int? {
        if (!sampleFile.exists()) return 0
        return try {
            val page = readPreviewPage(
                snapshotLength = sampleFile.length(),
                beforeSequenceExclusive = null,
                limit = SAMPLE_COUNT_TAIL_RECORDS,
            )
            if (page.status != NotificationTemplateSamplePreviewStatus.AVAILABLE) return null
            val newest = page.samples.firstOrNull() ?: return 0
            val secondNewest = page.samples.getOrNull(1)
            when {
                secondNewest != null && newest.sequence != secondNewest.sequence + 1 -> null
                !page.hasOlderSamples && page.samples.last().sequence != 1 -> null
                else -> newest.sequence
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun Long.saturatingIncrement(): Long =
        if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1L

    private fun Int.saturatingIncrement(): Int =
        if (this == Int.MAX_VALUE) Int.MAX_VALUE else this + 1

    private data class PersistedWindow(
        val startedAtEpochMillis: Long,
        val targetPackages: Set<String>,
    )

    internal companion object {
        const val SAMPLE_FILE_NAME = "notification-template-samples.ndjson"
        const val PREFERENCES_NAME = "bill.notification-template-sampling-debug"
        private const val KEY_ACTIVE = "active"
        private const val KEY_STARTED_AT = "started-at"
        private const val KEY_TARGET_PACKAGES = "target-packages"
        private const val SAMPLE_SCHEMA_VERSION = 1
        private const val NEWLINE = '\n'.code
        private const val MAX_SAMPLE_LINE_BYTES = 64 * 1_024
        private const val MAX_PREVIEW_PAGE_SIZE = 20
        private const val SAMPLE_COUNT_TAIL_RECORDS = 2
    }
}
