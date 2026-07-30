package dev.bill.app.quickcapture

import dev.bill.application.PhotoOcrTranscriptCapture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `disconnect waits for a capture that crossed its commit boundary`() {
        val controller = SingleFlightQuickCaptureController()
        val gateway = FakeGateway()
        val outcomes = mutableListOf<QuickCaptureOutcome>()
        controller.attach(gateway)
        controller.request("first", outcomes::add)
        gateway.markCompleted(0)

        controller.detach(gateway)

        assertTrue(outcomes.isEmpty())
        assertEquals(
            QuickCaptureRequestDisposition.BUSY,
            controller.request("blocked-until-callback") {},
        )
        gateway.invokeCompletion(0, QuickCaptureOutcome.Saved(alreadyPresent = false))
        assertEquals(listOf(QuickCaptureOutcome.Saved(false)), outcomes)
        assertEquals(
            QuickCaptureRequestDisposition.SERVICE_UNAVAILABLE,
            controller.request("disconnected") {},
        )
    }

    @Test
    fun `disconnect releases an unreported committed capture after one grace period`() {
        val scheduler = FakeTimeoutScheduler()
        val controller = SingleFlightQuickCaptureController(
            timeoutMillis = 100L,
            timeoutScheduler = scheduler,
        )
        val gateway = FakeGateway()
        val outcomes = mutableListOf<QuickCaptureOutcome>()
        controller.attach(gateway)
        controller.request("first", outcomes::add)
        gateway.markCompleted(0)

        controller.detach(gateway)

        assertEquals(listOf(100L, 15_000L), scheduler.delays)
        scheduler.run(1)
        assertEquals(
            listOf(
                QuickCaptureOutcome.Failed(QuickCaptureFailure.RESULT_UNCONFIRMED),
            ),
            outcomes,
        )
        assertEquals(
            QuickCaptureRequestDisposition.SERVICE_UNAVAILABLE,
            controller.request("after-grace") {},
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

    @Test
    fun `replacement waits for committed old capture then admits new request`() {
        val controller = SingleFlightQuickCaptureController()
        val stale = FakeGateway()
        val current = FakeGateway()
        val outcomes = mutableListOf<QuickCaptureOutcome>()
        controller.attach(stale)
        controller.request("stale", outcomes::add)
        stale.markCompleted(0)

        controller.attach(current)

        assertTrue(outcomes.isEmpty())
        assertEquals(
            QuickCaptureRequestDisposition.BUSY,
            controller.request("too-early", outcomes::add),
        )
        stale.invokeCompletion(0, QuickCaptureOutcome.Saved(alreadyPresent = false))
        assertEquals(listOf(QuickCaptureOutcome.Saved(false)), outcomes)
        assertEquals(
            QuickCaptureRequestDisposition.STARTED,
            controller.request("current", outcomes::add),
        )
        assertEquals(listOf("current"), current.commandIds)
    }

    @Test
    fun `missing callback times out and a late callback cannot complete the next request`() {
        val scheduler = FakeTimeoutScheduler()
        val controller = SingleFlightQuickCaptureController(
            timeoutMillis = 12_345L,
            timeoutScheduler = scheduler,
        )
        val gateway = FakeGateway()
        val outcomes = mutableListOf<QuickCaptureOutcome>()
        controller.attach(gateway)

        assertEquals(
            QuickCaptureRequestDisposition.STARTED,
            controller.request("first", outcomes::add),
        )
        assertEquals(listOf(12_345L), scheduler.delays)

        scheduler.run(0)

        assertTrue(gateway.wasCancelled(0))
        assertEquals(
            listOf(QuickCaptureOutcome.Failed(QuickCaptureFailure.TIMED_OUT)),
            outcomes,
        )
        assertEquals(
            QuickCaptureRequestDisposition.STARTED,
            controller.request("second", outcomes::add),
        )

        gateway.complete(0, QuickCaptureOutcome.Saved(alreadyPresent = false))
        assertEquals(
            listOf(QuickCaptureOutcome.Failed(QuickCaptureFailure.TIMED_OUT)),
            outcomes,
        )

        gateway.complete(1, QuickCaptureOutcome.Saved(alreadyPresent = false))
        assertEquals(
            listOf(
                QuickCaptureOutcome.Failed(QuickCaptureFailure.TIMED_OUT),
                QuickCaptureOutcome.Saved(alreadyPresent = false),
            ),
            outcomes,
        )
    }

    @Test
    fun `normal completion cancels its timeout`() {
        val scheduler = FakeTimeoutScheduler()
        val controller = SingleFlightQuickCaptureController(
            timeoutMillis = 100L,
            timeoutScheduler = scheduler,
        )
        val gateway = FakeGateway()
        val outcomes = mutableListOf<QuickCaptureOutcome>()
        controller.attach(gateway)
        controller.request("first", outcomes::add)

        gateway.complete(QuickCaptureOutcome.Saved(alreadyPresent = false))
        scheduler.run(0)

        assertEquals(listOf(QuickCaptureOutcome.Saved(false)), outcomes)
    }

    @Test
    fun `timeout defers to an underlying operation that already completed`() {
        val scheduler = FakeTimeoutScheduler()
        val controller = SingleFlightQuickCaptureController(
            timeoutMillis = 100L,
            timeoutScheduler = scheduler,
        )
        val gateway = FakeGateway()
        val outcomes = mutableListOf<QuickCaptureOutcome>()
        controller.attach(gateway)
        controller.request("first", outcomes::add)
        gateway.markCompleted(0)

        scheduler.run(0)

        assertTrue(outcomes.isEmpty())
        assertEquals(
            QuickCaptureRequestDisposition.BUSY,
            controller.request("still-resolving") {},
        )

        gateway.invokeCompletion(0, QuickCaptureOutcome.Saved(alreadyPresent = false))
        assertEquals(listOf(QuickCaptureOutcome.Saved(false)), outcomes)
    }

    @Test
    fun `commit without callback releases gate with an accurate bounded outcome`() {
        val scheduler = FakeTimeoutScheduler()
        val controller = SingleFlightQuickCaptureController(
            timeoutMillis = 100L,
            timeoutScheduler = scheduler,
        )
        val gateway = FakeGateway()
        val outcomes = mutableListOf<QuickCaptureOutcome>()
        controller.attach(gateway)
        controller.request("first", outcomes::add)
        gateway.markCompleted(0)

        scheduler.run(0)
        assertEquals(listOf(100L, 15_000L), scheduler.delays)
        assertTrue(outcomes.isEmpty())

        scheduler.run(1)

        assertEquals(
            listOf(
                QuickCaptureOutcome.Failed(QuickCaptureFailure.RESULT_UNCONFIRMED),
            ),
            outcomes,
        )
        assertEquals(
            QuickCaptureRequestDisposition.STARTED,
            controller.request("second", outcomes::add),
        )
        gateway.invokeCompletion(0, QuickCaptureOutcome.Saved(alreadyPresent = false))
        assertEquals(
            listOf(
                QuickCaptureOutcome.Failed(QuickCaptureFailure.RESULT_UNCONFIRMED),
            ),
            outcomes,
        )
    }

    @Test
    fun `finalization scheduling failure releases the gate immediately`() {
        var scheduleCount = 0
        lateinit var initialTimeout: () -> Unit
        val controller = SingleFlightQuickCaptureController(
            timeoutMillis = 100L,
            timeoutScheduler = QuickCaptureTimeoutScheduler { _, task ->
                scheduleCount += 1
                if (scheduleCount == 1) {
                    initialTimeout = task
                    QuickCaptureTimeoutHandle {}
                } else {
                    throw IllegalStateException("simulated scheduler failure")
                }
            },
        )
        val gateway = FakeGateway()
        val outcomes = mutableListOf<QuickCaptureOutcome>()
        controller.attach(gateway)
        controller.request("first", outcomes::add)
        gateway.markCompleted(0)

        initialTimeout()

        assertEquals(
            listOf(
                QuickCaptureOutcome.Failed(QuickCaptureFailure.RESULT_UNCONFIRMED),
            ),
            outcomes,
        )
        assertEquals(
            QuickCaptureRequestDisposition.STARTED,
            controller.request("second", outcomes::add),
        )
    }

    @Test
    fun `timeout that fires during scheduling does not start a stale capture`() {
        val controller = SingleFlightQuickCaptureController(
            timeoutMillis = 1L,
            timeoutScheduler = QuickCaptureTimeoutScheduler { _, task ->
                task()
                QuickCaptureTimeoutHandle {}
            },
        )
        val gateway = FakeGateway()
        val outcomes = mutableListOf<QuickCaptureOutcome>()
        controller.attach(gateway)

        assertEquals(
            QuickCaptureRequestDisposition.STARTED,
            controller.request("expired-before-capture", outcomes::add),
        )

        assertTrue(gateway.commandIds.isEmpty())
        assertEquals(
            listOf(QuickCaptureOutcome.Failed(QuickCaptureFailure.TIMED_OUT)),
            outcomes,
        )
    }

    @Test
    fun `timeout while cancellation handle is registering cannot misreport a later commit`() {
        val scheduler = FakeTimeoutScheduler()
        val enteredCapture = CountDownLatch(1)
        val releaseHandle = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val outcomes = CopyOnWriteArrayList<QuickCaptureOutcome>()
        val gateway = QuickScreenshotGateway { _, _ ->
            enteredCapture.countDown()
            releaseHandle.await(5L, TimeUnit.SECONDS)
            QuickCaptureCancellation {
                cancelled.set(true)
                true
            }
        }
        val controller = SingleFlightQuickCaptureController(
            timeoutMillis = 100L,
            timeoutScheduler = scheduler,
        )
        controller.attach(gateway)
        val requestThread = Thread {
            controller.request("registering", outcomes::add)
        }

        requestThread.start()
        assertTrue(enteredCapture.await(5L, TimeUnit.SECONDS))
        scheduler.run(0)

        assertTrue(outcomes.isEmpty())
        assertEquals(listOf(100L, 15_000L), scheduler.delays)

        releaseHandle.countDown()
        requestThread.join(5_000L)

        assertFalse(requestThread.isAlive)
        assertTrue(cancelled.get())
        assertEquals(
            listOf(QuickCaptureOutcome.Failed(QuickCaptureFailure.TIMED_OUT)),
            outcomes,
        )
    }

    @Test
    fun `capture cancellation signal cancels attached and late jobs`() {
        val attachedSignal = QuickCaptureCancellationSignal()
        val attachedJob = Job()
        attachedSignal.attach(attachedJob)

        assertTrue(attachedSignal.cancel())
        assertTrue(attachedJob.isCancelled)

        val lateSignal = QuickCaptureCancellationSignal()
        val lateJob = Job()
        assertTrue(lateSignal.cancel())
        lateSignal.attach(lateJob)
        assertTrue(lateJob.isCancelled)
    }

    @Test
    fun `completed capture cannot be reclassified as timed out`() {
        val signal = QuickCaptureCancellationSignal()

        assertTrue(signal.complete())
        assertFalse(signal.cancel())
    }

    @Test
    fun `local commit claim wins atomically over cancellation and keeps its job alive`() {
        val signal = QuickCaptureCancellationSignal()
        val job = Job()

        assertTrue(signal.tryBeginLocalCommit())
        signal.attach(job)

        assertFalse(signal.cancel())
        assertFalse(job.isCancelled)
        assertTrue(signal.complete())
    }

    @Test
    fun `cancellation prevents a later local commit claim`() {
        val signal = QuickCaptureCancellationSignal()

        assertTrue(signal.cancel())
        assertFalse(signal.tryBeginLocalCommit())
        assertFalse(signal.complete())
    }

    @Test
    fun `encoded transcript bytes are wiped when cancellation wins before ingestion`() =
        runBlocking {
            val signal = QuickCaptureCancellationSignal()
            val bytes = "private OCR text".toByteArray()
            assertTrue(signal.cancel())
            var cancelled = false

            try {
                ingestQuickCaptureTranscript(
                    commandId = "cancelled",
                    transcriptBytes = bytes,
                    cancellation = signal,
                    capture = PhotoOcrTranscriptCapture.Unavailable,
                )
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertTrue(bytes.all { it == 0.toByte() })
        }

    private class FakeGateway : QuickScreenshotGateway {
        val commandIds = mutableListOf<String>()
        private val requests = mutableListOf<Request>()

        override fun capture(
            commandId: String,
            completion: (QuickCaptureOutcome) -> Unit,
        ): QuickCaptureCancellation {
            commandIds += commandId
            val request = Request(completion)
            requests += request
            return QuickCaptureCancellation {
                when (request.state) {
                    RequestState.COMPLETED -> false
                    RequestState.CANCELLED -> true
                    RequestState.ACTIVE -> {
                        request.state = RequestState.CANCELLED
                        true
                    }
                }
            }
        }

        fun complete(outcome: QuickCaptureOutcome) {
            requests.lastOrNull()?.let { request ->
                if (request.state == RequestState.ACTIVE) {
                    request.state = RequestState.COMPLETED
                }
                request.completion(outcome)
            }
        }

        fun complete(index: Int, outcome: QuickCaptureOutcome) {
            val request = requests[index]
            if (request.state == RequestState.ACTIVE) {
                request.state = RequestState.COMPLETED
            }
            request.completion(outcome)
        }

        fun markCompleted(index: Int) {
            requests[index].state = RequestState.COMPLETED
        }

        fun invokeCompletion(index: Int, outcome: QuickCaptureOutcome) {
            requests[index].completion(outcome)
        }

        fun wasCancelled(index: Int): Boolean =
            requests[index].state == RequestState.CANCELLED

        private data class Request(
            val completion: (QuickCaptureOutcome) -> Unit,
            var state: RequestState = RequestState.ACTIVE,
        )

        private enum class RequestState {
            ACTIVE,
            CANCELLED,
            COMPLETED,
        }
    }

    private class FakeTimeoutScheduler : QuickCaptureTimeoutScheduler {
        val delays = mutableListOf<Long>()
        private val tasks = mutableListOf<ScheduledTask>()

        override fun schedule(
            delayMillis: Long,
            task: () -> Unit,
        ): QuickCaptureTimeoutHandle {
            delays += delayMillis
            val scheduled = ScheduledTask(task)
            tasks += scheduled
            return QuickCaptureTimeoutHandle { scheduled.cancelled = true }
        }

        fun run(index: Int) {
            tasks[index].run()
        }

        private class ScheduledTask(
            private val task: () -> Unit,
        ) {
            var cancelled = false

            fun run() {
                if (!cancelled) task()
            }
        }
    }
}
