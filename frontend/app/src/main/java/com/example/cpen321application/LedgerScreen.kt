package com.example.cpen321application

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@Composable
internal fun LedgerScreen(model: LedgerViewModel, session: Session? = null, onSessionExpired: () -> Unit = {},
                          onBillRoute: (BillRoute) -> Unit = {}) {
    var sharing by remember { mutableStateOf<List<LedgerRecord>>(emptyList()) }
    LaunchedEffect(model.bills.route) { model.bills.takeRoute()?.let(onBillRoute) }
    BackHandler(model.page != LedgerPage.Dashboard) { model.requestBack() }
    BackHandler(model.page == LedgerPage.Dashboard && model.selectingRecords && !model.busy) { model.selection.cancelSelection() }
    Scaffold(
        topBar = {
            LedgerTopBar(model)
        },
        bottomBar = { LedgerActions(model, if (session == null) null else {
            { sharing = model.records.filter { it.id in model.selectedRecordIds } }
        }) },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (model.page) {
                LedgerPage.Dashboard -> LedgerDashboard(model, if (session == null) null else { record -> sharing = listOf(record) })
                LedgerPage.Editor, LedgerPage.ImportEditor -> TransactionEditor(model)
                LedgerPage.Import -> ImportReviewScreen(model, session)
            }
        }
    }
    LedgerDialogs(model)
    if (sharing.isNotEmpty() && session != null) key(session.token) {
        ShareEntriesDialog(session, sharing, onSessionExpired) { sharing = emptyList() }
    }
}

@Composable
private fun LedgerActions(model: LedgerViewModel, onShare: (() -> Unit)? = null) {
    Surface(tonalElevation = 2.dp, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.busy) Text(t(model.progress), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (model.page) {
                LedgerPage.Dashboard -> if (model.selectingRecords) {
                    Text(tf("%1\$d selected · %2\$d shown", model.selectedRecordIds.size, model.visibleRecords.size), style = MaterialTheme.typography.bodySmall)
                    if (onShare != null) BarButton(enabled = !model.busy && model.selectedRecordIds.isNotEmpty(),
                        onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text(t("Share to groups")) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BarButton(enabled = !model.busy, onClick = model.selection::cancelSelection, modifier = Modifier.weight(1f)) { Text(t("Cancel selection")) }
                        PrimaryBarButton(enabled = !model.busy && model.selectedRecordIds.isNotEmpty(), onClick = model.selection::askDeleteSelected,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.weight(1f)) {
                            Text(tf("Delete (%1\$d)", model.selectedRecordIds.size))
                        }
                    }
                } else PrimaryBarButton(enabled = !model.busy, onClick = { model.bills.open() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(t("+ Add a bill"))
                }
                LedgerPage.Editor, LedgerPage.ImportEditor -> PrimaryBarButton(
                    enabled = !model.busy, onClick = model::saveDraft, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(if (model.page == LedgerPage.ImportEditor) t("Apply to review") else t("Save transaction")) }
                LedgerPage.Import -> if (model.importRows.isNotEmpty()) {
                    val selected = model.importReviews.filter { it.row.selected }
                    val unresolved = selected.count { it.errors.isNotEmpty() || (it.duplicates.isNotEmpty() && !it.row.acknowledgeDuplicate) }
                    Text(if (unresolved > 0) (if (unresolved == 1) t("1 selected row needs attention") else tf("%1\$d selected rows need attention", unresolved)) else tf("%1\$d selected · Saved only when you confirm", selected.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unresolved > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryBarButton(enabled = !model.busy && !model.reviewing && selected.isNotEmpty() && unresolved == 0,
                        onClick = model.imports::confirmImport, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(tf("Confirm import (%1\$d)", selected.size))
                    }
                } else if (model.documentName != null) {
                    PrimaryBarButton(enabled = !model.busy, onClick = model.imports::extractDocument, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(when {
                            model.documentMime == "text/csv" -> t("Preview CSV")
                            model.documentMime == "application/pdf" && model.bills.target == null -> t("Preview statement")
                            else -> t("Send to OpenAI & extract")
                        })
                    }
                } else Text(t("Choose a document to get started."), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LedgerDashboard(model: LedgerViewModel, onShare: ((LedgerRecord) -> Unit)? = null) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxSize().testTag("ledger-dashboard")) {
        item { LedgerMessages(model) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Overview"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                CurrencyMenu(model.baseCurrency, !model.busy, model::changeCurrency)
            }
        }
        item { MonthSelector(model) }
        item { SummaryCards(model.summary, model.baseCurrency) }
        item {
            OutlinedTextField(value = model.search, onValueChange = model.filters::changeSearch,
                label = { Text(t("Search merchant, category or notes")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { if (model.search.isNotBlank()) BarButton(onClick = { model.filters.changeSearch("") }) { Text(t("Clear")) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "expense" to "Expenses", "income" to "Income").forEach { (key, label) ->
                    FilterChip(selected = model.kindFilter == key, onClick = { model.filters.changeKindFilter(key) }, label = { Text(t(label)) })
                }
            }
        }
        val categories = model.summary?.categories.orEmpty().filterValues { it > 0 }.entries.sortedByDescending { it.value }
        if (categories.isNotEmpty()) item {
            SpendingCategories(model, categories)
        }
        item {
            TransactionListHeader(model)
        }
        if (model.loading) item {
            Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (model.visibleRecords.isEmpty()) item {
            EmptyLedgerHelp(model)
        }
        items(model.visibleRecords, key = { it.id }) { record ->
            TransactionCard(record, model.busy, model.selectingRecords, record.id in model.selectedRecordIds,
                onShare = if (onShare != null && record.input.kind == "expense") ({ onShare(record) }) else null) {
                if (model.selectingRecords) model.selection.toggleSelected(record.id) else model.editTransaction(record)
            }
        }
    }
}

@Composable
private fun MonthSelector(model: LedgerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BarIconButton(onClick = { model.filters.shiftMonth(-1) }) {
                    LedgerIcon(R.drawable.ic_chevron_left, t("Previous month"))
                }
            }
            Text(model.month?.let { YearMonth.parse(it).format(DateTimeFormatter.ofPattern("LLLL yyyy", L10n.locale)) } ?: t("All time"),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(3f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                BarIconButton(onClick = { model.filters.shiftMonth(1) }) {
                    LedgerIcon(R.drawable.ic_chevron_right, t("Next month"))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = model.month == YearMonth.now(ZoneOffset.UTC).toString(), onClick = { model.filters.changeMonth(YearMonth.now(ZoneOffset.UTC).toString()) }, label = { Text(t("This month")) })
            FilterChip(selected = model.month == null, onClick = { model.filters.changeMonth(null) }, label = { Text(t("All time")) })
        }
    }
}

@Composable
private fun SummaryCards(summary: LedgerSummary?, currency: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("NET MOVEMENT"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(Money.display(summary?.netMinor ?: 0, currency), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold, maxLines = 2)
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
    Panel(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LedgerIcon(if (income) R.drawable.ic_arrow_income else R.drawable.ic_arrow_expense)
                Text(t(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(Money.display(amount, currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
        }
    }
}

@Composable
private fun TransactionCard(record: LedgerRecord, busy: Boolean, selecting: Boolean, selected: Boolean,
                            onShare: (() -> Unit)? = null, onClick: () -> Unit) {
    Panel(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                if (selecting) Checkbox(checked = selected, enabled = !busy, onCheckedChange = { onClick() },
                    modifier = Modifier.semantics { contentDescription = tf("Select transaction %1\$s", record.id) })
                Surface(color = if (record.input.kind == "income") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = PanelShape, border = inkBorder()) {
                    Text(if (record.input.kind == "income") t("IN") else t("OUT"), Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(record.input.merchant, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${t(record.input.category)} · ${record.input.date}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(when (record.source) { "manual" -> t("Manual entry"); "csv" -> t("CSV import"); "group" -> t("Group import"); else -> t("Document import") },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((if (record.input.kind == "income") "+ " else "− ") + Money.display(record.fx.convertedMinor, record.fx.baseCurrency),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (record.input.currency != record.fx.baseCurrency) {
                Text(tf("Original %1\$s · FX %2\$s", Money.display(record.amountMinor, record.input.currency), record.fx.rateDate),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onShare != null && !selecting) BarButton(enabled = !busy, onClick = onShare) { Text(t("Share to groups")) }
        }
    }
}

@Composable
internal fun LedgerMessages(model: LedgerViewModel) {
    val message = model.error ?: model.notice ?: return
    val isError = model.error != null
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = PanelShape, border = inkBorder(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t(message), color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyMedium)
            BarButton(onClick = model::dismissMessage, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(t("Dismiss")) }
        }
    }
}

@Composable
internal fun LedgerChoice(label: String, value: String, choices: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        BarButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(t(label), style = MaterialTheme.typography.labelSmall)
                Text(t(value), style = MaterialTheme.typography.bodyLarge)
            }
            LedgerIcon(R.drawable.ic_arrow_drop_down)
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(t(choice)) }, onClick = { expanded = false; onChange(choice) }) }
        }
    }
}


@Composable
private fun LedgerTopBar(model: LedgerViewModel) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (model.page != LedgerPage.Dashboard) {
                    BackBar("Back", onClick = model::requestBack, enabled = !model.busy)
                }
                Column(Modifier.weight(1f)) {
                    Text(when (model.page) {
                        LedgerPage.Dashboard -> t("My Expenses")
                        LedgerPage.Editor -> if (model.editingRecord == null) t("New transaction") else t("Edit transaction")
                        LedgerPage.Import -> t("Add a bill")
                        LedgerPage.ImportEditor -> t("Review transaction")
                    }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
    }
}

@Composable
private fun LedgerDialogs(model: LedgerViewModel) {
    if (model.validationDialog) AlertDialog(onDismissRequest = { model.validationDialog = false },
        title = { Text(t("Check transaction")) },
        text = { Text(LedgerDomain.validate(model.draft).values.joinToString("\n") { t(it) }) },
        confirmButton = { BarButton(onClick = { model.validationDialog = false }) { Text(t("OK")) } })
    model.deletingRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { model.askDelete(null) },
            title = { Text(t("Delete transaction?")) },
            text = { Text("${record.input.merchant} · ${Money.display(record.amountMinor, record.input.currency)}\n\n" + t("This transaction will be removed from your ledger and totals.")) },
            confirmButton = { BarButton(onClick = model::deleteConfirmed) { Text(t("Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { BarButton(onClick = { model.askDelete(null) }) { Text(t("Keep transaction")) } },
        )
    }
    if (model.deletingRecords.isNotEmpty()) AlertDialog(
        onDismissRequest = model.selection::dismissBatchDelete,
        title = { Text(tf("Delete %1\$d transactions?", model.deletingRecords.size)) },
        text = { Text(t("Only the selected transactions will be removed. This cannot be undone. Other entries and hidden results will remain.")) },
        confirmButton = { BarButton(onClick = model.selection::deleteSelectedConfirmed) { Text(t("Delete selected"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { BarButton(onClick = model.selection::dismissBatchDelete) { Text(t("Cancel")) } },
    )
    if (model.confirmDiscard) {
        AlertDialog(
            onDismissRequest = model::dismissDiscard,
            title = { Text(if (model.page == LedgerPage.Import) t("Discard this import?") else t("Discard your changes?")) },
            text = { Text(t("Your unsaved changes will be lost.")) },
            confirmButton = { BarButton(onClick = model::discardAndBack) { Text(t("Discard")) } },
            dismissButton = { BarButton(onClick = model::dismissDiscard) { Text(t("Keep editing")) } },
        )
    }

}

@Composable
private fun SpendingCategories(model: LedgerViewModel, categories: List<Map.Entry<String, Long>>) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(t("Spending by category"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val total = model.summary?.expenseMinor?.toDouble()?.coerceAtLeast(1.0) ?: 1.0
            categories.forEach { (category, amount) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t(category), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(Money.display(amount, model.baseCurrency), style = MaterialTheme.typography.labelLarge)
                    }
                    LinearProgressIndicator(progress = { (amount / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun TransactionListHeader(model: LedgerViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t("Transactions"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("${model.visibleRecords.size} / ${model.records.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (model.records.isNotEmpty()) {
        Text(tf("Showing %1\$d of %2\$d saved transactions.", model.visibleRecords.size, model.records.size), style = MaterialTheme.typography.bodySmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (model.selectingRecords) {
                BarButton(enabled = !model.busy, onClick = model.selection::selectAllVisible) { Text(t("Select all shown")) }
                BarButton(enabled = !model.busy, onClick = model.selection::clearSelected) { Text(t("Clear selection")) }
            } else if (model.visibleRecords.isNotEmpty()) {
                BarButton(enabled = !model.busy, onClick = model.selection::startSelection) { Text(t("Select transactions")) }
            }
            if (model.visibleRecords.isNotEmpty() && model.visibleRecords.size < model.records.size) {
                BarButton(enabled = !model.busy, onClick = { model.filters.changeMonth(null); model.filters.changeKindFilter("all"); model.filters.changeSearch("") }) { Text(t("Show all")) }
            }
        }
    }
}

@Composable
private fun EmptyLedgerHelp(model: LedgerViewModel) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (model.records.isEmpty()) t("A clear view starts here.") else t("No matching transactions"), style = MaterialTheme.typography.titleLarge)
            Text(if (model.records.isEmpty()) t("Add your first record.")
                else t("Try another month, transaction type or search."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.records.isNotEmpty()) BarButton(onClick = { model.filters.changeMonth(null); model.filters.changeKindFilter("all"); model.filters.changeSearch("") }) { Text(t("Show all transactions")) }
        }
    }
}

@Composable
internal fun CurrencyMenu(currency: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        BarButton(enabled = enabled, onClick = { expanded = true }) {
            Text(currency)
            LedgerIcon(R.drawable.ic_arrow_drop_down)
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            LedgerDomain.currencies.forEach { choice ->
                DropdownMenuItem(text = { Text(choice) },
                    trailingIcon = { if (choice == currency) Text("✓") },
                    onClick = { expanded = false; if (choice != currency) onSelect(choice) })
            }
        }
    }
}
