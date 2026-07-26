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
import dev.bill.core.designsystem.component.LedgerCard
import dev.bill.core.designsystem.component.MoneyText
import dev.bill.core.designsystem.component.PosterPanel
import dev.bill.core.designsystem.component.SectionMarker
import dev.bill.core.designsystem.theme.BillTheme
import dev.bill.core.model.AccountType
import dev.bill.core.model.CurrencyCode
import dev.bill.core.model.Money
import dev.bill.core.model.allowsUserAccountCurrency

data class CreateAccountInput(
    val name: String,
    val type: AccountType,
    val openingBalance: String,
    val currency: CurrencyCode = CurrencyCode.CNY,
)

@Composable
fun AccountsScreen(
    accounts: List<AccountSummary>,
    amountsMasked: Boolean,
    isSubmitting: Boolean,
    operationError: OperationError?,
    showCreateSheet: Boolean,
    onCreateRequested: () -> Unit,
    onDismissCreate: () -> Unit,
    onCreateAccount: (CreateAccountInput) -> Unit,
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
        }

        if (accounts.isEmpty()) {
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
            items(accounts, key = AccountSummary::id) { account ->
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

private val creatableAccountTypes = listOf(
    AccountType.ASSET_CASH,
    AccountType.ASSET_BANK,
    AccountType.ASSET_EWALLET_BALANCE,
    AccountType.LIABILITY_CC,
)

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
            amountsMasked = false,
            isSubmitting = false,
            operationError = null,
            showCreateSheet = false,
            onCreateRequested = {},
            onDismissCreate = {},
            onCreateAccount = {},
            contentPadding = PaddingValues(),
        )
    }
}
