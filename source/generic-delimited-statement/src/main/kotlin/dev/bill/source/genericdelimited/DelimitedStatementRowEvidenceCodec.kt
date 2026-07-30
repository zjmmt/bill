package dev.bill.source.genericdelimited

import dev.bill.core.model.CurrencyCode
import dev.bill.source.contract.EvidenceHash
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class DelimitedStatementRowEvidence(
    val fileHash: EvidenceHash,
    val mappingHash: EvidenceHash,
    val row: DelimitedRow,
    val mapping: DelimitedStatementMapping,
)

sealed interface DelimitedRowEvidenceDecodeResult {
    data class Decoded(val evidence: DelimitedStatementRowEvidence) :
        DelimitedRowEvidenceDecodeResult

    data object Malformed : DelimitedRowEvidenceDecodeResult
}

object DelimitedStatementRowEvidenceCodec {
    const val MEDIA_TYPE = "application/vnd.dev.bill.delimited-statement-row"
    const val MAX_EVIDENCE_BYTES = 512 * 1024

    fun mappingHash(mapping: DelimitedStatementMapping): EvidenceHash {
        val canonical = encodeMapping(mapping)
        return try {
            EvidenceHash.fromBytes(canonical)
        } finally {
            canonical.fill(0)
        }
    }

    fun encode(
        fileHash: EvidenceHash,
        row: DelimitedRow,
        mapping: DelimitedStatementMapping,
    ): ByteArray {
        require(row.cells.size <= DelimitedReadLimits.HARD_MAX_COLUMNS)
        require(row.cells.all { it.length <= DelimitedReadLimits.HARD_MAX_CELL_CHARS })
        require(row.cells.none { '\u0000' in it })

        val mappingBytes = encodeMapping(mapping)
        val calculatedMappingHash = try {
            EvidenceHash.fromBytes(mappingBytes)
        } finally {
            // The mapping is metadata, but clearing temporary canonical bytes keeps ownership
            // consistent with the row payload path.
            mappingBytes.fill(0)
        }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeInt(VERSION)
            data.writeAsciiHash(fileHash)
            data.writeAsciiHash(calculatedMappingHash)
            data.writeInt(row.tableRowIndex)
            writeMapping(data, mapping)
            data.writeInt(row.cells.size)
            row.cells.forEach { cell -> data.writeBoundedUtf8(cell) }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_EVIDENCE_BYTES) {
                "Encoded row evidence exceeds its storage budget"
            }
        }
    }

    fun decode(bytes: ByteArray): DelimitedRowEvidenceDecodeResult {
        if (bytes.isEmpty() || bytes.size > MAX_EVIDENCE_BYTES) {
            return DelimitedRowEvidenceDecodeResult.Malformed
        }
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val magic = ByteArray(MAGIC.size)
                data.readFully(magic)
                if (!magic.contentEquals(MAGIC)) return malformed()
                if (data.readInt() != VERSION) return malformed()

                val fileHash = data.readAsciiHash()
                val storedMappingHash = data.readAsciiHash()
                val rowIndex = data.readInt()
                if (rowIndex <= 0) return malformed()
                val mapping = readMapping(data)
                if (mappingHash(mapping) != storedMappingHash) return malformed()

                val cellCount = data.readInt()
                if (cellCount !in 1..DelimitedReadLimits.HARD_MAX_COLUMNS) return malformed()
                val cells = List(cellCount) { data.readBoundedUtf8() }
                if (data.available() != 0) return malformed()

                DelimitedRowEvidenceDecodeResult.Decoded(
                    DelimitedStatementRowEvidence(
                        fileHash = fileHash,
                        mappingHash = storedMappingHash,
                        row = DelimitedRow(rowIndex, cells),
                        mapping = mapping,
                    ),
                )
            }
        } catch (_: EOFException) {
            malformed()
        } catch (_: CharacterCodingException) {
            malformed()
        } catch (_: IllegalArgumentException) {
            malformed()
        } catch (_: NegativeArraySizeException) {
            malformed()
        }
    }

    private fun encodeMapping(mapping: DelimitedStatementMapping): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writeMapping(it, mapping) }
        return output.toByteArray()
    }

    private fun writeMapping(
        data: DataOutputStream,
        mapping: DelimitedStatementMapping,
    ) {
        data.writeInt(MAPPING_VERSION)
        data.writeInt(mapping.delimiter.ordinal)
        data.writeInt(mapping.dateColumnIndex)
        data.writeInt(mapping.dateFormat.ordinal)
        data.writeInt(mapping.amountColumnIndex)
        data.writeInt(mapping.amountFormat.ordinal)
        when (val direction = mapping.directionMapping) {
            is StatementDirectionMapping.SignedAmount -> {
                data.writeByte(DIRECTION_SIGNED)
                data.writeInt(direction.positiveDirection.ordinal)
            }

            is StatementDirectionMapping.DirectionColumn -> {
                data.writeByte(DIRECTION_COLUMN)
                data.writeInt(direction.columnIndex)
                data.writeBoolean(direction.caseSensitive)
                data.writeStringSet(direction.inboundTokens)
                data.writeStringSet(direction.outboundTokens)
            }
        }
        data.writeInt(mapping.counterpartyColumnIndex)
        data.writeInt(mapping.referenceColumnIndex ?: NO_COLUMN)
        data.writeBoundedUtf8(mapping.currency.value)
    }

    private fun readMapping(data: DataInputStream): DelimitedStatementMapping {
        require(data.readInt() == MAPPING_VERSION)
        val delimiter = enumValue<DelimitedDelimiter>(data.readInt())
        val dateColumn = data.readInt()
        val dateFormat = enumValue<StatementDateFormat>(data.readInt())
        val amountColumn = data.readInt()
        val amountFormat = enumValue<StatementAmountFormat>(data.readInt())
        val direction = when (data.readUnsignedByte()) {
            DIRECTION_SIGNED -> StatementDirectionMapping.SignedAmount(
                positiveDirection = enumValue<StatementDirection>(data.readInt()),
            )

            DIRECTION_COLUMN -> StatementDirectionMapping.DirectionColumn(
                columnIndex = data.readInt(),
                caseSensitive = data.readBoolean(),
                inboundTokens = data.readStringSet(),
                outboundTokens = data.readStringSet(),
            )

            else -> throw IllegalArgumentException("Unknown direction mapping")
        }
        val counterpartyColumn = data.readInt()
        val referenceColumn = data.readInt().let { if (it == NO_COLUMN) null else it }
        val currency = CurrencyCode(data.readBoundedUtf8())
        return DelimitedStatementMapping(
            delimiter = delimiter,
            dateColumnIndex = dateColumn,
            dateFormat = dateFormat,
            amountColumnIndex = amountColumn,
            amountFormat = amountFormat,
            directionMapping = direction,
            counterpartyColumnIndex = counterpartyColumn,
            referenceColumnIndex = referenceColumn,
            currency = currency,
        )
    }

    private fun DataOutputStream.writeStringSet(values: Set<String>) {
        require(values.size in 1..MAX_DIRECTION_TOKENS)
        val canonical = values.map(String::trim).sorted()
        writeInt(canonical.size)
        canonical.forEach { token -> writeBoundedUtf8(token) }
    }

    private fun DataInputStream.readStringSet(): Set<String> {
        val count = readInt()
        require(count in 1..MAX_DIRECTION_TOKENS)
        return List(count) { readBoundedUtf8() }.toSet().also {
            require(it.size == count) { "Direction tokens must be unique" }
        }
    }

    private fun DataOutputStream.writeAsciiHash(hash: EvidenceHash) {
        val bytes = hash.value.toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size == HASH_LENGTH)
        write(bytes)
    }

    private fun DataInputStream.readAsciiHash(): EvidenceHash {
        val bytes = ByteArray(HASH_LENGTH)
        readFully(bytes)
        return EvidenceHash(String(bytes, StandardCharsets.US_ASCII))
    }

    private fun DataOutputStream.writeBoundedUtf8(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try {
            require(bytes.size <= MAX_UTF8_FIELD_BYTES)
            writeInt(bytes.size)
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    @Throws(CharacterCodingException::class)
    private fun DataInputStream.readBoundedUtf8(): String {
        val length = readInt()
        require(length in 0..MAX_UTF8_FIELD_BYTES)
        val bytes = ByteArray(length)
        readFully(bytes)
        return try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } finally {
            bytes.fill(0)
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal)
            ?: throw IllegalArgumentException("Unknown enum value")

    private fun malformed() = DelimitedRowEvidenceDecodeResult.Malformed

    private val MAGIC = "BILLDSR1".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION = 1
    private const val MAPPING_VERSION = 1
    private const val HASH_LENGTH = 64
    private const val MAX_UTF8_FIELD_BYTES = 16 * 1024
    private const val NO_COLUMN = -1
    private const val DIRECTION_SIGNED = 1
    private const val DIRECTION_COLUMN = 2
}
