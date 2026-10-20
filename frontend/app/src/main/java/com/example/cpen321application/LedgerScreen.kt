package com.example.cpen321application

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun LedgerScreen(model: LedgerViewModel, onOpenAccount: () -> Unit) {
    BackHandler(model.page != LedgerPage.Dashboard) { model.requestBack() }
    BackHandler(model.page == LedgerPage.Dashboard && model.selectingRecords && !model.busy) { model.cancelSelection() }
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (model.page != LedgerPage.Dashboard) {
                            TextButton(enabled = !model.busy, onClick = model::requestBack) { Text("Back") }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(when (model.page) {
                                LedgerPage.Dashboard -> "Local guest ledger"
                                LedgerPage.Editor -> if (model.editingRecord == null) "New transaction" else "Edit transaction"
                                LedgerPage.Import -> "Import transactions"
                                LedgerPage.ImportEditor -> "Review transaction"
                            }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (model.page == LedgerPage.Dashboard) Text("Device-only · not linked to an account", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (model.page == LedgerPage.Dashboard) {
                            TextButton(enabled = !model.busy, onClick = { model.cancelSelection(); onOpenAccount() }) { Text("Account & groups") }
                        }
                    }
                    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
        },
        bottomBar = { LedgerActions(model) },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (model.page) {
                LedgerPage.Dashboard -> LedgerDashboard(model)
                LedgerPage.Editor, LedgerPage.ImportEditor -> TransactionEditor(model)
                LedgerPage.Import -> ImportReviewScreen(model)
            }
        }
    }
    model.deletingRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { model.askDelete(null) },
            title = { Text("Delete transaction?") },
            text = { Text("${record.input.merchant} · ${formatLedgerMoney(record.amountMinor, record.input.currency)}\n\nThis transaction will be removed from your ledger and totals.") },
            confirmButton = { TextButton(onClick = model::deleteConfirmed) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { model.askDelete(null) }) { Text("Keep transaction") } },
        )
    }
    if (model.deletingRecords.isNotEmpty()) AlertDialog(
        onDismissRequest = model::dismissBatchDelete,
        title = { Text("Delete ${model.deletingRecords.size} transactions?") },
        text = { Text("Only the selected transactions will be removed. This cannot be undone. Other entries and hidden results will remain.") },
        confirmButton = { TextButton(onClick = model::deleteSelectedConfirmed) { Text("Delete selected", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = model::dismissBatchDelete) { Text("Cancel") } },
    )
    if (model.confirmDiscard) {
        AlertDialog(
            onDismissRequest = model::dismissDiscard,
            title = { Text(if (model.page == LedgerPage.Import) "Discard this import?" else "Discard your changes?") },
            text = { Text("Your unsaved changes will be lost.") },
            confirmButton = { TextButton(onClick = model::discardAndBack) { Text("Discard") } },
            dismissButton = { TextButton(onClick = model::dismissDiscard) { Text("Keep editing") } },
        )
    }
    if (model.currencyDialog) {
        var currency by remember { mutableStateOf(model.baseCurrency) }
        AlertDialog(
            onDismissRequest = { model.showCurrencyDialog(false) },
            title = { Text("Reporting currency") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("All transactions will be converted using historical exchange rates for their dates. Original amounts stay the same.")
                    LedgerChoice("Currency", currency, LedgerDomain.currencies, true) { currency = it }
                    Text("Saved exchange rates work offline. New rates need an internet connection. If any rate is unavailable, nothing changes.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { model.changeCurrency(currency) }) { Text("Apply currency") } },
            dismissButton = { TextButton(onClick = { model.showCurrencyDialog(false) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LedgerActions(model: LedgerViewModel) {
    Surface(tonalElevation = 2.dp, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.busy) Text(model.progress, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (model.page) {
                LedgerPage.Dashboard -> if (model.selectingRecords) {
                    Text("${model.selectedRecordIds.size} selected · ${model.visibleRecords.size} shown", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(enabled = !model.busy, onClick = model::cancelSelection, modifier = Modifier.weight(1f)) { Text("Cancel selection") }
                        Button(enabled = !model.busy && model.selectedRecordIds.isNotEmpty(), onClick = model::askDeleteSelected,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.weight(1f)) {
                            Text("Delete (${model.selectedRecordIds.size})")
                        }
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(enabled = !model.busy, onClick = model::openImport, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Import") }
                    Button(enabled = !model.busy, onClick = model::newTransaction, modifier = Modifier.weight(1.5f).heightIn(min = 52.dp)) { Text("+ Add transaction") }
                }
                LedgerPage.Editor, LedgerPage.ImportEditor -> Button(
                    enabled = !model.busy, onClick = model::saveDraft, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(if (model.page == LedgerPage.ImportEditor) "Apply to review" else "Save transaction") }
                LedgerPage.Import -> if (model.importRows.isNotEmpty()) {
                    val selected = model.importReviews.filter { it.row.selected }
                    val unresolved = selected.count { it.errors.isNotEmpty() || (it.duplicates.isNotEmpty() && !it.row.acknowledgeDuplicate) }
                    Text(if (unresolved > 0) "$unresolved selected row${if (unresolved == 1) " needs" else "s need"} attention" else "${selected.size} selected · Saved only when you confirm",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unresolved > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !model.busy && !model.reviewing && selected.isNotEmpty() && unresolved == 0,
                        onClick = model::confirmImport, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("Confirm import (${selected.size})")
                    }
                } else if (model.documentName != null) {
                    Button(enabled = !model.busy, onClick = model::extractDocument, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(when (model.documentMime) { "text/csv" -> "Preview CSV"; "application/pdf" -> "Preview statement locally"; else -> "Send to OpenAI & extract" })
                    }
                } else Text("Choose a document to get started.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LedgerDashboard(model: LedgerViewModel) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxSize().testTag("ledger-dashboard")) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("GUEST LEDGER", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text("Stored on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { LedgerMessages(model) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Overview", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Totals follow the filters below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(enabled = !model.busy, onClick = { model.showCurrencyDialog(true) }) { Text("${model.baseCurrency} ▾") }
            }
        }
        item { MonthSelector(model) }
        item { SummaryCards(model.summary, model.baseCurrency) }
        item {
            OutlinedTextField(value = model.search, onValueChange = model::changeSearch,
                label = { Text("Search merchant, category or notes") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { if (model.search.isNotBlank()) TextButton(onClick = { model.changeSearch("") }) { Text("Clear") } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "expense" to "Expenses", "income" to "Income").forEach { (key, label) ->
                    FilterChip(selected = model.kindFilter == key, onClick = { model.changeKindFilter(key) }, label = { Text(label) })
                }
            }
        }
        val categories = model.summary?.categories.orEmpty().filterValues { it > 0 }.entries.sortedByDescending { it.value }
        if (categories.isNotEmpty()) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Spending by category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val total = model.summary?.expenseMinor?.toDouble()?.coerceAtLeast(1.0) ?: 1.0
                    categories.forEach { (category, amount) ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(category, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(formatLedgerMoney(amount, model.baseCurrency), style = MaterialTheme.typography.labelLarge)
                            }
                            LinearProgressIndicator(progress = { (amount / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp))
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Transactions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${model.visibleRecords.size} / ${model.records.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled = !model.busy, onClick = model::refresh) { Text("Refresh") }
            }
            if (model.records.isNotEmpty()) {
                Text("Showing ${model.visibleRecords.size} of ${model.records.size} saved transactions.", style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (model.selectingRecords) {
                        TextButton(enabled = !model.busy, onClick = model::selectAllVisible) { Text("Select all shown") }
                        TextButton(enabled = !model.busy, onClick = model::clearSelected) { Text("Clear selection") }
                    } else if (model.visibleRecords.isNotEmpty()) {
                        TextButton(enabled = !model.busy, onClick = model::startSelection) { Text("Select transactions") }
                    }
                    if (model.visibleRecords.isNotEmpty() && model.visibleRecords.size < model.records.size) {
                        TextButton(enabled = !model.busy, onClick = { model.changeMonth(null); model.changeKindFilter("all"); model.changeSearch("") }) { Text("Show all") }
                    }
                }
            }
        }
        if (model.loading) item {
            Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (model.visibleRecords.isEmpty()) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (model.records.isEmpty()) "A clear view starts here." else "No matching transactions", style = MaterialTheme.typography.titleLarge)
                    Text(if (model.records.isEmpty()) "Add your first income or expense, or import a statement. Your records stay on this device."
                        else "Try another month, transaction type or search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (model.records.isNotEmpty()) TextButton(onClick = { model.changeMonth(null); model.changeKindFilter("all"); model.changeSearch("") }) { Text("Show all transactions") }
                }
            }
        }
        items(model.visibleRecords, key = { it.id }) { record ->
            TransactionCard(record, model.busy, model.selectingRecords, record.id in model.selectedRecordIds) {
                if (model.selectingRecords) model.toggleSelected(record.id) else model.editTransaction(record)
            }
        }
        item {
            Text("Guest records are separate from future account and group ledgers. Uninstalling the app or clearing its data removes this ledger.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MonthSelector(model: LedgerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { model.shiftMonth(-1) }) { Text("‹ Prev") }
            Text(model.month?.let { YearMonth.parse(it).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)) } ?: "All time",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { model.shiftMonth(1) }) { Text("Next ›") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = model.month == YearMonth.now(ZoneOffset.UTC).toString(), onClick = { model.changeMonth(YearMonth.now(ZoneOffset.UTC).toString()) }, label = { Text("This month") })
            FilterChip(selected = model.month == null, onClick = { model.changeMonth(null) }, label = { Text("All time") })
        }
    }
}

@Composable
private fun SummaryCards(summary: LedgerSummary?, currency: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NET MOVEMENT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(formatLedgerMoney(summary?.netMinor ?: 0, currency), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold, maxLines = 2)
                Text("Income minus expenses · $currency", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryMetric("Income", summary?.incomeMinor ?: 0, currency, Modifier.weight(1f), true)
            SummaryMetric("Expenses", summary?.expenseMinor ?: 0, currency, Modifier.weight(1f), false)
        }
    }
}

@Composable
private fun SummaryMetric(label: String, amount: Long, currency: String, modifier: Modifier, income: Boolean) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((if (income) "↙  " else "↗  ") + label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatLedgerMoney(amount, currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
        }
    }
}

@Composable
private fun TransactionCard(record: LedgerRecord, busy: Boolean, selecting: Boolean, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                if (selecting) Checkbox(checked = selected, enabled = !busy, onCheckedChange = { onClick() },
                    modifier = Modifier.semantics { contentDescription = "Select transaction ${record.id}" })
                Surface(color = if (record.input.kind == "income") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)) {
                    Text(if (record.input.kind == "income") "IN" else "OUT", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(record.input.merchant, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${record.input.category} · ${record.input.date}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (record.source == "manual") "Manual entry" else if (record.source == "csv") "CSV import" else "Document import",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((if (record.input.kind == "income") "+ " else "− ") + formatLedgerMoney(record.fx.convertedMinor, record.fx.baseCurrency),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (record.input.currency != record.fx.baseCurrency) {
                Text("Original ${formatLedgerMoney(record.amountMinor, record.input.currency)} · FX ${record.fx.rateDate}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun LedgerMessages(model: LedgerViewModel) {
    val message = model.error ?: model.notice ?: return
    val isError = model.error != null
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = model::dismissMessage, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Dismiss") }
        }
    }
}

@Composable
internal fun LedgerChoice(label: String, value: String, choices: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
            Text("▾")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { expanded = false; onChange(choice) }) }
        }
    }
}

internal fun formatLedgerMoney(amount: Long, currency: String): String = "$currency ${LedgerDomain.formatMinor(amount, currency)}"
