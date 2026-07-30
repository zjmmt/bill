package dev.bill.source.genericdelimited

import dev.bill.core.model.CurrencyCode
import dev.bill.source.contract.CaptureMethod
import dev.bill.source.contract.CaptureScopeId
import dev.bill.source.contract.ConnectorId
import dev.bill.source.contract.EvidenceHash
import dev.bill.source.contract.EvidenceInput
import dev.bill.source.contract.ParseResult
import dev.bill.source.contract.PayloadId
import dev.bill.source.contract.RawEvent
import dev.bill.source.contract.RawEventId
import dev.bill.source.contract.SourceFamily
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DelimitedStatementRowEvidenceCodecTest {
    @Test
    fun `round trips versioned row evidence and deterministic mapping hash`() {
        val mapping = mapping()
        val row = row()
        val bytes = DelimitedStatementRowEvidenceCodec.encode(fileHash(), row, mapping)

        val decoded = DelimitedStatementRowEvidenceCodec.decode(bytes)
            as DelimitedRowEvidenceDecodeResult.Decoded

        assertEquals(fileHash(), decoded.evidence.fileHash)
        assertEquals(row, decoded.evidence.row)
        assertEquals(mapping, decoded.evidence.mapping)
        assertEquals(
            DelimitedStatementRowEvidenceCodec.mappingHash(mapping),
            decoded.evidence.mappingHash,
        )
        assertEquals(
            DelimitedStatementRowEvidenceCodec.mappingHash(mapping),
            DelimitedStatementRowEvidenceCodec.mappingHash(mapping.copy()),
        )
        assertNotEquals(
            DelimitedStatementRowEvidenceCodec.mappingHash(mapping),
            DelimitedStatementRowEvidenceCodec.mappingHash(
                mapping.copy(currency = CurrencyCode.USD),
            ),
        )
    }

    @Test
    fun `rejects trailing bytes and mapping hash tampering`() {
        val bytes = DelimitedStatementRowEvidenceCodec.encode(fileHash(), row(), mapping())
        val trailing = bytes + 0x01
        assertEquals(
            DelimitedRowEvidenceDecodeResult.Malformed,
            DelimitedStatementRowEvidenceCodec.decode(trailing),
        )

        val tampered = bytes.copyOf()
        // Magic (8) + version (4) + file hash (64), then the first mapping-hash byte.
        val mappingHashOffset = 8 + 4 + 64
        tampered[mappingHashOffset] = if (tampered[mappingHashOffset] == 'a'.code.toByte()) {
            'b'.code.toByte()
        } else {
            'a'.code.toByte()
        }
        assertEquals(
            DelimitedRowEvidenceDecodeResult.Malformed,
            DelimitedStatementRowEvidenceCodec.decode(tampered),
        )
    }

    @Test
    fun `parser accepts only its row connector and produces a candidate`() {
        val parser = GenericDelimitedStatementParser()
        val bytes = DelimitedStatementRowEvidenceCodec.encode(fileHash(), row(), mapping())
        val event = RawEvent(
            id = RawEventId("row-test"),
            sourceFamily = SourceFamily.GENERIC,
            connectorId = GenericDelimitedStatementParser.CONNECTOR_ID,
            captureMethod = CaptureMethod.STATEMENT_IMPORT,
            captureScope = CaptureScopeId("local-install"),
            contentHash = EvidenceHash.fromBytes(bytes),
            capturedAt = Instant.EPOCH,
            payloadId = PayloadId("row-test"),
            payloadSizeBytes = bytes.size.toLong(),
        )

        val result = parser.parse(
            event,
            EvidenceInput(DelimitedStatementRowEvidenceCodec.MEDIA_TYPE, bytes),
        )

        assertTrue(result is ParseResult.Parsed)
        assertEquals(
            1_234L,
            (result as ParseResult.Parsed).candidate.amount?.value?.minorUnits,
        )

        val wrongConnector = parser.parse(
            event.copy(connectorId = ConnectorId("other-connector")),
            EvidenceInput(DelimitedStatementRowEvidenceCodec.MEDIA_TYPE, bytes),
        )
        assertTrue(wrongConnector is ParseResult.Rejected)
    }

    private fun mapping() = DelimitedStatementMapping(
        delimiter = DelimitedDelimiter.COMMA,
        dateColumnIndex = 0,
        dateFormat = StatementDateFormat.DATE_DASH,
        amountColumnIndex = 1,
        amountFormat = StatementAmountFormat.DOT_DECIMAL,
        directionMapping = StatementDirectionMapping.DirectionColumn(
            columnIndex = 2,
            inboundTokens = setOf("income", "收入"),
            outboundTokens = setOf("expense", "支出"),
        ),
        counterpartyColumnIndex = 3,
        referenceColumnIndex = 4,
        currency = CurrencyCode.CNY,
    )

    private fun row() = DelimitedRow(
        tableRowIndex = 4,
        cells = listOf("2026-07-01", "12.34", "expense", "Coffee", "R-1"),
    )

    private fun fileHash() = EvidenceHash.fromBytes(
        "synthetic-file".toByteArray(StandardCharsets.UTF_8),
    )
}
