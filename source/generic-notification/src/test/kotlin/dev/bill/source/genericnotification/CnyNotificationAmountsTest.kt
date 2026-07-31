package dev.bill.source.genericnotification

import dev.bill.source.contract.NotificationField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CnyNotificationAmountsTest {
    @Test
    fun `parses one positive symbol prefix or yuan suffix amount`() {
        assertEquals(1_234L, CnyNotificationAmounts.parseSingle("付款￥12.34")?.minorUnits)
        assertEquals(1_234L, CnyNotificationAmounts.parseSingle("退款人民币12.34元")?.minorUnits)
        assertEquals(123_456L, CnyNotificationAmounts.parseSingle("支出1,234.56元")?.minorUnits)
        assertEquals(1_200L, CnyNotificationAmounts.parseSingle("CNY 12")?.minorUnits)
    }

    @Test
    fun `rejects missing zero signed malformed or competing amounts`() {
        assertNull(CnyNotificationAmounts.parseSingle("尾号1234"))
        assertNull(CnyNotificationAmounts.parseSingle("￥0.00"))
        assertNull(CnyNotificationAmounts.parseSingle("￥-12.34"))
        assertNull(CnyNotificationAmounts.parseSingle("-￥12.34"))
        assertNull(CnyNotificationAmounts.parseSingle("+ 人民币12.34"))
        assertNull(CnyNotificationAmounts.parseSingle("￥12.345"))
        assertNull(CnyNotificationAmounts.parseSingle("支付￥12.34，余额￥56.78"))
        assertNull(CnyNotificationAmounts.parseSingle("人民币1234567890.00"))
        assertNull(CnyNotificationAmounts.parseSingle("ABCNY 12"))
    }

    @Test
    fun `consistent content allows mirrored amount and rejects another field amount`() {
        val mirrored = content(
            NotificationField.TITLE to "成功收款12.34元",
            NotificationField.TEXT to "已转入余额",
            NotificationField.BIG_TEXT to "成功收款￥12.34",
        )
        val conflicting = content(
            NotificationField.TITLE to "成功收款12.34元",
            NotificationField.TEXT to "已转入余额￥56.78",
        )
        val mixedCurrency = content(
            NotificationField.TITLE to "成功收款12.34元",
            NotificationField.TEXT to "原金额 USD 1.70",
        )
        val harmlessAsciiSubstring = content(
            NotificationField.TITLE to "成功收款12.34元",
            NotificationField.TEXT to "reference-enJPYjoy",
        )

        assertEquals(
            1_234L,
            CnyNotificationAmounts.parseConsistentSingle(
                mirrored,
                NotificationField.TITLE,
            )?.minorUnits,
        )
        assertNull(
            CnyNotificationAmounts.parseConsistentSingle(
                conflicting,
                NotificationField.TITLE,
            ),
        )
        assertNull(
            CnyNotificationAmounts.parseConsistentSingle(
                mixedCurrency,
                NotificationField.TITLE,
            ),
        )
        assertEquals(
            1_234L,
            CnyNotificationAmounts.parseConsistentSingle(
                harmlessAsciiSubstring,
                NotificationField.TITLE,
            )?.minorUnits,
        )
    }

    private fun content(vararg fields: Pair<NotificationField, String>): NotificationContent =
        checkNotNull(NotificationContent.from(mapOf(*fields)))
}
