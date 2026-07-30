package dev.bill.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bill.core.model.CurrencyCode
import dev.bill.source.genericdelimited.DelimitedDelimiter
import dev.bill.source.genericdelimited.StatementAmountFormat
import dev.bill.source.genericdelimited.StatementDateFormat
import dev.bill.source.genericdelimited.StatementDirection
import dev.bill.source.genericdelimited.StatementMappingError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportMethodSheet(
    onCsvSelected: () -> Unit,
    onTsvSelected: () -> Unit,
    onPlainTextSelected: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatementImportHeading(
                title = stringResource(R.string.import_statement_title),
                step = stringResource(R.string.import_statement_step_choose),
            )
            Text(
                text = stringResource(R.string.import_statement_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ImportChoiceButton(
                title = stringResource(R.string.import_csv_title),
                body = stringResource(R.string.import_csv_body),
                onClick = onCsvSelected,
            )
            ImportChoiceButton(
                title = stringResource(R.string.import_tsv_title),
                body = stringResource(R.string.import_tsv_body),
                onClick = onTsvSelected,
            )
            ImportChoiceButton(
                title = stringResource(R.string.import_plain_text_title),
                body = stringResource(R.string.import_plain_text_body),
                onClick = onPlainTextSelected,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = stringResource(R.string.import_file_local_only),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.statement_import_cancel))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatementImportSheet(
    state: StatementImportUiState,
    onInputChanged: (StatementImportMappingInput) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is StatementImportUiState.Idle) return
    var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }
    var showStopConfirmation by rememberSaveable { mutableStateOf(false) }
    val canDismissImmediately = state !is StatementImportUiState.Mapping
    val isImporting = state is StatementImportUiState.Importing

    ModalBottomSheet(
        onDismissRequest = {
            when {
                isImporting -> showStopConfirmation = true
                canDismissImmediately -> onDismiss()
                else -> showDiscardConfirmation = true
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        when (state) {
            StatementImportUiState.Idle -> Unit
            is StatementImportUiState.Reading -> StatementImportReadingContent(state)
            is StatementImportUiState.Mapping -> StatementImportMappingContent(
                state = state,
                onInputChanged = onInputChanged,
                onConfirm = onConfirm,
                onCancel = { showDiscardConfirmation = true },
            )

            is StatementImportUiState.Importing -> StatementImportProgressContent(
                state = state,
                onStop = { showStopConfirmation = true },
            )
            is StatementImportUiState.Completed -> StatementImportCompletedContent(
                state = state,
                onDismiss = onDismiss,
            )

            is StatementImportUiState.Failed -> StatementImportFailedContent(
                state = state,
                onDismiss = onDismiss,
            )
        }
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text(stringResource(R.string.statement_import_discard_title)) },
            text = { Text(stringResource(R.string.statement_import_discard_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onDismiss()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.statement_import_discard),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text(stringResource(R.string.statement_import_keep_editing))
                }
            },
        )
    }
    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text(stringResource(R.string.statement_import_stop_title)) },
            text = { Text(stringResource(R.string.statement_import_stop_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStopConfirmation = false
                        onDismiss()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.statement_import_stop),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false }) {
                    Text(stringResource(R.string.statement_import_keep_running))
                }
            },
        )
    }
}

@Composable
private fun ImportChoiceButton(
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColumnScope.StatementImportReadingContent(state: StatementImportUiState.Reading) {
    StatementImportCenteredContent {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        StatementImportHeading(
            title = stringResource(R.string.statement_import_reading_title),
            step = stringResource(R.string.statement_import_step_reading),
        )
        Text(
            text = stringResource(
                R.string.statement_import_reading_body,
                state.delimiter.localizedName(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.StatementImportMappingContent(
    state: StatementImportUiState.Mapping,
    onInputChanged: (StatementImportMappingInput) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val input = state.input
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .align(Alignment.CenterHorizontally)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatementImportHeading(
            title = stringResource(R.string.statement_import_mapping_title),
            step = stringResource(R.string.statement_import_step_mapping),
        )
        LinearProgressIndicator(
            progress = { 2f / 3f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = pluralStringResource(
                R.plurals.statement_import_detected,
                state.totalDataRowCount,
                state.delimiter.localizedName(),
                state.totalDataRowCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatementSectionTitle(stringResource(R.string.statement_import_section_fields))
        ColumnSelector(
            label = stringResource(R.string.statement_import_date_column),
            columns = state.columns,
            selectedIndex = input.dateColumnIndex,
            includeNone = false,
            onSelected = { onInputChanged(input.copy(dateColumnIndex = it)) },
        )
        EnumSelector(
            label = stringResource(R.string.statement_import_date_format),
            selected = input.dateFormat,
            options = StatementDateFormat.entries.map { it to it.localizedName() },
            onSelected = { onInputChanged(input.copy(dateFormat = it)) },
        )
        ColumnSelector(
            label = stringResource(R.string.statement_import_amount_column),
            columns = state.columns,
            selectedIndex = input.amountColumnIndex,
            includeNone = false,
            onSelected = { onInputChanged(input.copy(amountColumnIndex = it)) },
        )
        EnumSelector(
            label = stringResource(R.string.statement_import_amount_format),
            selected = input.amountFormat,
            options = StatementAmountFormat.entries.map { it to it.localizedName() },
            onSelected = { onInputChanged(input.copy(amountFormat = it)) },
        )
        ColumnSelector(
            label = stringResource(R.string.statement_import_counterparty_column),
            columns = state.columns,
            selectedIndex = input.counterpartyColumnIndex,
            includeNone = false,
            onSelected = { onInputChanged(input.copy(counterpartyColumnIndex = it)) },
        )
        ColumnSelector(
            label = stringResource(R.string.statement_import_reference_column),
            columns = state.columns,
            selectedIndex = input.referenceColumnIndex,
            includeNone = true,
            onSelected = { onInputChanged(input.copy(referenceColumnIndex = it)) },
        )

        HorizontalDivider()
        StatementSectionTitle(stringResource(R.string.statement_import_section_direction))
        DirectionModeSelector(
            selected = input.directionMode,
            onSelected = { onInputChanged(input.copy(directionMode = it)) },
        )
        when (input.directionMode) {
            StatementImportDirectionMode.SIGNED_AMOUNT -> {
                EnumSelector(
                    label = stringResource(R.string.statement_import_positive_means),
                    selected = input.positiveDirection,
                    options = StatementDirection.entries.map { it to it.localizedName() },
                    onSelected = { onInputChanged(input.copy(positiveDirection = it)) },
                )
            }

            StatementImportDirectionMode.DIRECTION_COLUMN -> {
                ColumnSelector(
                    label = stringResource(R.string.statement_import_direction_column),
                    columns = state.columns,
                    selectedIndex = input.directionColumnIndex,
                    includeNone = false,
                    onSelected = { onInputChanged(input.copy(directionColumnIndex = it)) },
                )
                OutlinedTextField(
                    value = input.inboundTokens,
                    onValueChange = { onInputChanged(input.copy(inboundTokens = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.statement_import_inbound_tokens)) },
                    supportingText = {
                        Text(stringResource(R.string.statement_import_tokens_hint))
                    },
                    minLines = 2,
                    maxLines = 3,
                )
                OutlinedTextField(
                    value = input.outboundTokens,
                    onValueChange = { onInputChanged(input.copy(outboundTokens = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.statement_import_outbound_tokens)) },
                    supportingText = {
                        Text(stringResource(R.string.statement_import_tokens_hint))
                    },
                    minLines = 2,
                    maxLines = 3,
                )
            }
        }

        HorizontalDivider()
        StatementSectionTitle(stringResource(R.string.statement_import_currency))
        CurrencySelector(
            selected = input.currency,
            onSelected = { onInputChanged(input.copy(currency = it)) },
        )
        Text(
            text = stringResource(R.string.statement_import_no_fx),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        StatementImportSamples(state)
        StatementImportPreview(state)

        state.configurationIssue?.let { issue ->
            InlineError(issue.localizedMessage())
        }
        state.operationError?.let { error ->
            InlineError(error.localizedMessage())
        }

        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            enabled = !state.isPreviewing &&
                state.configurationIssue == null &&
                state.preview?.validRowCount?.let { it > 0 } == true,
            shape = MaterialTheme.shapes.small,
        ) {
            if (state.isPreviewing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.statement_import_previewing))
            } else {
                Text(stringResource(R.string.statement_import_confirm))
            }
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.statement_import_cancel))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatementImportSamples(state: StatementImportUiState.Mapping) {
    StatementSectionTitle(stringResource(R.string.statement_import_sample_title))
    Text(
        text = stringResource(R.string.statement_import_sample_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val selectedFields = buildList {
        add(stringResource(R.string.statement_import_date_column) to state.input.dateColumnIndex)
        add(stringResource(R.string.statement_import_amount_column) to state.input.amountColumnIndex)
        if (state.input.directionMode == StatementImportDirectionMode.DIRECTION_COLUMN) {
            add(
                stringResource(R.string.statement_import_direction_column) to
                    state.input.directionColumnIndex,
            )
        }
        add(
            stringResource(R.string.statement_import_counterparty_column) to
                state.input.counterpartyColumnIndex,
        )
        state.input.referenceColumnIndex?.let {
            add(stringResource(R.string.statement_import_reference_column) to it)
        }
    }
    state.sampleRows.forEach { row ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.statement_import_sample_row,
                        row.tableRowIndex + 1,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                selectedFields.forEach { (label, index) ->
                    val value = index?.let(row.cells::getOrNull)
                        ?: stringResource(R.string.statement_import_not_selected)
                    Text(
                        text = "$label · $value",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatementImportPreview(state: StatementImportUiState.Mapping) {
    val preview = state.preview
    if (state.isPreviewing) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = stringResource(R.string.statement_import_previewing_rows),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    if (preview == null) return

    StatementSectionTitle(stringResource(R.string.statement_import_preview_title))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.statement_import_valid_rows,
                    preview.validRowCount,
                    preview.validRowCount,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.statement_import_invalid_rows,
                    preview.invalidRowCount,
                    preview.invalidRowCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (preview.invalidRowCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (preview.invalidRowCount > 0) {
                Text(
                    text = stringResource(R.string.statement_import_invalid_rows_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    preview.rowOutcomes
        .asSequence()
        .filter { it.error != null }
        .take(5)
        .forEach { outcome ->
            Text(
                text = stringResource(
                    R.string.statement_import_row_error,
                    outcome.tableRowIndex + 1,
                    checkNotNull(outcome.error).localizedMessage(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
}

@Composable
private fun ColumnScope.StatementImportProgressContent(
    state: StatementImportUiState.Importing,
    onStop: () -> Unit,
) {
    val progress = if (state.totalRowCount > 0) {
        state.processedRowCount.toFloat() / state.totalRowCount.toFloat()
    } else {
        0f
    }
    StatementImportCenteredContent {
        StatementImportHeading(
            title = stringResource(R.string.statement_import_importing_title),
            step = stringResource(R.string.statement_import_step_importing),
        )
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = pluralStringResource(
                R.plurals.statement_import_importing_count,
                state.totalRowCount,
                state.processedRowCount,
                state.totalRowCount,
            ),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.statement_import_importing_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onStop,
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.statement_import_stop))
        }
    }
}

@Composable
private fun ColumnScope.StatementImportCompletedContent(
    state: StatementImportUiState.Completed,
    onDismiss: () -> Unit,
) {
    StatementImportCenteredContent {
        StatementImportHeading(
            title = stringResource(R.string.statement_import_completed_title),
            step = stringResource(R.string.statement_import_step_completed),
        )
        Text(
            text = pluralStringResource(
                R.plurals.statement_import_completed_body,
                state.readyForReviewCount,
                state.readyForReviewCount,
                state.rejectedRowCount,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.resumedRowCount > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.statement_import_resumed_rows,
                    state.resumedRowCount,
                    state.resumedRowCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.statement_import_completed_next),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(stringResource(R.string.statement_import_done))
        }
    }
}

@Composable
private fun ColumnScope.StatementImportFailedContent(
    state: StatementImportUiState.Failed,
    onDismiss: () -> Unit,
) {
    StatementImportCenteredContent {
        StatementImportHeading(
            title = stringResource(R.string.statement_import_failed_title),
            step = stringResource(R.string.statement_import_step_failed),
        )
        InlineError(state.error.localizedMessage())
        Text(
            text = stringResource(R.string.statement_import_failed_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(stringResource(R.string.statement_import_done))
        }
    }
}

@Composable
private fun ColumnScope.StatementImportCenteredContent(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .align(Alignment.CenterHorizontally)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start,
        content = content,
    )
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun StatementImportHeading(
    title: String,
    step: String,
) {
    Text(
        text = step,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(30.dp)
                .graphicsLayer { rotationZ = -18f }
                .background(MaterialTheme.colorScheme.secondary),
        )
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun StatementSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ColumnSelector(
    label: String,
    columns: List<StatementImportColumnUi>,
    selectedIndex: Int?,
    includeNone: Boolean,
    onSelected: (Int?) -> Unit,
) {
    val options = buildList {
        if (includeNone) add(null to stringResource(R.string.statement_import_none))
        columns.forEach { column ->
            add(
                column.index to stringResource(
                    R.string.statement_import_column_option,
                    column.index + 1,
                    column.label,
                ),
            )
        }
    }
    OptionSelector(
        label = label,
        selected = selectedIndex,
        options = options,
        onSelected = onSelected,
    )
}

@Composable
private fun <T> EnumSelector(
    label: String,
    selected: T,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
) {
    OptionSelector(
        label = label,
        selected = selected,
        options = options,
        onSelected = onSelected,
    )
}

@Composable
private fun <T> OptionSelector(
    label: String,
    selected: T,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second
        ?: stringResource(R.string.statement_import_not_selected)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics {
                        contentDescription = "$label: $selectedLabel"
                    },
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = selectedLabel,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.second,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(option.first)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectionModeSelector(
    selected: StatementImportDirectionMode,
    onSelected: (StatementImportDirectionMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatementImportDirectionMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .selectable(
                        selected = selected == mode,
                        onClick = { onSelected(mode) },
                    )
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == mode, onClick = null)
                Column {
                    Text(
                        text = mode.localizedName(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = mode.localizedDescription(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrencySelector(
    selected: CurrencyCode,
    onSelected: (CurrencyCode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(CurrencyCode.CNY, CurrencyCode.USD).forEach { currency ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .selectable(
                        selected = selected == currency,
                        onClick = { onSelected(currency) },
                    )
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == currency, onClick = null)
                Text(currency.value, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun InlineError(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun DelimitedDelimiter.localizedName(): String = stringResource(
    when (this) {
        DelimitedDelimiter.COMMA -> R.string.statement_import_csv
        DelimitedDelimiter.TAB -> R.string.statement_import_tsv
    },
)

@Composable
private fun StatementImportDirectionMode.localizedName(): String = stringResource(
    when (this) {
        StatementImportDirectionMode.SIGNED_AMOUNT ->
            R.string.statement_import_direction_signed

        StatementImportDirectionMode.DIRECTION_COLUMN ->
            R.string.statement_import_direction_separate
    },
)

@Composable
private fun StatementImportDirectionMode.localizedDescription(): String = stringResource(
    when (this) {
        StatementImportDirectionMode.SIGNED_AMOUNT ->
            R.string.statement_import_direction_signed_body

        StatementImportDirectionMode.DIRECTION_COLUMN ->
            R.string.statement_import_direction_separate_body
    },
)

@Composable
private fun StatementDirection.localizedName(): String = stringResource(
    when (this) {
        StatementDirection.INBOUND -> R.string.statement_import_inbound
        StatementDirection.OUTBOUND -> R.string.statement_import_outbound
    },
)

@Composable
private fun StatementDateFormat.localizedName(): String = stringResource(
    when (this) {
        StatementDateFormat.DATE_DASH -> R.string.statement_date_dash
        StatementDateFormat.DATE_SLASH -> R.string.statement_date_slash
        StatementDateFormat.DATE_TIME_MINUTE_DASH -> R.string.statement_datetime_minute_dash
        StatementDateFormat.DATE_TIME_MINUTE_SLASH -> R.string.statement_datetime_minute_slash
        StatementDateFormat.DATE_TIME_SECOND_DASH -> R.string.statement_datetime_second_dash
        StatementDateFormat.DATE_TIME_SECOND_SLASH -> R.string.statement_datetime_second_slash
        StatementDateFormat.ISO_DATE_TIME_MINUTE -> R.string.statement_datetime_iso_minute
        StatementDateFormat.ISO_DATE_TIME_SECOND -> R.string.statement_datetime_iso_second
    },
)

@Composable
private fun StatementAmountFormat.localizedName(): String = stringResource(
    when (this) {
        StatementAmountFormat.DOT_DECIMAL -> R.string.statement_amount_dot_decimal
        StatementAmountFormat.COMMA_GROUP_DOT_DECIMAL ->
            R.string.statement_amount_comma_group

        StatementAmountFormat.COMMA_DECIMAL -> R.string.statement_amount_comma_decimal
        StatementAmountFormat.DOT_GROUP_COMMA_DECIMAL ->
            R.string.statement_amount_dot_group
    },
)

@Composable
private fun StatementImportConfigurationIssue.localizedMessage(): String = stringResource(
    when (this) {
        StatementImportConfigurationIssue.INCOMPLETE_FIELDS ->
            R.string.statement_mapping_incomplete

        StatementImportConfigurationIssue.DUPLICATE_COLUMNS ->
            R.string.statement_mapping_duplicate

        StatementImportConfigurationIssue.EMPTY_DIRECTION_TOKENS ->
            R.string.statement_mapping_empty_tokens

        StatementImportConfigurationIssue.TOO_MANY_DIRECTION_TOKENS ->
            R.string.statement_mapping_too_many_tokens

        StatementImportConfigurationIssue.INVALID_DIRECTION_TOKEN ->
            R.string.statement_mapping_invalid_token

        StatementImportConfigurationIssue.OVERLAPPING_DIRECTION_TOKENS ->
            R.string.statement_mapping_overlapping_tokens

        StatementImportConfigurationIssue.INVALID_MAPPING ->
            R.string.statement_mapping_invalid
    },
)

@Composable
private fun StatementImportUiError.localizedMessage(): String = stringResource(
    when (this) {
        StatementImportUiError.READER_UNAVAILABLE -> R.string.statement_error_unavailable
        StatementImportUiError.INVALID_DOCUMENT -> R.string.statement_error_invalid_document
        StatementImportUiError.UNSUPPORTED_MEDIA_TYPE ->
            R.string.statement_error_unsupported_type

        StatementImportUiError.CONTENT_TOO_LARGE -> R.string.statement_error_too_large
        StatementImportUiError.READ_FAILED -> R.string.statement_error_read_failed
        StatementImportUiError.DELIMITER_MISMATCH ->
            R.string.statement_error_delimiter

        StatementImportUiError.NO_DATA_ROWS -> R.string.statement_error_no_rows
        StatementImportUiError.MALFORMED_UTF8 -> R.string.statement_error_encoding
        StatementImportUiError.MALFORMED_DOCUMENT -> R.string.statement_error_malformed
        StatementImportUiError.TOO_MANY_RECORDS -> R.string.statement_error_too_many_rows
        StatementImportUiError.TOO_MANY_COLUMNS -> R.string.statement_error_too_many_columns
        StatementImportUiError.CELL_TOO_LONG -> R.string.statement_error_cell_too_long
        StatementImportUiError.RECORD_TOO_LONG -> R.string.statement_error_row_too_long
        StatementImportUiError.CLOSED_SESSION -> R.string.statement_error_session_closed
        StatementImportUiError.IMPORT_INTERRUPTED -> R.string.statement_error_interrupted
        StatementImportUiError.BATCH_IDENTITY_COLLISION,
        StatementImportUiError.ROW_IDENTITY_COLLISION,
        -> R.string.statement_error_identity_collision
    },
)

@Composable
private fun StatementMappingError.localizedMessage(): String = stringResource(
    when (this) {
        StatementMappingError.DELIMITER_MISMATCH,
        StatementMappingError.COLUMN_OUT_OF_RANGE,
        -> R.string.statement_row_error_mapping

        StatementMappingError.EMPTY_DATE,
        StatementMappingError.INVALID_DATE,
        -> R.string.statement_row_error_date

        StatementMappingError.EMPTY_AMOUNT,
        StatementMappingError.INVALID_AMOUNT,
        StatementMappingError.AMOUNT_OUT_OF_RANGE,
        StatementMappingError.ZERO_AMOUNT,
        -> R.string.statement_row_error_amount

        StatementMappingError.SIGNED_AMOUNT_WITH_DIRECTION_COLUMN,
        StatementMappingError.EMPTY_DIRECTION,
        StatementMappingError.UNKNOWN_DIRECTION,
        -> R.string.statement_row_error_direction

        StatementMappingError.EMPTY_COUNTERPARTY,
        StatementMappingError.COUNTERPARTY_TOO_LONG,
        -> R.string.statement_row_error_counterparty

        StatementMappingError.REFERENCE_TOO_LONG -> R.string.statement_row_error_reference
    },
)
