package dev.bill.app

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedTextDocumentReaderTest {
    @Test
    fun `bounded copy accepts an exact limit`() {
        val bytes = ByteArray(8) { index -> index.toByte() }

        val result = BoundedDocumentReader.copy(ByteArrayInputStream(bytes), maxBytes = 8)

        assertTrue(result is BoundedDocumentRead.Success)
        result as BoundedDocumentRead.Success
        assertArrayEquals(bytes, result.bytes)
        result.bytes.fill(0)
    }

    @Test
    fun `bounded copy rejects one byte beyond limit`() {
        val result = BoundedDocumentReader.copy(
            ByteArrayInputStream(ByteArray(9) { 7 }),
            maxBytes = 8,
        )

        assertTrue(result is BoundedDocumentRead.TooLarge)
    }

    @Test
    fun `bounded copy preserves a short payload`() {
        val result = BoundedDocumentReader.copy(
            ByteArrayInputStream(byteArrayOf(0x41, 0x42)),
            maxBytes = 8,
        )

        assertTrue(result is BoundedDocumentRead.Success)
        result as BoundedDocumentRead.Success
        assertArrayEquals(byteArrayOf(0x41, 0x42), result.bytes)
        result.bytes.fill(0)
    }
}
