package dev.bill.feature.accounts

import android.content.res.Configuration
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.bill.application.AccountSummary
import dev.bill.application.OperationError
import dev.bill.application.InvestmentPositionSummary
import dev.bill.core.designsystem.component.LedgerCard
import dev.bill.core.designsystem.component.MoneyText
import dev.bill.core.designsystem.component.PosterPanel
import dev.bill.core.designsystem.component.SectionMarker
import dev.bill.core.designsystem.theme.BillTheme
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import dev.bill.core.model.allowsUserAccountCurrency
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CreateAccountInput(
    val name: String,
    val type: AccountType,
    val openingBalance: String,
    val currency: CurrencyCode = CurrencyCode.CNY,
)

data class CreateInvestmentPositionInput(
    val name: String = "",
    val instrumentCode: String = "",
    val currentValue: String = "",
    val units: String = "",
    val costBasis: String = "",
    val wasOcrPrefilled: Boolean = false,
)

@Composable
fun AccountsScreen(
    accounts: List<AccountSummary>,
    investmentPositions: List<InvestmentPositionSummary>,
    amountsMasked: Boolean,
    isSubmitting: Boolean,
    isInvestmentSubmitting: Boolean,
    isInvestmentOcrRunning: Boolean,
    operationError: OperationError?,
    showCreateSheet: Boolean,
    showInvestmentSheet: Boolean,
    investmentFormSeed: CreateInvestmentPositionInput,
    onCreateRequested: () -> Unit,
    onInvestmentCreateRequested: () -> Unit,
    onInvestmentOcrRequested: (CreateInvestmentPositionInput) -> Unit,
    onDismissCreate: () -> Unit,
    onDismissInvestment: () -> Unit,
    onCreateAccount: (CreateAccountInput) -> Unit,
    onCreateInvestment: (CreateInvestmentPositionInput) -> Unit,
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
            SectionMarker(index = "04", title = stringResource(R.string.accounts_title))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.accounts_heading),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(R.string.accounts_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onCreateRequested,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(stringResource(R.string.add_account))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onInvestmentCreateRequested,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(stringResource(R.string.add_investment_position))
            }
        }

        if (investmentPositions.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.investment_positions_heading),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.investment_positions_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(investmentPositions, key = InvestmentPositionSummary::id) { position ->
                InvestmentPositionRow(position = position, amountsMasked = amountsMasked)
            }
        }

        val fundingAccounts = accounts.filter { account ->
            account.type != AccountType.INVESTMENT_SECURITY
        }
        if (fundingAccounts.isEmpty() && investmentPositions.isEmpty()) {
            item {
                PosterPanel(contentPadding = PaddingValues(20.dp)) {
                    Text(
                        text = stringResource(R.string.accounts_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.accounts_empty_body),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        } else {
            if (fundingAccounts.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.funding_accounts_heading),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            items(fundingAccounts, key = AccountSummary::id) { account ->
                AccountRow(account = account, amountsMasked = amountsMasked)
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showCreateSheet) {
        CreateAccountSheet(
            isSubmitting = isSubmitting,
            operationError = operationError,
            onDismiss = onDismissCreate,
            onSubmit = onCreateAccount,
        )
    }
    if (showInvestmentSheet) {
        CreateInvestmentPositionSheet(
            initial = investmentFormSeed,
            isSubmitting = isInvestmentSubmitting,
            isOcrRunning = isInvestmentOcrRunning,
            operationError = operationError,
            onOcrRequested = onInvestmentOcrRequested,
            onDismiss = onDismissInvestment,
            onSubmit = onCreateInvestment,
        )
    }
}

@Composable
private fun InvestmentPositionRow(
    position: InvestmentPositionSummary,
    amountsMasked: Boolean,
) {
    LedgerCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = position.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    position.instrumentCode?.let { code ->
                        Text(
                            text = stringResource(R.string.investment_code_value, code),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                MoneyText(
                    amount = position.currentValue,
                    masked = amountsMasked,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                text = stringResource(
                    R.string.investment_value_as_of,
                    position.asOf.atZone(ZoneId.systemDefault()).format(investmentDateFormatter),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (position.units != null || position.costBasis != null) {
                HorizontalDivider()
                position.units?.let { units ->
                    Text(
                        text = stringResource(
                            R.string.investment_units_value,
                            units.stripTrailingZeros().toPlainString(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                position.costBasis?.let { cost ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.investment_cost_basis),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        MoneyText(
                            amount = cost,
                            masked = amountsMasked,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Text(
                text = stringResource(
                    if (position.wasOcrPrefilled) {
                        R.string.investment_source_ocr_confirmed
                    } else {
                        R.string.investment_source_manual
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateInvestmentPositionSheet(
    initial: CreateInvestmentPositionInput,
    isSubmitting: Boolean,
    isOcrRunning: Boolean,
    operationError: OperationError?,
    onOcrRequested: (CreateInvestmentPositionInput) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (CreateInvestmentPositionInput) -> Unit,
) {
    var name by rememberSaveable(initial) { mutableStateOf(initial.name) }
    var code by rememberSaveable(initial) { mutableStateOf(initial.instrumentCode) }
    var currentValue by rememberSaveable(initial) { mutableStateOf(initial.currentValue) }
    var units by rememberSaveable(initial) { mutableStateOf(initial.units) }
    var costBasis by rememberSaveable(initial) { mutableStateOf(initial.costBasis) }

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
                text = stringResource(R.string.create_investment_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(R.string.create_investment_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    onOcrRequested(
                        CreateInvestmentPositionInput(
                            name = name,
                            instrumentCode = code,
                            currentValue = currentValue,
                            units = units,
                            costBasis = costBasis,
                            wasOcrPrefilled = initial.wasOcrPrefilled,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                enabled = !isSubmitting && !isOcrRunning,
                shape = MaterialTheme.shapes.small,
            ) {
                if (isOcrRunning) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.reading_investment_screenshot),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    Text(stringResource(R.string.prefill_investment_from_screenshot))
                }
            }
            Text(
                text = stringResource(R.string.investment_ocr_boundary),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.investment_name)) },
                supportingText = { Text(stringResource(R.string.investment_name_hint)) },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.investment_code_optional)) },
                supportingText = { Text(stringResource(R.string.investment_code_hint)) },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = currentValue,
                onValueChange = { currentValue = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.investment_current_value)) },
                prefix = { Text("¥") },
                supportingText = { Text(stringResource(R.string.investment_current_value_hint)) },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = units,
                onValueChange = { units = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.investment_units_optional)) },
                supportingText = { Text(stringResource(R.string.investment_units_hint)) },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = costBasis,
                onValueChange = { costBasis = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.investment_cost_optional)) },
                prefix = { Text("¥") },
                supportingText = { Text(stringResource(R.string.investment_cost_hint)) },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
            )
            operationError?.investmentErrorMessage()?.let { message ->
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
                        CreateInvestmentPositionInput(
                            name = name,
                            instrumentCode = code,
                            currentValue = currentValue,
                            units = units,
                            costBasis = costBasis,
                            wasOcrPrefilled = initial.wasOcrPrefilled,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = !isSubmitting && name.isNotBlank() && currentValue.isNotBlank(),
                shape = MaterialTheme.shapes.small,
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.creating_investment),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    Text(stringResource(R.string.create_investment))
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
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountRow(
    account: AccountSummary,
    amountsMasked: Boolean,
) {
    LedgerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = account.type.localizedName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(
                    amount = account.displayBalance,
                    masked = amountsMasked,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (account.isLiability) {
                    Text(
                        text = stringResource(R.string.amount_owed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateAccountSheet(
    isSubmitting: Boolean,
    operationError: OperationError?,
    onDismiss: () -> Unit,
    onSubmit: (CreateAccountInput) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var openingBalance by rememberSaveable { mutableStateOf("") }
    var selectedTypeName by rememberSaveable { mutableStateOf(AccountType.ASSET_CASH.name) }
    var selectedCurrencyCode by rememberSaveable { mutableStateOf(CurrencyCode.CNY.value) }
    val selectedType = AccountType.valueOf(selectedTypeName)
    val selectedCurrency = CurrencyCode(selectedCurrencyCode)
    val selectableCurrencies = listOf(CurrencyCode.CNY, CurrencyCode.USD)
        .filter(selectedType::allowsUserAccountCurrency)

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
                text = stringResource(R.string.create_account_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(R.string.create_account_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.account_name)) },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Text(
                text = stringResource(R.string.account_type),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                creatableAccountTypes.forEach { accountType ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(
                                selected = selectedType == accountType,
                                enabled = !isSubmitting,
                                onClick = {
                                    selectedTypeName = accountType.name
                                    if (!accountType.allowsUserAccountCurrency(selectedCurrency)) {
                                        selectedCurrencyCode = CurrencyCode.CNY.value
                                    }
                                },
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = selectedType == accountType,
                            onClick = null,
                            enabled = !isSubmitting,
                        )
                        Text(accountType.localizedName(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (selectableCurrencies.size > 1) {
                Text(
                    text = stringResource(R.string.account_currency),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectableCurrencies.forEach { currency ->
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
                value = openingBalance,
                onValueChange = { openingBalance = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.opening_balance)) },
                supportingText = { Text(stringResource(R.string.opening_balance_hint)) },
                prefix = { Text(selectedCurrency.inputPrefix()) },
                singleLine = true,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
            )

            operationError?.accountErrorMessage()?.let { errorMessage ->
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()
            Button(
                onClick = {
                    onSubmit(
                        CreateAccountInput(
                            name = name,
                            type = selectedType,
                            openingBalance = openingBalance,
                            currency = selectedCurrency,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = !isSubmitting && name.isNotBlank(),
                shape = MaterialTheme.shapes.small,
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(stringResource(R.string.creating_account))
                } else {
                    Text(stringResource(R.string.create_account))
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
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountType.localizedName(): String = stringResource(
    when (this) {
        AccountType.ASSET_CASH -> R.string.account_type_cash
        AccountType.ASSET_BANK -> R.string.account_type_bank
        AccountType.ASSET_EWALLET_BALANCE -> R.string.account_type_wallet
        AccountType.LIABILITY_CC -> R.string.account_type_credit_card
        else -> R.string.account_type_other
    },
)

@Composable
private fun CurrencyCode.localizedName(): String = stringResource(
    when (this) {
        CurrencyCode.CNY -> R.string.account_currency_cny
        CurrencyCode.USD -> R.string.account_currency_usd
        else -> R.string.account_currency_other
    },
)

private fun CurrencyCode.inputPrefix(): String = when (this) {
    CurrencyCode.CNY -> "¥"
    CurrencyCode.USD -> "$"
    else -> value
}

@Composable
private fun OperationError.accountErrorMessage(): String? = when (this) {
    OperationError.INVALID_NAME -> stringResource(R.string.error_invalid_account_name)
    OperationError.INVALID_AMOUNT -> stringResource(R.string.error_invalid_opening_balance)
    OperationError.DUPLICATE_ACCOUNT_NAME -> stringResource(R.string.error_duplicate_account)
    OperationError.UNSUPPORTED_ACCOUNT_TYPE,
    OperationError.UNSUPPORTED_CURRENCY,
    -> stringResource(R.string.error_unsupported_account)
    else -> null
}

@Composable
private fun OperationError.investmentErrorMessage(): String? = when (this) {
    OperationError.INVALID_NAME -> stringResource(R.string.error_invalid_investment_name)
    OperationError.INVALID_AMOUNT -> stringResource(R.string.error_invalid_investment_value)
    OperationError.INVALID_INSTRUMENT_CODE ->
        stringResource(R.string.error_invalid_investment_code)
    OperationError.INVALID_UNITS -> stringResource(R.string.error_invalid_investment_units)
    OperationError.INVALID_COST_BASIS -> stringResource(R.string.error_invalid_investment_cost)
    OperationError.DUPLICATE_ACCOUNT_NAME -> stringResource(R.string.error_duplicate_investment)
    else -> null
}

private val creatableAccountTypes = listOf(
    AccountType.ASSET_CASH,
    AccountType.ASSET_BANK,
    AccountType.ASSET_EWALLET_BALANCE,
    AccountType.LIABILITY_CC,
)

private val investmentDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Preview(
    name = "Accounts dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AccountsPreview() {
    BillTheme {
        AccountsScreen(
            accounts = listOf(
                AccountSummary(
                    id = "preview-cash",
                    name = "随身现金",
                    type = AccountType.ASSET_CASH,
                    displayBalance = Money.cny(12_300),
                    isLiability = false,
                ),
            ),
            investmentPositions = emptyList(),
            amountsMasked = false,
            isSubmitting = false,
            isInvestmentSubmitting = false,
            isInvestmentOcrRunning = false,
            operationError = null,
            showCreateSheet = false,
            showInvestmentSheet = false,
            investmentFormSeed = CreateInvestmentPositionInput(),
            onCreateRequested = {},
            onInvestmentCreateRequested = {},
            onInvestmentOcrRequested = {},
            onDismissCreate = {},
            onDismissInvestment = {},
            onCreateAccount = {},
            onCreateInvestment = {},
            contentPadding = PaddingValues(),
        )
    }
}
