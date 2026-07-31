package dev.bill.application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestmentPositionOcrPrefillParserTest {
    @Test
    fun `simplified Chinese labels prefill all verified fields`() {
        val result = success(
            "基金名称：示例稳健基金",
            "基金代码：012345",
            "当前持有金额：￥1,234.56",
            "持有份额：88.1234",
            "持仓成本：1000.00元",
        )

        assertEquals("示例稳健基金", result.name)
        assertEquals("012345", result.instrumentCode)
        assertEquals("1234.56", result.currentValue)
        assertEquals("88.1234", result.units)
        assertEquals("1000", result.costBasis)
    }

    @Test
    fun `traditional Chinese English and Japanese labels remain compatible`() {
        val traditional = success("基金名稱：示例基金", "目前持有金額：88.01元")
        val english = success("Fund name: Example Fund", "Market value: CNY 99.02")
        val japanese = success("ファンド名：サンプル", "評価額：123.45")

        assertEquals("示例基金", traditional.name)
        assertEquals("88.01", traditional.currentValue)
        assertEquals("Example Fund", english.name)
        assertEquals("99.02", english.currentValue)
        assertEquals("サンプル", japanese.name)
        assertEquals("123.45", japanese.currentValue)
    }

    @Test
    fun `partial labelled data is returned without inventing absent fields`() {
        val result = success("持有市值：66.60元")

        assertNull(result.name)
        assertNull(result.instrumentCode)
        assertEquals("66.6", result.currentValue)
        assertNull(result.units)
        assertNull(result.costBasis)
    }

    @Test
    fun `a following different label is not consumed as the previous field value`() {
        val result = success("基金名称", "当前金额：100.00元")

        assertNull(result.name)
        assertEquals("100", result.currentValue)
    }

    @Test
    fun `conflicting labelled values reject the entire prefill`() {
        val result = InvestmentPositionOcrPrefillParser.parse(
            listOf("持有市值：10.00元", "当前金额：11.00元"),
        )

        assertEquals(
            InvestmentPositionOcrPrefillResult.Failure(
                InvestmentPositionOcrPrefillError.AMBIGUOUS_FIELDS,
            ),
            result,
        )
    }

    @Test
    fun `gain text is never promoted to current holding value`() {
        val result = InvestmentPositionOcrPrefillParser.parse(
            listOf("当前金额", "今日收益：9.99元", "收益率：1.2%"),
        )

        assertEquals(
            InvestmentPositionOcrPrefillResult.Failure(
                InvestmentPositionOcrPrefillError.NO_RECOGNIZED_FIELDS,
            ),
            result,
        )
    }

    private fun success(vararg lines: String): InvestmentPositionOcrPrefill {
        val result = InvestmentPositionOcrPrefillParser.parse(lines.toList())
        assertTrue(result is InvestmentPositionOcrPrefillResult.Success)
        return (result as InvestmentPositionOcrPrefillResult.Success).prefill
    }
}
