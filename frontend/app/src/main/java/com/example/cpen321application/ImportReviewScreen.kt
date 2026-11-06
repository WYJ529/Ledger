package com.example.cpen321application

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun ImportReviewScreen(model: LedgerViewModel, session: Session? = null) {
    val groups = rememberBillGroups(session)
    val appLock = LocalAppLock.current
    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        appLock.completeExternalActivity()
        if (uri != null) model.documents.chooseDocument(uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        appLock.completeExternalActivity(); model.documents.cameraResult(success)
    }
    var startOver by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("ledger-import"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { LedgerMessages(model) }
        item {
            Text(if (model.importRows.isEmpty()) t("Add a bill") else t("Review before saving."),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(if (model.importRows.isEmpty()) t("Photograph a receipt, choose a file or enter the bill yourself.")
                else t("Select the rows you want, correct the details and resolve any possible duplicates."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (model.importRows.isEmpty()) {
            item { BillTargetChips(model, groups) }
            item {
                ImportSourceCard(model, chooseFile = {
                    appLock.beginExternalActivity()
                    try { chooseFile.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "application/vnd.ms-excel", "text/plain", "application/pdf", "image/jpeg", "image/png", "image/webp")) } catch (_: android.content.ActivityNotFoundException) { appLock.externalLaunchFailed() }
                }, takePhoto = {
                    val uri = model.documents.prepareCamera()
                    if (uri != null) try { appLock.beginExternalActivity(); camera.launch(uri) } catch (_: android.content.ActivityNotFoundException) { appLock.externalLaunchFailed(); model.documents.cameraUnavailable() }
                })
            }
        }
        model.documentName?.let { name ->
            item {
                ImportDocumentSummary(model, name) {
                    if (model.importRows.isNotEmpty()) startOver = true else model.imports.removeDocument()
                }
            }
        }
        if (model.importRows.isEmpty()) {
            if (model.documentName != null && model.documentMime != "text/csv") item {
                DocumentExtractionHelp(model)
            }
            if (model.documentMime == "text/csv") item {
                LedgerChoice("Currency when CSV has no currency column", model.csvCurrency.ifBlank { "Choose currency" },
                    LedgerDomain.currencies, !model.busy, model.imports::changeCsvCurrency)
            }
        } else {
            item { ExtractedRowsHeader(model) }
            itemsIndexed(model.importReviews, key = { _, review -> review.row.rowId }) { index, review ->
                ImportRowCard(index, review, model)
            }
        }
    }
    if (startOver) DiscardReviewDialog(onKeep = { startOver = false }, onDiscard = { startOver = false; model.imports.removeDocument() })
}

// The groups a bill can go to. Without this list it can still go to the group it was opened from.
@Composable
private fun rememberBillGroups(session: Session?): List<ExpenseGroup> {
    var groups by remember { mutableStateOf(emptyList<ExpenseGroup>()) }
    LaunchedEffect(session?.token) {
        session?.token?.let { token ->
            handleRequestFailures({}) { groups = withContext(Dispatchers.IO) { GroupExpenseApi.groups(token) } }
        }
    }
    return groups
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BillTargetChips(model: LedgerViewModel, groups: List<ExpenseGroup>) {
    val chosen = model.bills.target
    val choices = if (chosen == null || groups.any { it.id == chosen.id }) groups else groups + chosen
    if (choices.isEmpty()) return
    Text(t("Who is this bill for?"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = chosen == null, enabled = !model.busy, onClick = { model.bills.choose(null) }, label = { Text(t("Just me")) })
        choices.forEach { group ->
            FilterChip(selected = chosen?.id == group.id, enabled = !model.busy, onClick = { model.bills.choose(group) }, label = { Text(group.name) })
        }
    }
}

@Composable
private fun ExtractedRowsHeader(model: LedgerViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tf("%1\$d extracted transactions", model.importRows.size), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (model.reviewing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun DiscardReviewDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(t("Discard this review?")) },
        text = { Text(t("The reviewed transactions have not been saved. Starting a different import will discard these rows and your edits.")) },
        confirmButton = { BarButton(onClick = onDiscard) { Text(t("Discard review")) } },
        dismissButton = { BarButton(onClick = onKeep) { Text(t("Keep reviewing")) } },
    )
}

@Composable
private fun ImportRowCard(index: Int, review: LedgerRowReview, model: LedgerViewModel) {
    val row = review.row
    val input = row.input
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.selected, enabled = !model.busy, onCheckedChange = { model.imports.selectImportRow(row.rowId, it) },
                    modifier = Modifier.semantics { contentDescription = tf("Include transaction %1\$d, %2\$s", index + 1, input.merchant) })
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. ${input.merchant.ifBlank { t("No merchant") }}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (row.selected) t("Selected for import") else t("Skipped"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BarButton(enabled = !model.busy, onClick = { model.imports.editImportRow(row.rowId) }) { Text(t("Edit")) }
            }
            Text("${input.currency} ${input.amount.ifBlank { "—" }} · ${if (input.kind == "income") t("Income") else t("Expense")}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${input.date.ifBlank { t("Missing date") }} · ${t(input.category)}", style = MaterialTheme.typography.bodyMedium)
            if (input.notes.isNotBlank()) Text(input.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (review.errors.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = PanelShape, border = inkBorder()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t("Needs correction"), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                        review.errors.values.forEach { message -> Text("• ${t(message)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                    }
                }
            }
            row.warnings.distinct().forEach { warning ->
                Text(tf("Review: %1\$s", t(warning)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (review.duplicates.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = PanelShape, border = inkBorder()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (review.duplicates.size == 1) t("Possible duplicate") else t("Possible duplicates"), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        review.duplicates.forEach { duplicate ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${duplicate.merchant} · ${duplicate.currency} ${duplicate.amount}", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Text("${duplicate.date} · ${if (duplicate.withinImport) t("In this import") else t("Already in your ledger")}\n${t(duplicate.reason)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                        Text(t("Uncheck the row to skip it, or acknowledge below if this is a separate transaction."), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = row.acknowledgeDuplicate, enabled = !model.busy && row.selected,
                                onCheckedChange = { model.imports.acknowledgeDuplicate(row.rowId, it) },
                                modifier = Modifier.semantics { contentDescription = tf("Keep transaction %1\$d despite possible duplicates", index + 1) })
                            Text(t("Keep this transaction anyway"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportSourceCard(model: LedgerViewModel, chooseFile: () -> Unit, takePhoto: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(t("Choose your source"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PrimaryBarButton(enabled = !model.busy, onClick = chooseFile, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(t("Choose a file")) }
            BarButton(enabled = !model.busy, onClick = takePhoto, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(t("Take a receipt photo")) }
            BarButton(enabled = !model.busy, onClick = model.bills::enterManually, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(t("Enter manually")) }
            Text(t("CSV and PDF · Up to 20 MB; images · Up to 5 MiB after compression"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DocumentExtractionHelp(model: LedgerViewModel) {
    Panel(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("Before you extract"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (model.documentMime == "application/pdf" && model.bills.target == null) {
                Text(t("Preview statement reads supported US Chase/Amex text PDFs without sending them to OpenAI. It checks detail against statement totals and skips credit-card repayments."))
                Text(t("For receipts, scanned statements or unsupported layouts, you can choose AI extraction below."))
                BarButton(enabled = !model.busy, onClick = model.imports::extractDocumentWithAi) { Text(t("Send to OpenAI & extract")) }
            }
            Text(t("Tapping “Send to OpenAI & extract” sends this document through the Ledger server to OpenAI to identify transactions."))
        }
    }
}

@Composable
private fun ImportDocumentSummary(model: LedgerViewModel, name: String, onRemove: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = PanelShape, border = inkBorder()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text("${if (model.documentMime == "text/csv") "CSV" else if (model.documentMime == "application/pdf") "PDF" else t("Image")} · ${String.format(Locale.US, "%.1f", model.documentSize / 1024.0)} KiB",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BarButton(enabled = !model.busy, onClick = onRemove, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text(if (model.importRows.isEmpty()) t("Remove document") else t("Start a different import")) }
        }
    }
}
