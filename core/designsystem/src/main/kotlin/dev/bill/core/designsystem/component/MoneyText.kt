package dev.bill.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import dev.bill.core.designsystem.R
import dev.bill.core.model.Money
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

@Composable
fun MoneyText(
    amount: Money,
    masked: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val locale = LocalConfiguration.current.locales[0]
    val formatted = remember(amount, locale) { amount.format(locale) }
    val hiddenDescription = stringResource(R.string.amount_hidden)
    val displayedText = if (masked) "••••••" else formatted
    val spokenText = if (masked) hiddenDescription else formatted

    Text(
        text = displayedText,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = spokenText
        },
        style = style.copy(fontFeatureSettings = "tnum"),
    )
}

private fun Money.format(locale: Locale): String {
    val javaCurrency = runCatching { Currency.getInstance(currency.value) }.getOrNull()
    val fractionDigits = javaCurrency?.defaultFractionDigits?.coerceAtLeast(0) ?: 2
    val decimalAmount = BigDecimal.valueOf(minorUnits, fractionDigits)

    if (javaCurrency == null) {
        return "${currency.value} ${decimalAmount.toPlainString()}"
    }

    return NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = javaCurrency
    }.format(decimalAmount)
}
