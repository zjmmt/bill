package dev.bill.source.contract

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortsContractTest {
    @Test
    fun `evidence reader validates and forwards a positive byte limit`() = runTest {
        var observedLimit = -1L
        val reader = object : EvidenceReader() {
            override suspend fun readBounded(
                payloadId: PayloadId,
                maxBytes: Long,
            ): EvidenceReadResult {
                observedLimit = maxBytes
                return EvidenceReadResult.NotFound
            }
        }

        assertEquals(EvidenceReadResult.NotFound, reader.read(PayloadId("payload-1"), 4096))
        assertEquals(4096L, observedLimit)
        var rejectedInvalidLimit = false
        try {
            reader.read(PayloadId("payload-1"), 0)
        } catch (_: IllegalArgumentException) {
            rejectedInvalidLimit = true
        }
        assertTrue(rejectedInvalidLimit)
    }
}
