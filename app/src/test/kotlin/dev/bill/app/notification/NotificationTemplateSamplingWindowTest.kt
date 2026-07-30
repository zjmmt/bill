package dev.bill.app.notification

import dev.bill.source.genericnotification.NotificationMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTemplateSamplingWindowTest {
    @Test
    fun rejectsEventsBeforeStartAndPackagesOutsideTheExplicitSet() {
        val window = NotificationTemplateSamplingWindow()
        assertTrue(
            window.start(
                setOf(ALIPAY_PACKAGE),
                startedAtEpochMillis = 1_000L,
            ),
        )

        assertFalse(window.accepts(metadata(ALIPAY_PACKAGE), postedAtEpochMillis = 999L))
        assertFalse(window.accepts(metadata(WECHAT_PACKAGE), postedAtEpochMillis = 1_000L))
        assertTrue(window.accepts(metadata(ALIPAY_PACKAGE), postedAtEpochMillis = 1_000L))
        assertTrue(window.accepts(metadata(ALIPAY_PACKAGE), postedAtEpochMillis = Long.MAX_VALUE))
    }

    @Test
    fun stopClosesTheWindowImmediately() {
        val window = NotificationTemplateSamplingWindow()
        assertTrue(
            window.start(
                setOf(ALIPAY_PACKAGE),
                startedAtEpochMillis = 1_000L,
            ),
        )

        window.stop()

        assertFalse(window.accepts(metadata(ALIPAY_PACKAGE), postedAtEpochMillis = 2_000L))
        assertNull(window.snapshot())
    }

    @Test
    fun invalidOrEmptyPackageSetsNeverOpenTheWindow() {
        val window = NotificationTemplateSamplingWindow()

        assertFalse(window.start(emptySet(), 1_000L))
        assertFalse(window.start(setOf("not a package"), 1_000L))
        assertFalse(window.start(setOf(ALIPAY_PACKAGE), -1L))
        assertNull(window.snapshot())
    }

    @Test
    fun snapshotDoesNotExposeAMutableTargetSet() {
        val mutablePackages = mutableSetOf(ALIPAY_PACKAGE)
        val window = NotificationTemplateSamplingWindow()
        assertTrue(
            window.start(
                mutablePackages,
                startedAtEpochMillis = 1_000L,
            ),
        )

        mutablePackages += WECHAT_PACKAGE

        assertTrue(window.accepts(metadata(ALIPAY_PACKAGE), postedAtEpochMillis = 1_000L))
        assertFalse(window.accepts(metadata(WECHAT_PACKAGE), postedAtEpochMillis = 1_000L))
    }

    private fun metadata(packageName: String) = NotificationMetadata(
        packageName = packageName,
        channelId = "transaction",
        category = "status",
    )

    private companion object {
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
