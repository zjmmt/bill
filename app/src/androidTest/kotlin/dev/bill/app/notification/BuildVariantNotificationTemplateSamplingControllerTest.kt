package dev.bill.app.notification

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bill.source.contract.NotificationField
import dev.bill.source.genericnotification.NotificationContent
import dev.bill.source.genericnotification.NotificationMetadata
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BuildVariantNotificationTemplateSamplingControllerTest {
    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var controller: BuildVariantNotificationTemplateSamplingController

    @Before
    fun setUp() {
        sampleFile().delete()
        targetContext.deleteSharedPreferences(TEST_PREFERENCES_NAME)
        controller = newController()
    }

    @After
    fun cleanUp() {
        runBlocking {
            controller.clear()
            sampleFile().delete()
            targetContext.deleteSharedPreferences(TEST_PREFERENCES_NAME)
        }
    }

    @Test
    fun writesOnlyAllowedNewNotificationsAndClearRemovesThePrivateFile() = runBlocking {
        assertEquals(
            NotificationTemplateSamplingOperationResult.APPLIED,
            controller.start(setOf(ALIPAY_PACKAGE)),
        )

        controller.record(
            metadata = metadata(ALIPAY_PACKAGE),
            postedAtEpochMillis = STARTED_AT - 1L,
            content = fixtureContent("historical-fixture"),
        )
        controller.record(
            metadata = metadata(WECHAT_PACKAGE),
            postedAtEpochMillis = STARTED_AT,
            content = fixtureContent("other-package-fixture"),
        )
        controller.record(
            metadata = metadata(ALIPAY_PACKAGE),
            postedAtEpochMillis = STARTED_AT,
            content = fixtureContent("new-allowed-fixture"),
        )
        controller.onContentRejected()

        assertEquals(1, controller.state.value.sampleCount)
        assertEquals(1L, controller.state.value.rejectedContentCount)
        val stored = sampleFile().readText(Charsets.UTF_8)
        assertTrue(stored.contains("new-allowed-fixture"))
        assertFalse(stored.contains("historical-fixture"))
        assertFalse(stored.contains("other-package-fixture"))
        val previewPage = controller.loadSamplePreviews(
            beforeSequenceExclusive = null,
            limit = 10,
        )
        assertEquals(
            NotificationTemplateSamplePreviewStatus.AVAILABLE,
            previewPage.status,
        )
        assertEquals(1, previewPage.samples.size)
        assertEquals(
            "new-allowed-fixture",
            previewPage.samples.single().fields[NotificationField.TITLE],
        )
        assertFalse(previewPage.hasOlderSamples)

        assertEquals(
            NotificationTemplateSamplingOperationResult.APPLIED,
            controller.clear(),
        )
        assertFalse(sampleFile().exists())
    }

    @Test
    fun previewPagesExposeEveryStoredSampleNewestFirstWithoutLoadingThemAll() = runBlocking {
        controller.start(setOf(ALIPAY_PACKAGE))
        repeat(3) { index ->
            controller.record(
                metadata = metadata(ALIPAY_PACKAGE),
                postedAtEpochMillis = STARTED_AT + index,
                content = fixtureContent("preview-fixture-${index + 1}"),
            )
        }

        val newestPage = controller.loadSamplePreviews(
            beforeSequenceExclusive = null,
            limit = 2,
        )
        assertEquals(
            listOf("preview-fixture-3", "preview-fixture-2"),
            newestPage.samples.map { it.fields[NotificationField.TITLE] },
        )
        assertEquals(ALIPAY_PACKAGE, newestPage.samples.first().packageName)
        assertEquals("fixture-channel", newestPage.samples.first().channelId)
        assertEquals("status", newestPage.samples.first().category)
        assertEquals(STARTED_AT + 2L, newestPage.samples.first().postedAtEpochMillis)
        assertEquals(
            "fixture-text",
            newestPage.samples.first().fields[NotificationField.TEXT],
        )
        assertTrue(newestPage.hasOlderSamples)

        val olderPage = controller.loadSamplePreviews(
            beforeSequenceExclusive = newestPage.samples.last().sequence,
            limit = 2,
        )
        assertEquals(
            listOf("preview-fixture-1"),
            olderPage.samples.map { it.fields[NotificationField.TITLE] },
        )
        assertFalse(olderPage.hasOlderSamples)

        controller = newController()
        assertEquals(3, controller.state.value.sampleCount)
        assertTrue(controller.state.value.isActive)
    }

    @Test
    fun previewRejectsAnEmptyNdjsonRecord() = runBlocking {
        sampleFile().writeText("\n", Charsets.UTF_8)

        val page = controller.loadSamplePreviews(
            beforeSequenceExclusive = null,
            limit = 10,
        )

        assertEquals(
            NotificationTemplateSamplePreviewStatus.STORAGE_UNAVAILABLE,
            page.status,
        )
        assertTrue(page.samples.isEmpty())
    }

    @Test
    fun invalidRestartClosesAnExistingWindow() = runBlocking {
        assertEquals(
            NotificationTemplateSamplingOperationResult.APPLIED,
            controller.start(setOf(ALIPAY_PACKAGE)),
        )
        assertTrue(controller.acceptsMetadata(metadata(ALIPAY_PACKAGE), STARTED_AT))

        assertEquals(
            NotificationTemplateSamplingOperationResult.INVALID_TARGET_PACKAGES,
            controller.start(setOf("not a package")),
        )

        assertFalse(controller.state.value.isActive)
        assertFalse(controller.acceptsMetadata(metadata(ALIPAY_PACKAGE), STARTED_AT + 1L))

        controller = newController()
        assertFalse(controller.state.value.isActive)
        assertFalse(controller.acceptsMetadata(metadata(ALIPAY_PACKAGE), STARTED_AT + 1L))
    }

    @Test
    fun stopRejectsAlreadyQueuedWorkBeforeItCanReachDisk() = runBlocking {
        controller.start(setOf(ALIPAY_PACKAGE))
        controller.stop()

        controller.record(
            metadata = metadata(ALIPAY_PACKAGE),
            postedAtEpochMillis = STARTED_AT + 1L,
            content = fixtureContent("queued-after-stop"),
        )

        assertEquals(0, controller.state.value.sampleCount)
        assertFalse(sampleFile().readText(Charsets.UTF_8).contains("queued-after-stop"))
    }

    @Test
    fun aFreshControllerRestoresTheActiveWindowAndExistingSampleCount() = runBlocking {
        controller.start(setOf(ALIPAY_PACKAGE))
        controller.record(
            metadata = metadata(ALIPAY_PACKAGE),
            postedAtEpochMillis = STARTED_AT,
            content = fixtureContent("restored-process-fixture"),
        )
        assertTrue(sampleFile().exists())

        controller = newController()

        assertTrue(sampleFile().exists())
        assertTrue(controller.state.value.isActive)
        assertEquals(1, controller.state.value.sampleCount)
        assertTrue(controller.acceptsMetadata(metadata(ALIPAY_PACKAGE), STARTED_AT + 1L))
    }

    @Test
    fun aRecreatedControllerRemainsActiveLongAfterTheOriginalStart() = runBlocking {
        controller.start(setOf(ALIPAY_PACKAGE))
        controller.record(
            metadata = metadata(ALIPAY_PACKAGE),
            postedAtEpochMillis = STARTED_AT,
            content = fixtureContent("persistent-window-fixture"),
        )

        controller = newController(
            nowEpochMillis = STARTED_AT + ONE_YEAR_MILLIS,
        )

        assertTrue(controller.state.value.isActive)
        assertEquals(1, controller.state.value.sampleCount)
        assertTrue(sampleFile().exists())
        assertTrue(
            controller.acceptsMetadata(
                metadata(ALIPAY_PACKAGE),
                STARTED_AT + ONE_YEAR_MILLIS,
            ),
        )
    }

    @Test
    fun stopPersistsAcrossARecreatedControllerAndKeepsSamplesForExport() = runBlocking {
        controller.start(setOf(ALIPAY_PACKAGE))
        controller.record(
            metadata = metadata(ALIPAY_PACKAGE),
            postedAtEpochMillis = STARTED_AT,
            content = fixtureContent("stopped-window-fixture"),
        )

        assertEquals(
            NotificationTemplateSamplingOperationResult.APPLIED,
            controller.stop(),
        )
        controller = newController()

        assertFalse(controller.state.value.isActive)
        assertEquals(1, controller.state.value.sampleCount)
        assertTrue(sampleFile().exists())
    }

    @Test
    fun repeatedStartKeepsExistingSamplesAndMovesTheForwardOnlyBoundary() = runBlocking {
        controller.start(setOf(ALIPAY_PACKAGE))
        controller.record(
            metadata = metadata(ALIPAY_PACKAGE),
            postedAtEpochMillis = STARTED_AT,
            content = fixtureContent("first-window-fixture"),
        )

        controller = newController(nowEpochMillis = STARTED_AT + 100L)
        assertEquals(
            NotificationTemplateSamplingOperationResult.APPLIED,
            controller.start(setOf(ALIPAY_PACKAGE)),
        )
        controller.record(
            metadata = metadata(ALIPAY_PACKAGE),
            postedAtEpochMillis = STARTED_AT + 99L,
            content = fixtureContent("before-new-start-fixture"),
        )
        controller.record(
            metadata = metadata(ALIPAY_PACKAGE),
            postedAtEpochMillis = STARTED_AT + 100L,
            content = fixtureContent("second-window-fixture"),
        )

        assertTrue(controller.state.value.isActive)
        assertEquals(2, controller.state.value.sampleCount)
        val stored = sampleFile().readText(Charsets.UTF_8)
        assertTrue(stored.contains("first-window-fixture"))
        assertTrue(stored.contains("second-window-fixture"))
        assertFalse(stored.contains("before-new-start-fixture"))
    }

    @Test
    fun truncatedPrivateFileNeverRestoresAnOtherwiseActiveWindow() = runBlocking {
        controller.start(setOf(ALIPAY_PACKAGE))
        sampleFile().appendText("truncated-without-newline", Charsets.UTF_8)

        controller = newController()

        assertFalse(controller.state.value.isActive)
        assertEquals(
            NotificationTemplateSamplingFailure.STORAGE_UNAVAILABLE,
            controller.state.value.failure,
        )
        assertTrue(sampleFile().exists())
    }

    private fun newController(
        nowEpochMillis: Long = STARTED_AT,
    ) = BuildVariantNotificationTemplateSamplingController(
        context = targetContext,
        clock = Clock.fixed(Instant.ofEpochMilli(nowEpochMillis), ZoneOffset.UTC),
        sampleFileName = TEST_SAMPLE_FILE_NAME,
        preferencesName = TEST_PREFERENCES_NAME,
    )

    private fun sampleFile() = File(targetContext.noBackupFilesDir, TEST_SAMPLE_FILE_NAME)

    private fun metadata(packageName: String) = NotificationMetadata(
        packageName = packageName,
        channelId = "fixture-channel",
        category = "status",
    )

    private fun fixtureContent(title: String): NotificationContent =
        requireNotNull(
            NotificationContent.from(
                mapOf(
                    NotificationField.TITLE to title,
                    NotificationField.TEXT to "fixture-text",
                ),
            ),
        )

    private companion object {
        const val STARTED_AT = 1_000L
        const val ONE_YEAR_MILLIS = 365L * 24L * 60L * 60L * 1_000L
        const val TEST_SAMPLE_FILE_NAME =
            "notification-template-samples.instrumentation-test.ndjson"
        const val TEST_PREFERENCES_NAME =
            "bill.notification-template-sampling.instrumentation-test"
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
