package dev.bill.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationObservationIdDeriverTest {
    @Test
    fun `same transient key and post time produce a stable opaque digest`() {
        val deriver = HmacNotificationObservationIdDeriver { ByteArray(32) { 7 } }

        val first = checkNotNull(deriver.derive("private-notification-key", 1_700_000_000_000L))
        val second = checkNotNull(deriver.derive("private-notification-key", 1_700_000_000_000L))

        assertEquals(first, second)
        assertFalse(first.toString().contains("private-notification-key"))
        assertFalse(first.toString().contains(first.value))
    }

    @Test
    fun `new post time and malformed transient identity do not reuse an observation`() {
        val deriver = HmacNotificationObservationIdDeriver { ByteArray(32) { 7 } }

        val original = checkNotNull(deriver.derive("private-notification-key", 1_700_000_000_000L))
        val reposted = checkNotNull(deriver.derive("private-notification-key", 1_700_000_000_001L))

        assertNotEquals(original, reposted)
        assertNull(deriver.derive("", 1_700_000_000_000L))
        assertNull(deriver.derive("private-notification-key", -1L))
    }
}
