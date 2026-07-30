package dev.bill.source.genericdelimited

import dev.bill.source.contract.EvidenceHash
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

class DelimitedTextReader(
    private val limits: DelimitedReadLimits = DelimitedReadLimits(),
) {
    fun read(
        bytes: ByteArray,
        delimiter: DelimitedDelimiter,
    ): DelimitedReadResult {
        if (bytes.isEmpty()) return failure(DelimitedReadError.EMPTY_DOCUMENT)
        if (bytes.size > limits.maxBytes) return failure(DelimitedReadError.CONTENT_TOO_LARGE)

        val decoded = try {
            decodeUtf8(bytes)
        } catch (_: CharacterCodingException) {
            return failure(DelimitedReadError.MALFORMED_UTF8)
        }
        val text = decoded.removePrefix(UTF8_BOM)
        if (text.isEmpty()) return failure(DelimitedReadError.EMPTY_DOCUMENT)

        val records = mutableListOf<List<String>>()
        var cells = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var afterClosingQuote = false
        var atFieldStart = true
        var recordStarted = false
        var recordChars = 0
        var index = 0

        fun appendCellCharacter(character: Char): DelimitedReadResult.Failure? {
            cell.append(character)
            if (cell.length > limits.maxCellChars) {
                return failure(DelimitedReadError.CELL_TOO_LONG)
            }
            return null
        }

        fun finishField(): DelimitedReadResult.Failure? {
            if (cells.size >= limits.maxColumns) {
                return failure(DelimitedReadError.TOO_MANY_COLUMNS)
            }
            cells.add(cell.toString())
            cell.setLength(0)
            atFieldStart = true
            afterClosingQuote = false
            return null
        }

        fun finishRecord(): DelimitedReadResult.Failure? {
            finishField()?.let { return it }
            if (records.size >= limits.maxRecords) {
                return failure(DelimitedReadError.TOO_MANY_RECORDS)
            }
            records.add(cells.toList())
            cells = mutableListOf()
            recordStarted = false
            recordChars = 0
            return null
        }

        while (index < text.length) {
            val character = text[index]
            if (character == NUL) return failure(DelimitedReadError.NUL_CHARACTER)
            recordChars += 1
            if (recordChars > limits.maxRecordChars) {
                return failure(DelimitedReadError.RECORD_TOO_LONG)
            }

            if (inQuotes) {
                if (character == QUOTE) {
                    if (index + 1 < text.length && text[index + 1] == QUOTE) {
                        recordChars += 1
                        if (recordChars > limits.maxRecordChars) {
                            return failure(DelimitedReadError.RECORD_TOO_LONG)
                        }
                        appendCellCharacter(QUOTE)?.let { return it }
                        index += 2
                    } else {
                        inQuotes = false
                        afterClosingQuote = true
                        index += 1
                    }
                } else {
                    appendCellCharacter(character)?.let { return it }
                    index += 1
                }
                continue
            }

            if (afterClosingQuote) {
                when (character) {
                    delimiter.character -> {
                        finishField()?.let { return it }
                        recordStarted = true
                        index += 1
                    }

                    '\r', '\n' -> {
                        finishRecord()?.let { return it }
                        index = skipLineSeparator(text, index)
                    }

                    else -> return failure(
                        DelimitedReadError.CHARACTERS_AFTER_CLOSING_QUOTE,
                    )
                }
                continue
            }

            when {
                atFieldStart && character == QUOTE -> {
                    inQuotes = true
                    atFieldStart = false
                    recordStarted = true
                    index += 1
                }

                character == QUOTE -> return failure(DelimitedReadError.UNEXPECTED_QUOTE)

                character == delimiter.character -> {
                    finishField()?.let { return it }
                    recordStarted = true
                    index += 1
                }

                character == '\r' || character == '\n' -> {
                    finishRecord()?.let { return it }
                    index = skipLineSeparator(text, index)
                }

                else -> {
                    appendCellCharacter(character)?.let { return it }
                    atFieldStart = false
                    recordStarted = true
                    index += 1
                }
            }
        }

        if (inQuotes) return failure(DelimitedReadError.UNCLOSED_QUOTED_FIELD)
        if (recordStarted || cells.isNotEmpty() || cell.isNotEmpty() || afterClosingQuote) {
            finishRecord()?.let { return it }
        }
        if (records.isEmpty() || records.all { row -> row.all(String::isBlank) }) {
            return failure(DelimitedReadError.EMPTY_DOCUMENT)
        }

        val header = records.first()
        val rows = records.drop(1).mapIndexed { rowIndex, row ->
            DelimitedRow(
                tableRowIndex = rowIndex + 1,
                cells = row,
            )
        }
        return DelimitedReadResult.Success(
            DelimitedDocument(
                delimiter = delimiter,
                fileHash = EvidenceHash.fromBytes(bytes),
                header = header,
                rows = rows,
            ),
        )
    }

    @Throws(CharacterCodingException::class)
    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun skipLineSeparator(text: String, index: Int): Int =
        if (text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
            index + 2
        } else {
            index + 1
        }

    private fun failure(error: DelimitedReadError) = DelimitedReadResult.Failure(error)

    private companion object {
        const val UTF8_BOM = "\uFEFF"
        const val NUL = '\u0000'
        const val QUOTE = '"'
    }
}
