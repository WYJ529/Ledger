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
            Text(if (model.page == LedgerPage.ImportEditor) "Check the details" else if (model.editingRecord == null) "Make it a habit." else "Transaction details",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(if (model.page == LedgerPage.ImportEditor) "Changes apply to the review. Confirm the import when every selected row is ready."
                else "Record the original amount. Your summary uses ${model.baseCurrency}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = draft.kind == "expense", onClick = { model.updateDraft(draft.copy(kind = "expense")) }, enabled = enabled, label = { Text("Expense") })
                FilterChip(selected = draft.kind == "income", onClick = { model.updateDraft(draft.copy(kind = "income")) }, enabled = enabled, label = { Text("Income") })
            }
            errors["kind"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            LedgerTextField("Amount", draft.amount, { model.updateDraft(draft.copy(amount = it.take(24))) }, enabled, errors["amount"],
                keyboardType = KeyboardType.Decimal, helper = "Use a positive amount, without a currency symbol.")
        }
        item {
            LedgerChoice("Original currency", draft.currency, LedgerDomain.currencies, enabled) { model.updateDraft(draft.copy(currency = it)) }
            errors["currency"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            LedgerTextField("Date (YYYY-MM-DD)", draft.date, { model.updateDraft(draft.copy(date = it.take(10))) }, enabled, errors["date"], keyboardType = KeyboardType.Ascii)
            TextButton(enabled = enabled, onClick = { dateDialog = true }) { Text("Choose date") }
        }
        item {
            LedgerTextField(if (draft.kind == "income") "Merchant / payer" else "Merchant / payee", draft.merchant,
                { model.updateDraft(draft.copy(merchant = it.take(120))) }, enabled, errors["merchant"])
        }
        item {
            LedgerChoice("Category", draft.category, LedgerDomain.categories, enabled) { model.updateDraft(draft.copy(category = it)) }
            errors["category"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            LedgerTextField("Notes (optional)", draft.notes, { model.updateDraft(draft.copy(notes = it.take(1000))) }, enabled, errors["notes"], multiline = true)
        }
        if (draft.currency != model.baseCurrency) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Historical currency conversion", style = MaterialTheme.typography.titleSmall)
                    Text("On save, ${draft.currency} is converted to ${model.baseCurrency} using the latest available rate on or before the transaction date. Previously saved rates work offline.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (model.page == LedgerPage.Editor) model.editingRecord?.let { record ->
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saved conversion", style = MaterialTheme.typography.titleSmall)
                        Text("Original: ${formatLedgerMoney(record.amountMinor, record.input.currency)}")
                        Text("Reported: ${formatLedgerMoney(record.fx.convertedMinor, record.fx.baseCurrency)}")
                        Text("1 ${record.input.currency} = ${record.fx.rate} ${record.fx.baseCurrency}\nRate date: ${record.fx.rateDate}\nSource: ${record.fx.source}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.sourceFileName.isNotBlank()) Text("Imported from: ${record.sourceFileName}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                OutlinedButton(enabled = enabled, onClick = { model.askDelete(record) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete transaction") }
            }
        }
    }
    if (dateDialog) {
        val today = LocalDate.now(ZoneOffset.UTC)
        val initial = runCatching { LocalDate.parse(draft.date) }.getOrNull()
            ?.takeIf { it >= LocalDate.of(1999, 1, 4) && it <= today }
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial,
            yearRange = 1999..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return date >= LocalDate.of(1999, 1, 4) && date <= today
                }
            })
        DatePickerDialog(onDismissRequest = { dateDialog = false },
            confirmButton = { TextButton(enabled = state.selectedDateMillis != null, onClick = {
                state.selectedDateMillis?.let { millis -> model.updateDraft(draft.copy(date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())) }
                dateDialog = false
            }) { Text("Use date") } },
            dismissButton = { TextButton(onClick = { dateDialog = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun LedgerTextField(
    label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, error: String?,
    keyboardType: KeyboardType = KeyboardType.Text, helper: String? = null, multiline: Boolean = false,
) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled,
        isError = error != null, supportingText = (error ?: helper)?.let { { Text(it) } },
        singleLine = !multiline, minLines = if (multiline) 3 else 1, maxLines = if (multiline) 6 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            imeAction = if (multiline) ImeAction.Default else ImeAction.Next), modifier = Modifier.fillMaxWidth())
}
