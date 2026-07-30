package dev.bill.feature.review

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.bill.application.DraftSummary
import dev.bill.application.DraftSummaryKind
import dev.bill.application.ReconciliationCaseKind
import dev.bill.application.ReconciliationCaseSummary
import dev.bill.application.SourceReviewSummary
import dev.bill.application.SourceReviewKind
import dev.bill.core.designsystem.component.LedgerCard
import dev.bill.core.designsystem.component.MoneyText
import dev.bill.core.designsystem.component.PosterPanel
import dev.bill.core.designsystem.component.SectionMarker

@Composable
fun DraftsScreen(
    drafts: List<DraftSummary>,
    sourceReviews: List<SourceReviewSummary>,
    reconciliationCases: List<ReconciliationCaseSummary>,
    amountsMasked: Boolean,
    onAddManualDraft: () -> Unit,
    onImportTextFile: () -> Unit,
    onReviewDraft: (String) -> Unit,
    onReviewSource: (String) -> Unit,
    onReviewReconciliation: (String) -> Unit,
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
            SectionMarker(index = "02", title = stringResource(R.string.drafts_title))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.drafts_heading),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(R.string.drafts_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAddManualDraft,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(stringResource(R.string.add_manual_draft))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onImportTextFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(stringResource(R.string.import_text_file))
            }
        }

        if (drafts.isEmpty() && sourceReviews.isEmpty() && reconciliationCases.isEmpty()) {
            item {
                PosterPanel(contentPadding = PaddingValues(20.dp)) {
                    Text(
                        text = stringResource(R.string.drafts_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.drafts_empty_body),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        if (sourceReviews.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.source_reviews_heading),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.source_reviews_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(
                items = sourceReviews,
                key = { review -> "source-${review.id}" },
            ) { review ->
                SourceReviewRow(
                    review = review,
                    amountsMasked = amountsMasked,
                    onReview = { onReviewSource(review.id) },
                )
            }
        }

        if (reconciliationCases.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.reconciliation_candidates_heading),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.reconciliation_candidates_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(
                items = reconciliationCases,
                key = { case -> "reconciliation-${case.id}" },
            ) { case ->
                ReconciliationRow(
                    case = case,
                    amountsMasked = amountsMasked,
                    onReview = { onReviewReconciliation(case.id) },
                )
            }
        }

        if (drafts.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.ledger_drafts_heading),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(drafts, key = DraftSummary::id) { draft ->
                DraftRow(
                    draft = draft,
                    amountsMasked = amountsMasked,
                    onReview = { onReviewDraft(draft.id) },
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ReconciliationRow(
    case: ReconciliationCaseSummary,
    amountsMasked: Boolean,
    onReview: () -> Unit,
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
                    text = case.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        when (case.kind) {
                            ReconciliationCaseKind.TRANSFER ->
                                R.string.reconciliation_kind_transfer

                            ReconciliationCaseKind.REFUND ->
                                R.string.reconciliation_kind_refund

                            ReconciliationCaseKind.LIABILITY_REPAYMENT ->
                                R.string.reconciliation_kind_repayment
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.reconciliation_candidate_not_automatic),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoneyText(
                amount = case.amount,
                masked = amountsMasked,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onReview,
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.reconciliation_review))
        }
    }
}

@Composable
private fun SourceReviewRow(
    review: SourceReviewSummary,
    amountsMasked: Boolean,
    onReview: () -> Unit,
) {
    LedgerCard {
        val sourceLabel = when (review.kind) {
            SourceReviewKind.SHARED_TEXT -> stringResource(R.string.shared_text_source)
            SourceReviewKind.SELECTED_TEXT_FILE -> stringResource(R.string.selected_text_file_source)
            SourceReviewKind.DELIMITED_STATEMENT_ROW ->
                stringResource(R.string.delimited_statement_source)
            SourceReviewKind.SHARED_RECEIPT_IMAGE ->
                stringResource(R.string.shared_receipt_image_source)
            SourceReviewKind.PHOTO_OCR -> stringResource(R.string.photo_ocr_source)
            SourceReviewKind.NOTIFICATION -> review.notificationRouteLabel
                ?: stringResource(R.string.notification_source)
        }
        val needsConfirmationLabel = when (review.kind) {
            SourceReviewKind.SHARED_TEXT -> stringResource(R.string.shared_text_needs_confirmation)
            SourceReviewKind.SELECTED_TEXT_FILE ->
                stringResource(R.string.selected_text_file_needs_confirmation)
            SourceReviewKind.DELIMITED_STATEMENT_ROW ->
                stringResource(R.string.delimited_statement_needs_confirmation)
            SourceReviewKind.SHARED_RECEIPT_IMAGE ->
                stringResource(R.string.shared_receipt_image_needs_confirmation)
            SourceReviewKind.PHOTO_OCR ->
                stringResource(R.string.photo_ocr_needs_confirmation)
            SourceReviewKind.NOTIFICATION -> stringResource(R.string.notification_needs_confirmation)
        }
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
                    text = review.suggestedCounterparty
                        ?: stringResource(R.string.shared_text_unknown_counterparty),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = needsConfirmationLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (review.isPossibleDuplicate) {
                    Text(
                        text = stringResource(R.string.shared_text_possible_duplicate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            review.suggestedAmount?.let { amount ->
                MoneyText(
                    amount = amount,
                    masked = amountsMasked,
                    style = MaterialTheme.typography.titleMedium,
                )
            } ?: Text(
                text = stringResource(R.string.amount_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onReview,
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.complete_source_review))
        }
    }
}

@Composable
private fun DraftRow(
    draft: DraftSummary,
    amountsMasked: Boolean,
    onReview: () -> Unit,
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
                    text = draft.counterparty,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when (draft.kind) {
                        DraftSummaryKind.EXPENSE -> stringResource(R.string.expense)
                        DraftSummaryKind.INCOME -> stringResource(R.string.income)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (draft.fundingAccountId == null) {
                        stringResource(R.string.funding_account_missing)
                    } else {
                        stringResource(R.string.funding_account_selected)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (draft.fundingAccountId == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            MoneyText(
                amount = draft.amount,
                masked = amountsMasked,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onReview,
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.review_draft))
        }
    }
}
