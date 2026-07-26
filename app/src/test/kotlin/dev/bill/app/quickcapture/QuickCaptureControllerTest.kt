package dev.bill.app.quickcapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCaptureControllerTest {
    @Test
    fun `disconnected controller rejects without invoking completion`() {
        val controller = SingleFlightQuickCaptureController()
        var completed = false

        val disposition = controller.request("command") { completed = true }

        assertEquals(QuickCaptureRequestDisposition.SERVICE_UNAVAILABLE, disposition)
        assertEquals(
            QuickCaptureConnectionState.DISCONNECTED,
            controller.connectionState.value,
        )
        assertTrue(!completed)
    }

    @Test
    fun `only one request runs and completion reopens the gate`() {
        val controller = SingleFlightQuickCaptureController()
        val gateway = FakeGateway()
        controller.attach(gateway)
        val outcomes = mutableListOf<QuickCaptureOutcome>()

        assertEquals(
            QuickCaptureRequestDisposition.STARTED,
            controller.request("first", outcomes::add),
        )
        assertEquals(
            QuickCaptureRequestDisposition.BUSY,
            controller.request("second", outcomes::add),
        )
        gateway.complete(QuickCaptureOutcome.Saved(alreadyPresent = false))
        assertEquals(listOf(QuickCaptureOutcome.Saved(false)), outcomes)

        assertEquals(
            QuickCaptureRequestDisposition.STARTED,
            controller.request("third", outcomes::add),
        )
        assertEquals(listOf("first", "third"), gateway.commandIds)
    }

    @Test
    fun `disconnect fails active command once and ignores late callback`() {
        val controller = SingleFlightQuickCaptureController()
        val gateway = FakeGateway()
        controller.attach(gateway)
        val outcomes = mutableListOf<QuickCaptureOutcome>()
        controller.request("first", outcomes::add)

        controller.detach(gateway)
        gateway.complete(QuickCaptureOutcome.Saved(alreadyPresent = false))

        assertEquals(
            listOf(
                QuickCaptureOutcome.Failed(QuickCaptureFailure.SERVICE_DISCONNECTED),
            ),
            outcomes,
        )
        assertEquals(
            QuickCaptureConnectionState.DISCONNECTED,
            controller.connectionState.value,
        )
    }

    @Test
    fun `a stale service cannot detach a replacement`() {
        val controller = SingleFlightQuickCaptureController()
        val stale = FakeGateway()
        val current = FakeGateway()
        controller.attach(stale)
        controller.attach(current)

        controller.detach(stale)

        assertEquals(
            QuickCaptureConnectionState.CONNECTED,
            controller.connectionState.value,
        )
        assertEquals(
            QuickCaptureRequestDisposition.STARTED,
            controller.request("current") {},
        )
        assertEquals(listOf("current"), current.commandIds)
    }

    @Test
    fun `attaching replacement fails an old active request and admits a new one`() {
        val controller = SingleFlightQuickCaptureController()
        val stale = FakeGateway()
        val current = FakeGateway()
        val outcomes = mutableListOf<QuickCaptureOutcome>()
        controller.attach(stale)
        controller.request("stale", outcomes::add)

        controller.attach(current)
        val disposition = controller.request("current", outcomes::add)

        assertEquals(
            listOf(
                QuickCaptureOutcome.Failed(QuickCaptureFailure.SERVICE_DISCONNECTED),
            ),
            outcomes,
        )
        assertEquals(QuickCaptureRequestDisposition.STARTED, disposition)
        assertEquals(listOf("current"), current.commandIds)
    }

    private class FakeGateway : QuickScreenshotGateway {
        val commandIds = mutableListOf<String>()
        private var completion: ((QuickCaptureOutcome) -> Unit)? = null

        override fun capture(
            commandId: String,
            completion: (QuickCaptureOutcome) -> Unit,
        ) {
            commandIds += commandId
            this.completion = completion
        }

        fun complete(outcome: QuickCaptureOutcome) {
            completion?.invoke(outcome)
        }
    }
}
