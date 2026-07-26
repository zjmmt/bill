package dev.bill.source.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentifierContractTest {
    @Test
    fun `opaque ids accept bounded ascii tokens`() {
        assertEquals("connector.v1", ConnectorId("connector.v1").value)
        assertEquals("provider-a", ProviderId("provider-a").value)
        assertEquals("scope_1", CaptureScopeId("scope_1").value)
        assertEquals("payload-1", PayloadId("payload-1").value)
        assertEquals("parser-1", ParserId("parser-1").value)
        assertEquals("1.2.3", VersionId("1.2.3").value)
    }

    @Test
    fun `opaque ids reject whitespace unicode and overlong values`() {
        listOf(
            "",
            "has space",
            "账单",
            "a".repeat(129),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ConnectorId(invalid)
            }
        }
    }

    @Test
    fun `payload id rejects paths and uri shapes`() {
        listOf(
            "content://provider/item",
            "file:///tmp/evidence",
            "/tmp/evidence",
            "C:\\evidence\\payload",
            "folder/payload",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                PayloadId(invalid)
            }
        }
    }
}
