package dev.bill.source.genericdelimited

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DelimitedTextReaderTest {
    @Test
    fun `reads quoted commas escaped quotes and quoted newlines`() {
        val bytes = (
            "日期,金额,方向,对手方\n" +
                "2026-07-01,12.34,支出,\"Coffee, Inc.\"\n" +
                "2026-07-02,20.00,收入,\"日语\"\"メモ\ncontinued\""
            ).toByteArray(StandardCharsets.UTF_8)

        val result = DelimitedTextReader().read(bytes, DelimitedDelimiter.COMMA)

        val document = (result as DelimitedReadResult.Success).document
        assertEquals(listOf("日期", "金额", "方向", "对手方"), document.header)
        assertEquals(2, document.rows.size)
        assertEquals("Coffee, Inc.", document.rows[0].cells[3])
        assertEquals("日语\"メモ\ncontinued", document.rows[1].cells[3])
        assertEquals(1, document.rows[0].tableRowIndex)
        assertEquals(2, document.rows[1].tableRowIndex)
    }

    @Test
    fun `reads tab separated values and optional utf8 bom`() {
        val bytes = "\uFEFFdate\tamount\tparty\r\n2026/07/01\t1.00\t東京"
            .toByteArray(StandardCharsets.UTF_8)

        val result = DelimitedTextReader().read(bytes, DelimitedDelimiter.TAB)

        val document = (result as DelimitedReadResult.Success).document
        assertEquals(listOf("date", "amount", "party"), document.header)
        assertEquals("東京", document.rows.single().cells[2])
    }

    @Test
    fun `rejects malformed utf8`() {
        val result = DelimitedTextReader().read(
            byteArrayOf(0x61, 0x2c, 0xC3.toByte(), 0x28),
            DelimitedDelimiter.COMMA,
        )

        assertEquals(
            DelimitedReadResult.Failure(DelimitedReadError.MALFORMED_UTF8),
            result,
        )
    }

    @Test
    fun `rejects unclosed quoted field`() {
        val result = read("a,b\n1,\"unfinished")

        assertEquals(
            DelimitedReadResult.Failure(DelimitedReadError.UNCLOSED_QUOTED_FIELD),
            result,
        )
    }

    @Test
    fun `rejects quote inside unquoted field`() {
        val result = read("a,b\nun\"expected,2")

        assertEquals(
            DelimitedReadResult.Failure(DelimitedReadError.UNEXPECTED_QUOTE),
            result,
        )
    }

    @Test
    fun `rejects characters after closing quote`() {
        val result = read("a,b\n\"closed\"suffix,2")

        assertEquals(
            DelimitedReadResult.Failure(
                DelimitedReadError.CHARACTERS_AFTER_CLOSING_QUOTE,
            ),
            result,
        )
    }

    @Test
    fun `enforces row column cell and file limits`() {
        val cellLimited = DelimitedTextReader(
            DelimitedReadLimits(maxCellChars = 3, maxRecordChars = 10),
        ).read("a\n1234".bytes(), DelimitedDelimiter.COMMA)
        assertEquals(
            DelimitedReadResult.Failure(DelimitedReadError.CELL_TOO_LONG),
            cellLimited,
        )

        val columnLimited = DelimitedTextReader(
            DelimitedReadLimits(maxColumns = 2),
        ).read("a,b,c\n1,2,3".bytes(), DelimitedDelimiter.COMMA)
        assertEquals(
            DelimitedReadResult.Failure(DelimitedReadError.TOO_MANY_COLUMNS),
            columnLimited,
        )

        val recordLimited = DelimitedTextReader(
            DelimitedReadLimits(maxRecords = 2),
        ).read("h\n1\n2".bytes(), DelimitedDelimiter.COMMA)
        assertEquals(
            DelimitedReadResult.Failure(DelimitedReadError.TOO_MANY_RECORDS),
            recordLimited,
        )

        val fileLimited = DelimitedTextReader(
            DelimitedReadLimits(maxBytes = 3),
        ).read("a\n12".bytes(), DelimitedDelimiter.COMMA)
        assertEquals(
            DelimitedReadResult.Failure(DelimitedReadError.CONTENT_TOO_LARGE),
            fileLimited,
        )
    }

    @Test
    fun `does not create a phantom row after trailing newline`() {
        val result = read("h1,h2\n1,2\n")

        val document = (result as DelimitedReadResult.Success).document
        assertEquals(1, document.rows.size)
        assertTrue(document.rows.single().cells.contains("2"))
    }

    private fun read(value: String): DelimitedReadResult =
        DelimitedTextReader().read(value.bytes(), DelimitedDelimiter.COMMA)

    private fun String.bytes() = toByteArray(StandardCharsets.UTF_8)
}
