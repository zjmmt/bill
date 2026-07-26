package dev.bill.app.notification

import kotlinx.coroutines.channels.Channel

/**
 * A tiny, bounded in-memory hand-off for listener work. It intentionally exposes no inspection
 * or logging API, so callers cannot enumerate queued notification-derived values.
 */
internal class BoundedNotificationWorkQueue<T>(
    capacity: Int,
) {
    init {
        require(capacity in 1..MaxCapacity) {
            "Notification work queue capacity must stay bounded"
        }
    }

    private val channel = Channel<T>(capacity)

    fun offer(value: T): NotificationQueueOfferResult {
        val result = channel.trySend(value)
        return when {
            result.isSuccess -> NotificationQueueOfferResult.ENQUEUED
            result.isClosed -> NotificationQueueOfferResult.CLOSED
            else -> NotificationQueueOfferResult.FULL
        }
    }

    suspend fun consumeEach(consumer: suspend (T) -> Unit) {
        for (value in channel) {
            consumer(value)
        }
    }

    fun close() {
        channel.close()
    }

    override fun toString(): String = "BoundedNotificationWorkQueue(redacted=true)"

    private companion object {
        const val MaxCapacity = 64
    }
}

internal enum class NotificationQueueOfferResult {
    ENQUEUED,
    FULL,
    CLOSED,
}
