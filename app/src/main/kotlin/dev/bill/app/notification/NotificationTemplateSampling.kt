package dev.bill.app.notification

import dev.bill.source.contract.NotificationField
import dev.bill.source.genericnotification.NotificationContent
import dev.bill.source.genericnotification.NotificationMetadata
import kotlinx.coroutines.flow.StateFlow

internal enum class NotificationTemplateSamplingFailure {
    NONE,
    INVALID_TARGET_PACKAGES,
    STORAGE_UNAVAILABLE,
    UNAVAILABLE_IN_BUILD,
}

internal enum class NotificationTemplateSamplingOperationResult {
    APPLIED,
    INVALID_TARGET_PACKAGES,
    STORAGE_UNAVAILABLE,
    UNAVAILABLE_IN_BUILD,
}

internal enum class NotificationTemplateSamplePreviewStatus {
    AVAILABLE,
    STORAGE_UNAVAILABLE,
    UNAVAILABLE_IN_BUILD,
}

internal data class NotificationTemplateSamplePreview(
    val sequence: Int,
    val packageName: String,
    val channelId: String?,
    val category: String?,
    val postedAtEpochMillis: Long,
    val fields: Map<NotificationField, String>,
) {
    init {
        require(sequence > 0)
        require(postedAtEpochMillis >= 0L)
        require(fields.isNotEmpty())
    }

    override fun toString(): String =
        "NotificationTemplateSamplePreview(sequence=$sequence, redacted=true)"
}

internal data class NotificationTemplateSamplePreviewPage(
    val status: NotificationTemplateSamplePreviewStatus,
    val samples: List<NotificationTemplateSamplePreview>,
    val hasOlderSamples: Boolean,
)

internal data class NotificationTemplateSamplingSnapshot(
    val isAvailable: Boolean,
    val isActive: Boolean,
    val startedAtEpochMillis: Long?,
    val targetPackages: Set<String>,
    val sampleCount: Int,
    val droppedCount: Long,
    val rejectedContentCount: Long,
    val latestSample: NotificationTemplateSamplePreview?,
    val failure: NotificationTemplateSamplingFailure,
)

/**
 * Build-variant boundary for explicitly authorized notification-template research.
 *
 * The release implementation is permanently closed. The debug implementation may open only an
 * explicitly started, package-scoped, forward-only window from its dedicated UI.
 */
internal interface NotificationTemplateSamplingController {
    val state: StateFlow<NotificationTemplateSamplingSnapshot>

    fun acceptsMetadata(
        metadata: NotificationMetadata,
        postedAtEpochMillis: Long,
    ): Boolean

    suspend fun start(targetPackages: Set<String>): NotificationTemplateSamplingOperationResult

    suspend fun stop(): NotificationTemplateSamplingOperationResult

    suspend fun clear(): NotificationTemplateSamplingOperationResult

    suspend fun loadSamplePreviews(
        beforeSequenceExclusive: Int?,
        limit: Int,
    ): NotificationTemplateSamplePreviewPage

    suspend fun record(
        metadata: NotificationMetadata,
        postedAtEpochMillis: Long,
        content: NotificationContent,
    )

    fun onQueueDropped()

    fun onContentRejected()
}

/**
 * Pure package/time policy shared by the debug implementation and JVM tests.
 *
 * A caller may persist a manually controlled window so an OEM process restart does not lose the
 * user's explicit choice. The policy has no automatic expiry or sample limit: only an explicit
 * stop closes it. The pure policy itself owns no persistence and always rejects events before the
 * latest start time.
 */
internal class NotificationTemplateSamplingWindow {
    @Volatile
    private var activeWindow: ActiveWindow? = null

    @Synchronized
    fun start(
        targetPackages: Set<String>,
        startedAtEpochMillis: Long,
    ): Boolean {
        val normalized = normalizeTargetPackages(targetPackages) ?: return false
        if (startedAtEpochMillis < 0L) return false
        activeWindow = ActiveWindow(
            startedAtEpochMillis = startedAtEpochMillis,
            targetPackages = normalized,
        )
        return true
    }

    fun accepts(metadata: NotificationMetadata, postedAtEpochMillis: Long): Boolean {
        val window = activeWindow ?: return false
        return postedAtEpochMillis >= window.startedAtEpochMillis &&
            metadata.packageName in window.targetPackages
    }

    @Synchronized
    fun stop() {
        activeWindow = null
    }

    fun snapshot(): WindowSnapshot? = activeWindow?.let { window ->
        WindowSnapshot(
            startedAtEpochMillis = window.startedAtEpochMillis,
            targetPackages = window.targetPackages.toSet(),
        )
    }

    internal data class WindowSnapshot(
        val startedAtEpochMillis: Long,
        val targetPackages: Set<String>,
    )

    private data class ActiveWindow(
        val startedAtEpochMillis: Long,
        val targetPackages: Set<String>,
    )

    private companion object {
        const val MAX_TARGET_PACKAGES = 16
        val PackageNamePattern =
            Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun normalizeTargetPackages(targetPackages: Set<String>): Set<String>? {
            val normalized = targetPackages
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
            return normalized.takeIf {
                it.isNotEmpty() &&
                    it.size <= MAX_TARGET_PACKAGES &&
                    it.all(PackageNamePattern::matches)
            }
        }
    }
}
