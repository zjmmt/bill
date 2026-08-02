package dev.bill.app.quickcapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCaptureFlashFeedbackTest {
    @Test
    fun `flash is visible immediately and removed after the bounded receipt`() {
        val surface = FakeSurface()
        val scheduler = FakeScheduler()
        val feedback = QuickCaptureFlashFeedback(surface, scheduler)

        feedback.show()

        assertTrue(surface.visible)
        assertEquals(1, surface.showCount)
        assertEquals(listOf(QUICK_CAPTURE_FLASH_VISIBLE_MILLIS), scheduler.delays)

        scheduler.run(0)

        assertFalse(surface.visible)
        assertEquals(1, surface.hideCount)
    }

    @Test
    fun `older removal cannot hide a newer screenshot flash`() {
        val surface = FakeSurface()
        val scheduler = FakeScheduler()
        val feedback = QuickCaptureFlashFeedback(surface, scheduler)

        feedback.show()
        feedback.show()
        scheduler.run(0)

        assertTrue(surface.visible)
        assertEquals(2, surface.showCount)
        assertEquals(0, surface.hideCount)

        scheduler.run(1)

        assertFalse(surface.visible)
        assertEquals(1, surface.hideCount)
    }

    @Test
    fun `service teardown removes the flash and invalidates delayed work`() {
        val surface = FakeSurface()
        val scheduler = FakeScheduler()
        val feedback = QuickCaptureFlashFeedback(surface, scheduler)

        feedback.show()
        feedback.clear()
        scheduler.run(0)

        assertFalse(surface.visible)
        assertEquals(1, surface.hideCount)
    }

    private class FakeSurface : QuickCaptureFlashSurface {
        var visible = false
        var showCount = 0
        var hideCount = 0

        override fun show(): Boolean {
            visible = true
            showCount += 1
            return true
        }

        override fun hide() {
            if (visible) hideCount += 1
            visible = false
        }
    }

    private class FakeScheduler : QuickCaptureFlashScheduler {
        val delays = mutableListOf<Long>()
        private val tasks = mutableListOf<() -> Unit>()

        override fun postDelayed(delayMillis: Long, action: () -> Unit) {
            delays += delayMillis
            tasks += action
        }

        fun run(index: Int) = tasks[index].invoke()
    }
}
