package dev.bill.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.bill.application.DraftSummaryKind
import dev.bill.application.OperationError
import dev.bill.application.SourceReviewSummary
import dev.bill.application.SourceReviewKind
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import dev.bill.core.model.isSupportedLedgerCurrency
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualDraftSheet(
    isSubmitting: Boolean,
    operationError: OperationError?,
    onDismiss: () -> Unit,
    onSubmit: (ManualDraftInput) -> Unit,
) {
    DraftInputSheet(
        formKey = "manual",
        title = stringResource(R.string.manual_draft_title),
        explanation = stringResource(R.string.manual_draft_explanation),
        initialKind = DraftSummaryKind.EXPENSE,
        initialCurrency = CurrencyCode.CNY,
        availableCurrencies = listOf(CurrencyCode.CNY, CurrencyCode.USD),
        initialAmount = "",
        initialCounterparty = "",
        submitLabel = stringResource(R.string.create_draft),
        submittingLabel = stringResource(R.string.creating_draft),
        isSubmitting = isSubmitting,
        operationError = operationError,
        onDismiss = onDismiss,
        onSubmit = onSubmit,
    )
}

@Composable
fun SourceDraftSheet(
    sourceReview: SourceReviewSummary,
    isSubmitting: Boolean,
    operationError: OperationError?,
    onDismiss: () -> Unit,
    onSubmit: (ManualDraftInput) -> Unit,
    onIgnore: () -> Unit,
) {
    val title = when (sourceReview.kind) {
        SourceReviewKind.SHARED_TEXT -> R.string.source_draft_title
        SourceReviewKind.SELECTED_TEXT_FILE -> R.string.selected_text_file_draft_title
        SourceReviewKind.DELIMITED_STATEMENT_ROW ->
            R.string.delimited_statement_draft_title
        SourceReviewKind.SHARED_RECEIPT_IMAGE -> R.string.shared_receipt_image_draft_title
        SourceReviewKind.PHOTO_OCR -> R.string.photo_ocr_draft_title
        SourceReviewKind.NOTIFICATION -> R.string.notification_draft_title
    }
    val explanation = when (sourceReview.kind) {
        SourceReviewKind.SHARED_TEXT -> R.string.source_draft_explanation
        SourceReviewKind.SELECTED_TEXT_FILE -> R.string.selected_text_file_draft_explanation
        SourceReviewKind.DELIMITED_STATEMENT_ROW ->
            R.string.delimited_statement_draft_explanation
        SourceReviewKind.SHARED_RECEIPT_IMAGE -> R.string.shared_receipt_image_draft_explanation
        SourceReviewKind.PHOTO_OCR -> R.string.photo_ocr_draft_explanation
        SourceReviewKind.NOTIFICATION -> R.string.notification_draft_explanation
    }
    val duplicateExplanation = when (sourceReview.kind) {
        SourceReviewKind.SHARED_TEXT -> R.string.source_draft_duplicate_explanation
        SourceReviewKind.SELECTED_TEXT_FILE ->
            R.string.selected_text_file_draft_duplicate_explanation
        SourceReviewKind.DELIMITED_STATEMENT_ROW ->
            R.string.delimited_statement_draft_duplicate_explanation
        SourceReviewKind.SHARED_RECEIPT_IMAGE ->
            R.string.shared_receipt_image_draft_duplicate_explanation
        SourceReviewKind.PHOTO_OCR ->
            R.string.photo_ocr_draft_duplicate_explanation
        SourceReviewKind.NOTIFICATION -> R.string.notification_draft_duplicate_explanation
    }
    val allowedCurrencies = sourceReview.allowedDraftCurrencies
        .filter(CurrencyCode::isSupportedLedgerCurrency)
        .distinct()
    val suggestedCurrency = sourceReview.suggestedAmount?.currency
    val sourceCurrency = suggestedCurrency
        ?.takeIf { it in allowedCurrencies }
        ?: allowedCurrencies.firstOrNull()
        ?: CurrencyCode.CNY
    val hasDisallowedSuggestedCurrency = suggestedCurrency != null &&
        suggestedCurrency !in allowedCurrencies
    val hasNoAllowedCurrency = allowedCurrencies.isEmpty()
    val occurredAtNotice = sourceReview.suggestedOccurredAt?.let { occurredAt ->
        stringResource(
            R.string.source_occurred_at,
            occurredAt.atZone(ZoneId.systemDefault()).format(sourceTimeFormatter),
        )
    }
    DraftInputSheet(
        formKey = "source-${sourceReview.id}",
        title = stringResource(title),
        explanation = stringResource(
            if (sourceReview.isPossibleDuplicate) {
                duplicateExplanation
            } else {
                explanation
            },
        ),
        initialKind = sourceReview.suggestedKind ?: DraftSummaryKind.EXPENSE,
        initialCurrency = sourceCurrency,
        availableCurrencies = allowedCurrencies.ifEmpty { listOf(sourceCurrency) },
        initialAmount = sourceReview.suggestedAmount
            ?.takeIf { amount -> amount.currency == sourceCurrency }
            ?.toAmountInput()
            .orEmpty(),
        initialCounterparty = sourceReview.suggestedCounterparty.orEmpty(),
        submitLabel = stringResource(R.string.continue_source_draft),
        submittingLabel = stringResource(R.string.saving_source_draft),
        isSubmitting = isSubmitting,
        operationError = operationError,
        onDismiss = onDismiss,
        onSubmit = onSubmit,
        onIgnore = onIgnore,
        notice = when {
            hasNoAllowedCurrency -> stringResource(R.string.source_currency_unavailable)
            hasDisallowedSuggestedCurrency -> stringResource(R.string.source_currency_restricted)
            else -> null
        },
        sourceTimeNotice = occurredAtNotice,
        canSubmit = !hasNoAllowedCurrency,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftInputSheet(
    formKey: String,
    title: String,
    explanation: String,
    initialKind: DraftSummaryKind,
    initialCurrency: CurrencyCode,
    availableCurrencies: List<CurrencyCode>,
    initialAmount: String,
    initialCounterparty: String,
    submitLabel: String,
    submittingLabel: String,
    isSubmitting: Boolean,
    operationError: OperationError?,
    onDismiss: () -> Unit,
    onSubmit: (ManualDraftInput) -> Unit,
    onIgnore: (() -> Unit)? = null,
    notice: String? = null,
    sourceTimeNotice: String? = null,
    canSubmit: Boolean = true,
) {
    var selectedKindName by rememberSaveable(formKey) {
        mutableStateOf(initialKind.name)
    }
    var selectedCurrencyCode by rememberSaveable(formKey) {
        mutableStateOf(initialCurrency.value)
    }
    var amount by rememberSaveable(formKey) { mutableStateOf(initialAmount) }
    var counterparty by rememberSaveable(formKey) { mutableStateOf(initialCounterparty) }
    var note by rememberSaveable(formKey) { mutableStateOf("") }
    var showIgnoreConfirmation by rememberSaveable(formKey) { mutableStateOf(false) }
    val selectedKind = DraftSummaryKind.valueOf(selectedKindName)
    val selectedCurrency = CurrencyCode(selectedCurrencyCode)

    ModalBottomSheet(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            sourceTimeNotice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DraftSummaryKind.entries.forEach { kind ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(
                                selected = selectedKind == kind,
                                enabled = !isSubmitting,
                                onClick = { selectedKindName = kind.name },
                            )
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedKind == kind,
                            onClick = null,
                            enabled = !isSubmitting,
                        )
                        Text(
                            text = when (kind) {
                                DraftSummaryKind.EXPENSE -> stringResource(R.string.expense)
                                DraftSummaryKind.INCOME -> stringResource(R.string.income)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            if (availableCurrencies.size > 1) {
                Text(
                    text = stringResource(R.string.currency),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableCurrencies.forEach { currency ->
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selectedCurrency == currency,
                                    enabled = !isSubmitting,
                                    onClick = { selectedCurrencyCode = currency.value },
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RadioButton(
                                selected = selectedCurrency == currency,
                                onClick = null,
                                enabled = !isSubmitting,
                            )
                            Text(currency.localizedName())
                        }
                    }
                }
            }

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.amount)) },
                prefix = { Text(selectedCurrency.inputPrefix()) },
                supportingText = { Text(stringResource(R.string.amount_hint)) },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = counterparty,
                onValueChange = { counterparty = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.counterparty)) },
                supportingText = { Text(stringResource(R.string.counterparty_hint)) },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.note_optional)) },
                supportingText = { Text(stringResource(R.string.note_hint)) },
                minLines = 2,
                maxLines = 4,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )

            operationError?.draftInputErrorMessage()?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()
            Button(
                onClick = {
                    onSubmit(
                        ManualDraftInput(
                            kind = selectedKind,
                            amount = amount,
                            counterparty = counterparty,
                            note = note,
                            currency = selectedCurrency,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = canSubmit && !isSubmitting && amount.isNotBlank() && counterparty.isNotBlank(),
                shape = MaterialTheme.shapes.small,
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text(
                        text = submittingLabel,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    Text(submitLabel)
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp),
                enabled = !isSubmitting,
            ) {
                Text(stringResource(R.string.cancel))
            }
            if (onIgnore != null) {
                HorizontalDivider()
                TextButton(
                    onClick = { showIgnoreConfirmation = true },
                    modifier = Modifier
                        .align(Alignment.End)
                        .heightIn(min = 48.dp),
                    enabled = !isSubmitting,
                ) {
                    Text(
                        text = stringResource(R.string.ignore_source_review),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showIgnoreConfirmation && onIgnore != null) {
        AlertDialog(
            onDismissRequest = { showIgnoreConfirmation = false },
            title = {
                Text(stringResource(R.string.ignore_source_review_confirmation_title))
            },
            text = {
                Text(stringResource(R.string.ignore_source_review_confirmation_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showIgnoreConfirmation = false
                        onIgnore()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.ignore_source_review),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showIgnoreConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun OperationError.draftInputErrorMessage(): String? = when (this) {
    OperationError.INVALID_AMOUNT -> stringResource(R.string.error_invalid_amount)
    OperationError.INVALID_COUNTERPARTY -> stringResource(R.string.error_invalid_counterparty)
    OperationError.NOTE_TOO_LONG -> stringResource(R.string.error_note_too_long)
    OperationError.SOURCE_CURRENCY_NOT_ALLOWED ->
        stringResource(R.string.error_source_currency_not_allowed)
    else -> null
}

private fun Money.toAmountInput(): String {
    val whole = minorUnits / 100L
    val fraction = (minorUnits % 100L).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
}

@Composable
private fun CurrencyCode.localizedName(): String = stringResource(
    when (this) {
        CurrencyCode.CNY -> R.string.currency_cny
        CurrencyCode.USD -> R.string.currency_usd
        else -> R.string.currency_unknown
    },
)

private fun CurrencyCode.inputPrefix(): String = when (this) {
    CurrencyCode.CNY -> "¥"
    CurrencyCode.USD -> "$"
    else -> value
}

private val sourceTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
