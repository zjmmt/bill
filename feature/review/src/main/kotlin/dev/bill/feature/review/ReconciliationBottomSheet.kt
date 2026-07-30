package dev.bill.feature.review

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.bill.application.OperationError
import dev.bill.application.ReconciliationCaseKind
import dev.bill.application.ReconciliationCaseSummary
import dev.bill.core.designsystem.component.LedgerCard
import dev.bill.core.designsystem.component.MoneyText
import dev.bill.core.designsystem.component.PosterPanel
import dev.bill.core.designsystem.component.SectionMarker
import dev.bill.core.designsystem.theme.BillTheme
import dev.bill.core.model.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconciliationBottomSheet(
    case: ReconciliationCaseSummary,
    amountsMasked: Boolean,
    isSaving: Boolean,
    operationError: OperationError?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = {
            if (!isSaving) onDismiss()
        },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.reconciliation_marker),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = stringResource(R.string.reconciliation_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            PosterPanel(contentPadding = PaddingValues(20.dp)) {
                MoneyText(
                    amount = case.amount,
                    masked = amountsMasked,
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = case.kind.localizedName(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = case.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = case.occurredAt.localizedDateTime(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.76f),
                )
            }

            ReconciliationInformation(
                title = stringResource(R.string.reconciliation_reason_heading),
                body = stringResource(R.string.reconciliation_reason_body),
            )

            SectionMarker(
                index = "06",
                title = stringResource(R.string.reconciliation_impact_heading),
            )
            ReconciliationInformation(
                title = case.kind.localizedName(),
                body = case.kind.localizedImpact(),
            )
            ReconciliationInformation(
                title = stringResource(R.string.reconciliation_evidence_heading),
                body = pluralStringResource(
                    R.plurals.reconciliation_evidence_count,
                    case.draftIds.size,
                    case.draftIds.size,
                ),
            )
            ReconciliationInformation(
                title = stringResource(R.string.reconciliation_undo_heading),
                body = stringResource(R.string.reconciliation_undo_body),
            )

            operationError?.let {
                Text(
                    text = stringResource(
                        if (
                            it == OperationError.NOT_FOUND ||
                            it == OperationError.INVALID_STATE
                        ) {
                            R.string.reconciliation_error_changed
                        } else {
                            R.string.reconciliation_error_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = !isSaving,
                shape = MaterialTheme.shapes.small,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.reconciliation_saving),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    Text(stringResource(R.string.reconciliation_confirm))
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !isSaving,
            ) {
                Text(stringResource(R.string.reconciliation_later))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReconciliationInformation(title: String, body: String) {
    LedgerCard {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReconciliationCaseKind.localizedName(): String = stringResource(
    when (this) {
        ReconciliationCaseKind.TRANSFER -> R.string.reconciliation_kind_transfer
        ReconciliationCaseKind.REFUND -> R.string.reconciliation_kind_refund
        ReconciliationCaseKind.LIABILITY_REPAYMENT ->
            R.string.reconciliation_kind_repayment
    },
)

@Composable
private fun ReconciliationCaseKind.localizedImpact(): String = stringResource(
    when (this) {
        ReconciliationCaseKind.TRANSFER -> R.string.reconciliation_transfer_impact
        ReconciliationCaseKind.REFUND -> R.string.reconciliation_refund_impact
        ReconciliationCaseKind.LIABILITY_REPAYMENT ->
            R.string.reconciliation_repayment_impact
    },
)

@Composable
private fun Instant.localizedDateTime(): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(this)
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892)
@Preview(
    name = "Reconciliation dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ReconciliationPreview() {
    BillTheme {
        ReconciliationBottomSheet(
            case = ReconciliationCaseSummary(
                id = "preview-reconciliation",
                kind = ReconciliationCaseKind.TRANSFER,
                amount = Money.cny(12_800L),
                occurredAt = Instant.parse("2026-07-26T08:00:00Z"),
                title = "日常账户 → 储蓄账户",
                draftIds = listOf("preview-outbound", "preview-inbound"),
                sourceAccountId = "preview-source",
                destinationAccountId = "preview-destination",
                relatedTransactionId = null,
            ),
            amountsMasked = false,
            isSaving = false,
            operationError = null,
            onConfirm = {},
            onDismiss = {},
        )
    }
}
