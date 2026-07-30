package dev.bill.app.notification

import android.content.Context
import dev.bill.source.genericnotification.NotificationContent
import dev.bill.source.genericnotification.NotificationMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Suppress("UNUSED_PARAMETER")
internal class BuildVariantNotificationTemplateSamplingController(
    context: Context,
) : NotificationTemplateSamplingController {
    private val mutableState = MutableStateFlow(
        NotificationTemplateSamplingSnapshot(
            isAvailable = false,
            isActive = false,
            startedAtEpochMillis = null,
            targetPackages = emptySet(),
            sampleCount = 0,
            droppedCount = 0L,
            rejectedContentCount = 0L,
            latestSample = null,
            failure = NotificationTemplateSamplingFailure.UNAVAILABLE_IN_BUILD,
        ),
    )

    override val state: StateFlow<NotificationTemplateSamplingSnapshot> = mutableState.asStateFlow()

    override fun acceptsMetadata(
        metadata: NotificationMetadata,
        postedAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun start(
        targetPackages: Set<String>,
    ): NotificationTemplateSamplingOperationResult =
        NotificationTemplateSamplingOperationResult.UNAVAILABLE_IN_BUILD

    override suspend fun stop(): NotificationTemplateSamplingOperationResult =
        NotificationTemplateSamplingOperationResult.UNAVAILABLE_IN_BUILD

    override suspend fun clear(): NotificationTemplateSamplingOperationResult =
        NotificationTemplateSamplingOperationResult.UNAVAILABLE_IN_BUILD

    override suspend fun loadSamplePreviews(
        beforeSequenceExclusive: Int?,
        limit: Int,
    ): NotificationTemplateSamplePreviewPage = NotificationTemplateSamplePreviewPage(
        status = NotificationTemplateSamplePreviewStatus.UNAVAILABLE_IN_BUILD,
        samples = emptyList(),
        hasOlderSamples = false,
    )

    override suspend fun record(
        metadata: NotificationMetadata,
        postedAtEpochMillis: Long,
        content: NotificationContent,
    ) = Unit

    override fun onQueueDropped() = Unit

    override fun onContentRejected() = Unit
}
