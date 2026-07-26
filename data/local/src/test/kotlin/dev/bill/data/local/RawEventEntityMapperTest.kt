package dev.bill.data.local

import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawEventEntityMapperTest {
    @Test
    fun validRawEventRoundTripsWithoutChangingEvidenceIdentity() {
        val event = event()

        val restored = RawEventEntityMapper.toDomain(RawEventEntityMapper.toEntity(event))

        assertEquals(event, restored)
    }

    @Test
    fun corruptRowsFailClosedWithoutEchoingStoredValues() {
        val valid = RawEventEntityMapper.toEntity(event())
        val corruptRows = listOf(
            valid.copy(id = "invalid:id"),
            valid.copy(sourceFamily = "PRIVATE_SOURCE_VALUE"),
            valid.copy(connectorId = "invalid/connector"),
            valid.copy(captureMethod = "PRIVATE_METHOD_VALUE"),
            valid.copy(captureScope = ""),
            valid.copy(contentHash = "A".repeat(64)),
            valid.copy(payloadReference = "private://payload/location"),
        )

        corruptRows.forEach { row ->
            val failure = runCatching { RawEventEntityMapper.toDomain(row) }.exceptionOrNull()

            assertTrue(failure is LocalDataIntegrityException)
            assertEquals(SafeFailureMessage, failure?.message)
            assertFalse(failure?.message.orEmpty().contains("PRIVATE"))
            assertFalse(failure?.message.orEmpty().contains("private://"))
        }
    }

    @Test
    fun instantOutsideEpochMillisRangeFailsClosed() {
        val failure = runCatching {
            RawEventEntityMapper.toEntity(event().copy(capturedAt = Instant.MAX))
        }.exceptionOrNull()

        assertTrue(failure is LocalDataIntegrityException)
        assertEquals(SafeFailureMessage, failure?.message)
    }

    private fun event() = RawEvent(
        id = RawEventId("fixture-event-1"),
        sourceFamily = SourceFamily.BANK,
        connectorId = ConnectorId("fixture-connector"),
        captureMethod = CaptureMethod.STATEMENT_IMPORT,
        captureScope = CaptureScopeId("FIXTURE_ONLY"),
        contentHash = EvidenceHash(ValidHash),
        capturedAt = Instant.parse("2025-07-20T09:46:40Z"),
        payloadId = PayloadId("fixture-payload-1"),
    )

    private companion object {
        const val ValidHash =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SafeFailureMessage = "Local ledger data failed validation: raw event"
    }
}
