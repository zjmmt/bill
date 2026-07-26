package dev.bill.feature.overview

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.bill.application.SourceHealthState
import dev.bill.application.SourceHealthSummary
import dev.bill.application.SourceKind
import dev.bill.application.TransactionSummary
import dev.bill.application.CurrencyBalanceSummary
import dev.bill.application.signedDisplayAmount
import dev.bill.core.designsystem.component.LedgerCard
import dev.bill.core.designsystem.component.MoneyText
import dev.bill.core.designsystem.component.PosterPanel
import dev.bill.core.designsystem.component.SectionMarker
import dev.bill.core.designsystem.theme.BillTheme
import dev.bill.core.model.Money

@Composable
fun OverviewScreen(
    state: OverviewUiState,
    onAction: (OverviewAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        OverviewUiState.Loading -> LoadingState(modifier)
        OverviewUiState.Empty -> EmptyState(onAction, modifier)
        OverviewUiState.Error -> ErrorState(onAction, modifier)
        is OverviewUiState.Content -> OverviewContent(state, onAction, modifier)
    }
}

@Composable
private fun OverviewContent(
    state: OverviewUiState.Content,
    onAction: (OverviewAction) -> Unit,
    modifier: Modifier,
) {
    val snapshot = state.snapshot

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "balance") {
                BalanceHero(state, onAction)
            }

            item(key = "local-only") {
                LocalStatusBand()
            }

            if (snapshot.accountCount == 0) {
                item(key = "getting-started") {
                    GettingStartedBlock(onAction)
                }
            }

            if (snapshot.pendingDraftCount > 0) {
                item(key = "drafts") {
                    DraftActionBlock(state, onAction)
                }
            }

            item(key = "source-heading") {
                SectionMarker(index = "02", title = stringResource(R.string.source_health))
            }

            item(key = "sources") {
                SourceStatusPanel(snapshot.sourceHealth)
            }

            item(key = "recent-heading") {
                SectionMarker(index = "03", title = stringResource(R.string.recent_transactions))
            }

            item(key = "transactions") {
                TransactionPanel(snapshot.recentTransactions, state.amountsMasked)
            }

            item(key = "bottom-space") {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun GettingStartedBlock(onAction: (OverviewAction) -> Unit) {
    LedgerCard {
        Text(
            text = stringResource(R.string.getting_started_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.getting_started_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onAction(OverviewAction.AddAccount) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(stringResource(R.string.create_first_account))
        }
        TextButton(
            onClick = { onAction(OverviewAction.AddManualDraft) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.enter_first_transaction))
        }
    }
}

@Composable
private fun BalanceHero(
    state: OverviewUiState.Content,
    onAction: (OverviewAction) -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.inverseOnSurface
    val stackMetrics = LocalDensity.current.fontScale >= 1.5f

    PosterPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.hero_kicker),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.78f),
            )
            Surface(
                shape = CircleShape,
                color = contentColor.copy(alpha = 0.12f),
                contentColor = contentColor,
            ) {
                IconButton(onClick = { onAction(OverviewAction.ToggleAmounts) }) {
                    Icon(
                        imageVector = if (state.amountsMasked) {
                            Icons.Outlined.Visibility
                        } else {
                            Icons.Outlined.VisibilityOff
                        },
                        contentDescription = stringResource(
                            if (state.amountsMasked) R.string.show_amounts else R.string.hide_amounts,
                        ),
                    )
                }
            }
        }

        state.snapshot.currencyBalances.forEachIndexed { index, balance ->
            Spacer(Modifier.height(if (index == 0) 24.dp else 28.dp))
            CurrencyBalanceBlock(
                balance = balance,
                amountsMasked = state.amountsMasked,
                stackMetrics = stackMetrics,
            )
        }
    }
}

@Composable
private fun CurrencyBalanceBlock(
    balance: CurrencyBalanceSummary,
    amountsMasked: Boolean,
    stackMetrics: Boolean,
) {
    val contentColor = LocalContentColor.current
    Text(
        text = stringResource(R.string.net_worth_currency, balance.currency.value),
        style = MaterialTheme.typography.titleMedium,
        color = contentColor.copy(alpha = 0.78f),
    )
    Spacer(Modifier.height(4.dp))
    MoneyText(
        amount = balance.netWorth,
        masked = amountsMasked,
        style = MaterialTheme.typography.displaySmall,
    )

    Spacer(Modifier.height(28.dp))
    if (stackMetrics) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BalanceMetric(
                label = stringResource(R.string.assets),
                amount = balance.assets,
                masked = amountsMasked,
            )
            BalanceMetric(
                label = stringResource(R.string.liabilities),
                amount = balance.liabilities,
                masked = amountsMasked,
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BalanceMetric(
                label = stringResource(R.string.assets),
                amount = balance.assets,
                masked = amountsMasked,
                modifier = Modifier.weight(1f),
            )
            BalanceMetric(
                label = stringResource(R.string.liabilities),
                amount = balance.liabilities,
                masked = amountsMasked,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BalanceMetric(
    label: String,
    amount: Money,
    masked: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentColor = LocalContentColor.current
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor.copy(alpha = 0.72f),
        )
        Spacer(Modifier.height(3.dp))
        MoneyText(
            amount = amount,
            masked = masked,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun LocalStatusBand() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(MaterialTheme.colorScheme.secondary),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.local_only_status),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.local_only_detail),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DraftActionBlock(
    state: OverviewUiState.Content,
    onAction: (OverviewAction) -> Unit,
) {
    val snapshot = state.snapshot

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.draft_action_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.8f),
            )
            Text(
                text = pluralStringResource(
                    R.plurals.pending_drafts,
                    snapshot.pendingDraftCount,
                    snapshot.pendingDraftCount,
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            if (snapshot.hardBlockCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.unknown_funding_accounts,
                        snapshot.hardBlockCount,
                        snapshot.hardBlockCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (snapshot.possibleDuplicateCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.possible_duplicates,
                        snapshot.possibleDuplicateCount,
                        snapshot.possibleDuplicateCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = { onAction(OverviewAction.ReviewDrafts) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(stringResource(R.string.review_now))
            }
        }
    }
}

@Composable
private fun SourceStatusPanel(sources: List<SourceHealthSummary>) {
    LedgerCard(contentPadding = PaddingValues(0.dp)) {
        sources.forEachIndexed { index, source ->
            SourceStatusRow(source)
            if (index < sources.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun SourceStatusRow(source: SourceHealthSummary) {
    val statusColor = source.state.statusColor()
    val stackContent = LocalDensity.current.fontScale >= 1.5f

    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 76.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp)

    if (stackContent) {
        Column(
            modifier = rowModifier,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                SourceStatusIcon(source.state, statusColor)
                SourceStatusText(source, Modifier.weight(1f))
            }
            SourceStatusBadge(
                state = source.state,
                statusColor = statusColor,
                modifier = Modifier.align(Alignment.End),
            )
        }
    } else {
        Row(
            modifier = rowModifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceStatusIcon(source.state, statusColor)
            SourceStatusText(source, Modifier.weight(1f))
            SourceStatusBadge(source.state, statusColor)
        }
    }
}

@Composable
private fun SourceStatusIcon(
    state: SourceHealthState,
    statusColor: Color,
) {
        Icon(
            imageVector = when (state) {
                SourceHealthState.HEALTHY -> Icons.Outlined.CheckCircle
                SourceHealthState.NEEDS_ATTENTION -> Icons.Outlined.PendingActions
                SourceHealthState.FALLBACK_REQUIRED -> Icons.Outlined.ErrorOutline
            },
            contentDescription = null,
            tint = statusColor,
        )
}

@Composable
private fun SourceStatusText(
    source: SourceHealthSummary,
    modifier: Modifier = Modifier,
) {
        Column(modifier = modifier) {
            Text(
                text = source.kind.displayName(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = source.state.detail(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
}

@Composable
private fun SourceStatusBadge(
    state: SourceHealthState,
    statusColor: Color,
    modifier: Modifier = Modifier,
) {
        Surface(
            modifier = modifier,
            color = statusColor.copy(alpha = 0.12f),
            contentColor = statusColor,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
        ) {
            Text(
                text = state.shortLabel(),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
}

@Composable
private fun TransactionPanel(
    transactions: List<TransactionSummary>,
    amountsMasked: Boolean,
) {
    LedgerCard(contentPadding = PaddingValues(0.dp)) {
        transactions.forEachIndexed { index, transaction ->
            TransactionRow(transaction, amountsMasked)
            if (index < transactions.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(
    transaction: TransactionSummary,
    amountsMasked: Boolean,
) {
    val stackContent = LocalDensity.current.fontScale >= 1.5f
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 76.dp)
        .padding(horizontal = 16.dp, vertical = 14.dp)

    if (stackContent) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TransactionDescription(transaction)
            MoneyText(
                amount = transaction.signedDisplayAmount(),
                masked = amountsMasked,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransactionDescription(transaction, Modifier.weight(1f))
            MoneyText(
                amount = transaction.signedDisplayAmount(),
                masked = amountsMasked,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun TransactionDescription(
    transaction: TransactionSummary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .width(4.dp)
                .height(32.dp)
                    .background(MaterialTheme.colorScheme.primary),
        )
        Column {
            Text(
                text = transaction.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = transaction.supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(
    onAction: (OverviewAction) -> Unit,
    modifier: Modifier,
) {
    MessageState(
        title = stringResource(R.string.overview_empty_title),
        body = stringResource(R.string.overview_empty_body),
        modifier = modifier,
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAction(OverviewAction.AddAccount) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.create_first_account))
                }
                TextButton(
                    onClick = { onAction(OverviewAction.AddManualDraft) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.enter_first_transaction))
                }
            }
        },
    )
}

@Composable
private fun ErrorState(
    onAction: (OverviewAction) -> Unit,
    modifier: Modifier,
) {
    MessageState(
        title = stringResource(R.string.overview_error_title),
        body = stringResource(R.string.overview_error_body),
        modifier = modifier,
        action = {
            Button(onClick = { onAction(OverviewAction.Retry) }) {
                Text(stringResource(R.string.retry))
            }
        },
    )
}

@Composable
private fun MessageState(
    title: String,
    body: String,
    modifier: Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        PosterPanel(
            modifier = Modifier.widthIn(max = 480.dp),
            contentPadding = PaddingValues(24.dp),
        ) {
            Text(
                text = "00 / STATUS",
                style = MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(24.dp))
            Text(text = title, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = LocalContentColor.current.copy(alpha = 0.82f),
            )
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                action()
            }
        }
    }
}

@Composable
private fun SourceKind.displayName(): String = stringResource(
    when (this) {
        SourceKind.ALIPAY -> R.string.source_alipay
        SourceKind.WECHAT -> R.string.source_wechat
        SourceKind.BANK -> R.string.source_bank
    },
)

@Composable
private fun SourceHealthState.detail(): String = stringResource(
    when (this) {
        SourceHealthState.HEALTHY -> R.string.source_healthy
        SourceHealthState.NEEDS_ATTENTION -> R.string.source_needs_attention
        SourceHealthState.FALLBACK_REQUIRED -> R.string.source_fallback_required
    },
)

@Composable
private fun SourceHealthState.shortLabel(): String = stringResource(
    when (this) {
        SourceHealthState.HEALTHY -> R.string.status_ready
        SourceHealthState.NEEDS_ATTENTION -> R.string.status_attention
        SourceHealthState.FALLBACK_REQUIRED -> R.string.status_fallback
    },
)

@Composable
private fun SourceHealthState.statusColor(): Color = when (this) {
    SourceHealthState.HEALTHY -> MaterialTheme.colorScheme.primary
    SourceHealthState.NEEDS_ATTENTION -> MaterialTheme.colorScheme.tertiary
    SourceHealthState.FALLBACK_REQUIRED -> MaterialTheme.colorScheme.error
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892)
@Preview(
    name = "Overview dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "Overview 200% font",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    fontScale = 2f,
)
@Composable
private fun OverviewPreview() {
    BillTheme {
        OverviewScreen(
            state = OverviewPresenter.present(syntheticOverviewSnapshot(), amountsMasked = false),
            onAction = {},
        )
    }
}
