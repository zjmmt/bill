package dev.bill.app.notification

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BoundedNotificationWorkQueueTest {
    @Test
    fun `queue rejects excess work instead of growing without bound`() {
        val queue = BoundedNotificationWorkQueue<String>(capacity = 2)

        assertEquals(NotificationQueueOfferResult.ENQUEUED, queue.offer("first"))
        assertEquals(NotificationQueueOfferResult.ENQUEUED, queue.offer("second"))
        assertEquals(NotificationQueueOfferResult.FULL, queue.offer("private-third"))

        queue.close()
        assertEquals(NotificationQueueOfferResult.CLOSED, queue.offer("after-close"))
        assertFalse(queue.toString().contains("private-third"))
    }

    @Test
    fun `closed queue drains accepted work in order without exposing an inspection API`() = runBlocking {
        val queue = BoundedNotificationWorkQueue<String>(capacity = 3)
        queue.offer("first")
        queue.offer("second")
        queue.close()

        val consumed = mutableListOf<String>()
        queue.consumeEach { consumed += it }

        assertEquals(listOf("first", "second"), consumed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `queue capacity cannot be zero`() {
        BoundedNotificationWorkQueue<String>(capacity = 0)
    }
}
