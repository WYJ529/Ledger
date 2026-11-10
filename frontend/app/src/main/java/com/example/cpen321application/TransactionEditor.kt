package com.example.cpen321application

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionEditor(model: LedgerViewModel) {
    val draft = model.draft
    val enabled = !model.busy
    val errors = if (model.editorAttempted) LedgerDomain.validate(draft) else emptyMap()
    var dateDialog by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("ledger-editor"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { LedgerMessages(model) }
        item {
            Text(if (model.page == LedgerPage.ImportEditor) t("Check the details") else if (model.editingRecord == null) t("Make it a habit.") else t("Transaction details"),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (model.page == LedgerPage.ImportEditor) Text(t("Changes apply to the review. Confirm the import when every selected row is ready."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = draft.kind == "expense", onClick = { model.updateDraft(draft.copy(kind = "expense")) }, enabled = enabled, label = { Text(t("Expense")) })
                FilterChip(selected = draft.kind == "income", onClick = { model.updateDraft(draft.copy(kind = "income")) }, enabled = enabled, label = { Text(t("Income")) })
            }
            errors["kind"]?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
        }
        item {
            LedgerTextField("Amount", draft.amount, { model.updateDraft(draft.copy(amount = it.take(24))) }, enabled, errors["amount"],
                options = LedgerTextOptions(keyboardType = KeyboardType.Decimal), trailing = {
                    CurrencyMenu(draft.currency, enabled) { model.updateDraft(draft.copy(currency = it)) }
                })
        }
        item {
            LedgerTextField("Date (YYYY-MM-DD)", draft.date, { model.updateDraft(draft.copy(date = it.take(10))) }, enabled, errors["date"], options = LedgerTextOptions(keyboardType = KeyboardType.Ascii))
            BarButton(enabled = enabled, onClick = { dateDialog = true }) { Text(t("Choose date")) }
        }
        item {
            LedgerTextField(if (draft.kind == "income") "Merchant / payer" else "Merchant / payee", draft.merchant,
                { model.updateDraft(draft.copy(merchant = it.take(120))) }, enabled, errors["merchant"])
        }
        item {
            LedgerChoice("Category", draft.category, LedgerDomain.categories, enabled) { model.updateDraft(draft.copy(category = it)) }
            errors["category"]?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
        }
        item {
            LedgerTextField("Notes (optional)", draft.notes, { model.updateDraft(draft.copy(notes = it.take(1000))) }, enabled, errors["notes"], options = LedgerTextOptions(multiline = true))
        }
        if (draft.currency != model.baseCurrency) item {
            HistoricalCurrencyHelp(draft.currency, model.baseCurrency)
        }
        if (model.page == LedgerPage.Editor) model.editingRecord?.let { record ->
            item {
                SavedConversion(record)
            }
            item {
                BarButton(enabled = enabled, onClick = { model.askDelete(record) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(t("Delete transaction")) }
            }
        }
    }
    if (dateDialog) TransactionDateDialog(model, onDismiss = { dateDialog = false })
}

@Composable
private fun LedgerTextField(
    label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, error: String?,
    options: LedgerTextOptions = LedgerTextOptions(),
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(t(label)) }, enabled = enabled,
        trailingIcon = trailing, isError = error != null, supportingText = (error ?: options.helper)?.let { { Text(t(it)) } },
        singleLine = !options.multiline, minLines = if (options.multiline) 3 else 1, maxLines = if (options.multiline) 6 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = options.keyboardType, capitalization = if (options.keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            imeAction = if (options.multiline) ImeAction.Default else ImeAction.Next), modifier = Modifier.fillMaxWidth())
}

private data class LedgerTextOptions(
    val keyboardType: KeyboardType = KeyboardType.Text, val helper: String? = null, val multiline: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDateDialog(model: LedgerViewModel, onDismiss: () -> Unit) {
    val draft = model.draft
    val today = LocalDate.now(ZoneOffset.UTC)
    val initial = draft.date.takeIf { validLedgerDate(it, today) }?.let(LocalDate::parse)
        ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initial,
        yearRange = 1999..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return validLedgerDate(date.toString(), today)
            }
        })
    DatePickerDialog(onDismissRequest = { onDismiss() },
        confirmButton = { BarButton(enabled = state.selectedDateMillis != null, onClick = {
            state.selectedDateMillis?.let { millis -> model.updateDraft(draft.copy(date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())) }
            onDismiss()
        }) { Text(t("Use date")) } },
        dismissButton = { BarButton(onClick = { onDismiss() }) { Text(t("Cancel")) } },
    ) { DatePicker(state = state) }
}

@Composable
private fun SavedConversion(record: LedgerRecord) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Saved conversion"), style = MaterialTheme.typography.titleSmall)
            Text(tf("Original: %1\$s", Money.display(record.amountMinor, record.input.currency)))
            Text(tf("Reported: %1\$s", Money.display(record.fx.convertedMinor, record.fx.baseCurrency)))
            Text(tf("1 %1\$s = %2\$s %3\$s\nRate date: %4\$s\nSource: %5\$s", record.input.currency, record.fx.rate, record.fx.baseCurrency, record.fx.rateDate, record.fx.source),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (record.sourceFileName.isNotBlank()) Text(tf("Imported from: %1\$s", record.sourceFileName), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HistoricalCurrencyHelp(originalCurrency: String, baseCurrency: String) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("Historical currency conversion"), style = MaterialTheme.typography.titleSmall)
            Text(tf("On save, %1\$s is converted to %2\$s using the latest available rate on or before the transaction date. Previously saved rates work offline.", originalCurrency, baseCurrency),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
