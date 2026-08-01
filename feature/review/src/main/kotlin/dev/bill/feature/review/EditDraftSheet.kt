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
import dev.bill.application.AccountSummary
import dev.bill.application.DraftSummary
import dev.bill.application.DraftSummaryKind
import dev.bill.application.InvestmentPositionSummary
import dev.bill.application.OperationError
import dev.bill.application.canFund
import dev.bill.core.domain.ObservedChannel
import dev.bill.core.domain.allowsCurrency
import dev.bill.core.model.AccountType
import dev.bill.core.model.Money
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDraftSheet(
    draft: DraftSummary,
    accounts: List<AccountSummary>,
    investmentPositions: List<InvestmentPositionSummary>,
    isSaving: Boolean,
    operationError: OperationError?,
    onSubmit: (EditDraftInput) -> Unit,
    onInputChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initialAmount = draft.amount.toEditableAmount()
    val initialTime = draft.occurredAt.atZone(ZoneId.systemDefault()).format(editTimeFormatter)
    var selectedKindName by rememberSaveable(draft.id) { mutableStateOf(draft.kind.name) }
    var amount by rememberSaveable(draft.id) { mutableStateOf(initialAmount) }
    var counterparty by rememberSaveable(draft.id) { mutableStateOf(draft.counterparty) }
    var note by rememberSaveable(draft.id) { mutableStateOf(draft.note.orEmpty()) }
    var occurredAtText by rememberSaveable(draft.id) { mutableStateOf(initialTime) }
    var selectedChannelName by rememberSaveable(draft.id) {
        mutableStateOf(draft.observedChannel.name)
    }
    var fundingAccountId by rememberSaveable(draft.id) {
        mutableStateOf(draft.fundingAccountId)
    }
    var investmentAccountId by rememberSaveable(draft.id) {
        mutableStateOf(draft.investmentAccountId)
    }
    var timeInvalid by rememberSaveable(draft.id) { mutableStateOf(false) }
    var showDiscardConfirmation by rememberSaveable(draft.id) { mutableStateOf(false) }

    val selectedKind = DraftSummaryKind.valueOf(selectedKindName)
    val selectedChannel = ObservedChannel.valueOf(selectedChannelName)
    val eligibleAccounts = accounts.filter { account ->
        account.displayBalance.currency == draft.amount.currency && account.canFund(selectedKind)
    }
    val availableInvestmentPositions = investmentPositions.filter { position ->
        position.currentValue.currency == draft.amount.currency
    }
    val hasValidFundingAccount = fundingAccountId == null ||
        eligibleAccounts.any { account -> account.id == fundingAccountId }
    val hasValidInvestmentTarget = selectedKind != DraftSummaryKind.INVEST_BUY ||
        availableInvestmentPositions.any { position -> position.accountId == investmentAccountId }
    val isDirty = selectedKind != draft.kind ||
        amount != initialAmount ||
        counterparty != draft.counterparty ||
        note != draft.note.orEmpty() ||
        occurredAtText != initialTime ||
        selectedChannel != draft.observedChannel ||
        fundingAccountId != draft.fundingAccountId ||
        investmentAccountId != draft.investmentAccountId

    fun requestDismiss() {
        if (!isSaving) {
            if (isDirty) showDiscardConfirmation = true else onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::requestDismiss,
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
                text = stringResource(R.string.edit_draft_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(R.string.edit_draft_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ChoiceGroup(
                title = stringResource(R.string.economic_type),
                values = DraftSummaryKind.entries,
                selected = selectedKind,
                enabled = !isSaving,
                label = { kind ->
                    when (kind) {
                        DraftSummaryKind.EXPENSE -> stringResource(R.string.expense)
                        DraftSummaryKind.INCOME -> stringResource(R.string.income)
                        DraftSummaryKind.INVEST_BUY -> stringResource(R.string.investment_buy)
                    }
                },
                onSelect = { kind ->
                    onInputChanged()
                    selectedKindName = kind.name
                    if (kind != DraftSummaryKind.INVEST_BUY) investmentAccountId = null
                },
            )

            OutlinedTextField(
                value = amount,
                onValueChange = {
                    onInputChanged()
                    amount = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.amount)) },
                prefix = { Text(draft.amount.currency.value) },
                supportingText = { Text(stringResource(R.string.amount_hint)) },
                singleLine = true,
                enabled = !isSaving,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = counterparty,
                onValueChange = {
                    onInputChanged()
                    counterparty = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.counterparty)) },
                supportingText = { Text(stringResource(R.string.counterparty_hint)) },
                singleLine = true,
                enabled = !isSaving,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = occurredAtText,
                onValueChange = {
                    onInputChanged()
                    occurredAtText = it
                    timeInvalid = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.occurred_at)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (timeInvalid) R.string.error_invalid_time else R.string.occurred_at_hint,
                        ),
                    )
                },
                isError = timeInvalid,
                singleLine = true,
                enabled = !isSaving,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = note,
                onValueChange = {
                    onInputChanged()
                    note = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.note_optional)) },
                supportingText = { Text(stringResource(R.string.note_hint)) },
                minLines = 2,
                maxLines = 4,
                enabled = !isSaving,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )

            ChoiceGroup(
                title = stringResource(R.string.observed_channel),
                values = ObservedChannel.entries.filter { channel ->
                    channel.allowsCurrency(draft.amount.currency)
                },
                selected = selectedChannel,
                enabled = !isSaving,
                label = { channel -> channel.localizedName() },
                onSelect = { channel ->
                    onInputChanged()
                    selectedChannelName = channel.name
                },
            )
            Text(
                text = stringResource(R.string.capture_source_immutable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.funding_account),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (eligibleAccounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_eligible_accounts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    eligibleAccounts.forEach { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .selectable(
                                    selected = fundingAccountId == account.id,
                                    enabled = !isSaving,
                                    onClick = {
                                        onInputChanged()
                                        fundingAccountId = account.id
                                    },
                                )
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = fundingAccountId == account.id,
                                onClick = null,
                                enabled = !isSaving,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(account.name)
                                Text(
                                    text = if (account.type == AccountType.LIABILITY_CC) {
                                        stringResource(R.string.credit_account)
                                    } else {
                                        stringResource(R.string.asset_account)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (selectedKind == DraftSummaryKind.INVEST_BUY) {
                ChoiceGroup(
                    title = stringResource(R.string.investment_position_target),
                    values = availableInvestmentPositions,
                    selected = availableInvestmentPositions.firstOrNull {
                        it.accountId == investmentAccountId
                    },
                    enabled = !isSaving,
                    label = { position -> position.name },
                    onSelect = { position ->
                        onInputChanged()
                        investmentAccountId = position.accountId
                    },
                )
            }

            operationError?.editErrorMessage()?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!hasValidFundingAccount || !hasValidInvestmentTarget) {
                Text(
                    text = stringResource(R.string.edit_draft_selection_invalid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    val occurredAt = if (occurredAtText == initialTime) {
                        draft.occurredAt
                    } else {
                        runCatching {
                            LocalDateTime.parse(occurredAtText, editTimeFormatter)
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                        }.getOrNull()
                    }
                    if (occurredAt == null) {
                        timeInvalid = true
                    } else {
                        onSubmit(
                            EditDraftInput(
                                kind = selectedKind,
                                amount = amount,
                                counterparty = counterparty,
                                note = note,
                                occurredAt = occurredAt,
                                observedChannel = selectedChannel,
                                fundingAccountId = fundingAccountId,
                                investmentAccountId = investmentAccountId,
                            ),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = !isSaving &&
                    amount.isNotBlank() &&
                    counterparty.isNotBlank() &&
                    hasValidFundingAccount &&
                    hasValidInvestmentTarget,
                shape = MaterialTheme.shapes.small,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.edit_draft_saving),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    Text(stringResource(R.string.edit_draft_save))
                }
            }
            TextButton(
                onClick = ::requestDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !isSaving,
            ) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text(stringResource(R.string.discard_draft_changes_title)) },
            text = { Text(stringResource(R.string.discard_draft_changes_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onDismiss()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.discard_draft_changes),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text(stringResource(R.string.keep_editing))
                }
            },
        )
    }
}

@Composable
private fun <T> ChoiceGroup(
    title: String,
    values: List<T>,
    selected: T?,
    enabled: Boolean,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        values.forEach { value ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selected == value,
                        enabled = enabled,
                        onClick = { onSelect(value) },
                    )
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == value, onClick = null, enabled = enabled)
                Text(label(value))
            }
        }
    }
}

@Composable
private fun OperationError.editErrorMessage(): String? = when (this) {
    OperationError.INVALID_AMOUNT -> stringResource(R.string.error_invalid_amount)
    OperationError.INVALID_COUNTERPARTY -> stringResource(R.string.error_invalid_counterparty)
    OperationError.NOTE_TOO_LONG -> stringResource(R.string.error_note_too_long)
    OperationError.INVALID_TIME -> stringResource(R.string.error_invalid_time)
    OperationError.ACCOUNT_NOT_FOUND -> stringResource(R.string.error_account_not_found)
    OperationError.UNSUPPORTED_ACCOUNT_TYPE,
    OperationError.ACCOUNT_REQUIRED,
    -> stringResource(R.string.edit_draft_selection_invalid)
    OperationError.NOT_FOUND,
    OperationError.INVALID_STATE,
    -> stringResource(R.string.error_draft_changed)
    else -> stringResource(R.string.error_review_failed)
}

private fun Money.toEditableAmount(): String {
    val whole = minorUnits / 100L
    val fraction = (minorUnits % 100L).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
}

private val editTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
