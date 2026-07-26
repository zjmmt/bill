package dev.bill.feature.ledger

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.bill.application.TransactionSummary
import dev.bill.application.TransactionSummaryKind
import dev.bill.application.signedDisplayAmount
import dev.bill.core.designsystem.component.LedgerCard
import dev.bill.core.designsystem.component.MoneyText
import dev.bill.core.designsystem.component.PosterPanel
import dev.bill.core.designsystem.component.SectionMarker
import dev.bill.core.designsystem.theme.BillTheme
import dev.bill.core.model.Money

@Composable
fun LedgerScreen(
    transactions: List<TransactionSummary>,
    amountsMasked: Boolean,
    voidingTransactionId: String?,
    onVoidTransaction: (String) -> Unit,
    onAddManualDraft: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionMarker(index = "03", title = stringResource(R.string.ledger_title))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.ledger_heading),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(R.string.ledger_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (transactions.isEmpty()) {
            item {
                PosterPanel(contentPadding = PaddingValues(20.dp)) {
                    Text(
                        text = stringResource(R.string.ledger_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.ledger_empty_body),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onAddManualDraft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(stringResource(R.string.enter_transaction))
                    }
                }
            }
        } else {
            items(transactions, key = TransactionSummary::id) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    amountsMasked = amountsMasked,
                    isVoiding = voidingTransactionId == transaction.id,
                    onVoid = { onVoidTransaction(transaction.id) },
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun TransactionRow(
    transaction: TransactionSummary,
    amountsMasked: Boolean,
    isVoiding: Boolean,
    onVoid: () -> Unit,
) {
    LedgerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = transaction.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = transaction.supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = transaction.kind.localizedLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            MoneyText(
                amount = transaction.signedDisplayAmount(),
                masked = amountsMasked,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (transaction.canUndo) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            TextButton(
                onClick = onVoid,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp),
                enabled = !isVoiding,
            ) {
                if (isVoiding) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.undoing),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    Text(stringResource(R.string.undo_and_restore))
                }
            }
        }
    }
}

@Composable
private fun TransactionSummaryKind.localizedLabel(): String = stringResource(
    when (this) {
        TransactionSummaryKind.EXPENSE -> R.string.transaction_expense
        TransactionSummaryKind.INCOME -> R.string.transaction_income
        TransactionSummaryKind.MONEY_MOVEMENT -> R.string.transaction_movement
    },
)

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Preview(
    name = "Ledger dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LedgerPreview() {
    BillTheme {
        LedgerScreen(
            transactions = listOf(
                TransactionSummary(
                    id = "preview-transaction",
                    title = "午饭",
                    supportingText = "手工录入 · 已记账",
                    amount = Money.cny(2_800),
                    kind = TransactionSummaryKind.EXPENSE,
                    canUndo = true,
                ),
            ),
            amountsMasked = false,
            voidingTransactionId = null,
            onVoidTransaction = {},
            onAddManualDraft = {},
            contentPadding = PaddingValues(),
        )
    }
}
