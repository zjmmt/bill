package dev.bill.source.contract

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EvidenceContractTest {
    @Test
    fun `evidence hash is canonical lowercase sha256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            EvidenceHash.fromBytes("abc".toByteArray()).value,
        )
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceHash("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceHash("abc")
        }
    }

    @Test
    fun `evidence input copies data at both boundaries`() {
        val original = "financial-secret".toByteArray()
        val input = EvidenceInput("text/plain; charset=utf-8", original)
        original.fill(0)

        val firstRead = input.copyBytes()
        assertArrayEquals("financial-secret".toByteArray(), firstRead)
        firstRead.fill(0)
        assertArrayEquals("financial-secret".toByteArray(), input.copyBytes())
        assertEquals(16L, input.sizeBytes)
    }

    @Test
    fun `evidence input toString never reveals content`() {
        val input = EvidenceInput("text/plain", "do-not-log-this".toByteArray())

        assertEquals("EvidenceInput(sizeBytes=15)", input.toString())
        assertFalse(input.toString().contains("do-not-log-this"))
        assertFalse(input.toString().contains("text/plain"))
    }

    @Test
    fun `ranges must be non-empty and start at a valid offset`() {
        assertEquals(7L, EvidenceLocator.ByteRange(3, 7).endExclusive)
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceLocator.ByteRange(-1, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceLocator.ByteRange(2, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceLocator.TextRange(4, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceLocator.TableCell(-1, 0)
        }
    }
}
