package dev.bill.feature.review

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.bill.application.AccountSummary
import dev.bill.application.DraftSummary
import dev.bill.application.DraftSummaryKind
import dev.bill.application.OperationError
import dev.bill.core.domain.TransactionSourceMode
import dev.bill.core.designsystem.component.LedgerCard
import dev.bill.core.designsystem.component.MoneyText
import dev.bill.core.designsystem.component.PosterPanel
import dev.bill.core.designsystem.component.SectionMarker
import dev.bill.core.designsystem.theme.BillTheme
import dev.bill.core.model.AccountType
import dev.bill.core.model.Money
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewBottomSheet(
    state: ReviewUiState,
    amountsMasked: Boolean,
    onAction: (ReviewAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    val selectedAccount = state.eligibleAccounts.firstOrNull { it.id == draft.fundingAccountId }
    val investmentTargetReady = draft.kind != DraftSummaryKind.INVEST_BUY ||
        state.investmentAccount?.type == AccountType.INVESTMENT_SECURITY
    val postingReady = selectedAccount != null && investmentTargetReady

    ModalBottomSheet(
        onDismissRequest = {
            if (!state.isSaving) onAction(ReviewAction.Dismiss)
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
                    text = stringResource(R.string.review_marker),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = stringResource(R.string.review_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            PosterPanel(contentPadding = PaddingValues(20.dp)) {
                MoneyText(
                    amount = draft.amount,
                    masked = amountsMasked,
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = draft.counterparty,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.review_time_and_source,
                        draft.occurredAt.localizedDateTime(),
                        stringResource(
                            if (draft.sourceMode == TransactionSourceMode.EXTERNAL) {
                                R.string.shared_text_source
                            } else {
                                R.string.manual_source
                            },
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.76f),
                )
            }

            InformationSection(
                title = stringResource(R.string.review_reason_heading),
                body = when {
                    selectedAccount == null -> stringResource(R.string.review_reason_account_missing)
                    !investmentTargetReady ->
                        stringResource(R.string.review_reason_investment_position_missing)
                    else -> stringResource(R.string.review_reason_ready)
                },
            )

            SectionMarker(index = "05", title = stringResource(R.string.review_details_heading))
            LabeledValue(
                label = stringResource(R.string.economic_type),
                value = draft.kind.localizedName(),
            )
            if (draft.kind == DraftSummaryKind.INVEST_BUY) {
                LabeledValue(
                    label = stringResource(R.string.investment_position_target),
                    value = state.investmentAccount?.name
                        ?: stringResource(R.string.investment_position_missing),
                )
            }
            LabeledValue(
                label = stringResource(R.string.payment_channel),
                value = stringResource(
                    if (draft.sourceMode == TransactionSourceMode.EXTERNAL) {
                        R.string.shared_text_channel
                    } else {
                        R.string.manual_channel
                    },
                ),
            )

            Text(
                text = stringResource(R.string.funding_account),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.eligibleAccounts.isEmpty()) {
                LedgerCard {
                    Text(
                        text = stringResource(R.string.no_eligible_accounts),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.eligibleAccounts.forEach { account ->
                        AccountChoice(
                            account = account,
                            selected = account.id == draft.fundingAccountId,
                            enabled = !state.isSaving,
                            amountsMasked = amountsMasked,
                            onClick = {
                                onAction(ReviewAction.SelectFundingAccount(account.id))
                            },
                        )
                    }
                }
            }

            InformationSection(
                title = stringResource(R.string.review_evidence_heading),
                body = stringResource(
                    if (draft.sourceMode == TransactionSourceMode.EXTERNAL) {
                        R.string.shared_text_evidence
                    } else {
                        R.string.manual_evidence
                    },
                ),
            )
            InformationSection(
                title = stringResource(R.string.review_impact_heading),
                body = when (draft.kind) {
                    DraftSummaryKind.EXPENSE -> stringResource(R.string.expense_impact)
                    DraftSummaryKind.INCOME -> stringResource(R.string.income_impact)
                    DraftSummaryKind.INVEST_BUY -> stringResource(R.string.investment_buy_impact)
                },
            )

            state.operationError?.reviewErrorMessage()?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { onAction(ReviewAction.Confirm) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = !state.isSaving && postingReady,
                shape = MaterialTheme.shapes.small,
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.review_saving),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    Text(stringResource(R.string.confirm_posting))
                }
            }

            TextButton(
                onClick = { onAction(ReviewAction.SaveForLater) },
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !state.isSaving,
            ) {
                Text(stringResource(R.string.save_for_later))
            }
            HorizontalDivider()
            TextButton(
                onClick = { onAction(ReviewAction.Ignore) },
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp),
                enabled = !state.isSaving,
            ) {
                Text(
                    text = stringResource(R.string.ignore_draft),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountChoice(
    account: AccountSummary,
    selected: Boolean,
    enabled: Boolean,
    amountsMasked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(account.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (account.isLiability) {
                    stringResource(R.string.credit_account)
                } else {
                    stringResource(R.string.asset_account)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MoneyText(
            amount = account.displayBalance,
            masked = amountsMasked,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun InformationSection(title: String, body: String) {
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
private fun LabeledValue(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DraftSummaryKind.localizedName(): String = stringResource(
    when (this) {
        DraftSummaryKind.EXPENSE -> R.string.expense
        DraftSummaryKind.INCOME -> R.string.income
        DraftSummaryKind.INVEST_BUY -> R.string.investment_buy
    },
)

@Composable
private fun java.time.Instant.localizedDateTime(): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(this)
}

@Composable
private fun OperationError.reviewErrorMessage(): String = when (this) {
    OperationError.ACCOUNT_REQUIRED -> stringResource(R.string.error_account_required)
    OperationError.ACCOUNT_NOT_FOUND -> stringResource(R.string.error_account_not_found)
    OperationError.INVALID_STATE,
    OperationError.NOT_FOUND,
    -> stringResource(R.string.error_draft_changed)
    else -> stringResource(R.string.error_review_failed)
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892)
@Preview(
    name = "Review dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ReviewPreview() {
    val draft = DraftSummary(
        id = "preview-draft",
        kind = DraftSummaryKind.EXPENSE,
        amount = Money.cny(2_800),
        counterparty = "午饭",
        note = null,
        fundingAccountId = "preview-account",
        occurredAt = java.time.Instant.parse("2026-07-19T12:00:00Z"),
    )
    BillTheme {
        ReviewBottomSheet(
            state = ReviewPresenter.present(
                draft = draft,
                accounts = listOf(
                    AccountSummary(
                        id = "preview-account",
                        name = "随身现金",
                        type = AccountType.ASSET_CASH,
                        displayBalance = Money.cny(12_300),
                        isLiability = false,
                    ),
                ),
                isSaving = false,
                operationError = null,
            ),
            amountsMasked = false,
            onAction = {},
        )
    }
}
