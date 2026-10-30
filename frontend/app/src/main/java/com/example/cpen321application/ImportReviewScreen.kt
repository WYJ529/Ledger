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
import java.util.Locale

@Composable
internal fun ImportReviewScreen(model: LedgerViewModel) {
    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.documents.chooseDocument(uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), model.documents::cameraResult)
    var startOver by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("ledger-import"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { LedgerMessages(model) }
        item {
            Text(if (model.importRows.isEmpty()) "Less typing. More clarity." else "Review before saving.",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(if (model.importRows.isEmpty()) "Bring in a bank statement or receipt, then check the transactions before adding them to your ledger."
                else "Select the rows you want, correct the details and resolve any possible duplicates.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (model.importRows.isEmpty()) {
            item {
                ImportSourceCard(model, chooseFile = {
                    chooseFile.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "application/vnd.ms-excel", "text/plain", "application/pdf", "image/jpeg", "image/png", "image/webp"))
                }, takePhoto = {
                    val uri = model.documents.prepareCamera()
                    if (uri != null) try { camera.launch(uri) } catch (_: android.content.ActivityNotFoundException) { model.documents.cameraUnavailable() }
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
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${model.importRows.size} extracted transactions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (model.reviewing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            itemsIndexed(model.importReviews, key = { _, review -> review.row.rowId }) { index, review ->
                ImportRowCard(index, review, model)
            }
        }
    }
    if (startOver) AlertDialog(
        onDismissRequest = { startOver = false },
        title = { Text("Discard this review?") },
        text = { Text("The reviewed transactions have not been saved. Starting a different import will discard these rows and your edits.") },
        confirmButton = { BarButton(onClick = { startOver = false; model.imports.removeDocument() }) { Text("Discard review") } },
        dismissButton = { BarButton(onClick = { startOver = false }) { Text("Keep reviewing") } },
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
                    modifier = Modifier.semantics { contentDescription = "Include transaction ${index + 1}, ${input.merchant}" })
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. ${input.merchant.ifBlank { "Missing merchant" }}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (row.selected) "Selected for import" else "Skipped", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BarButton(enabled = !model.busy, onClick = { model.imports.editImportRow(row.rowId) }) { Text("Edit") }
            }
            Text("${input.currency} ${input.amount.ifBlank { "—" }} · ${if (input.kind == "income") "Income" else "Expense"}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${input.date.ifBlank { "Missing date" }} · ${input.category}", style = MaterialTheme.typography.bodyMedium)
            if (input.notes.isNotBlank()) Text(input.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (review.errors.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = PanelShape, border = inkBorder()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Needs correction", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                        review.errors.values.forEach { message -> Text("• $message", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                    }
                }
            }
            row.warnings.distinct().forEach { warning ->
                Text("Review: $warning", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (review.duplicates.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = PanelShape, border = inkBorder()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Possible duplicate${if (review.duplicates.size == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        review.duplicates.forEach { duplicate ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${duplicate.merchant} · ${duplicate.currency} ${duplicate.amount}", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Text("${duplicate.date} · ${if (duplicate.withinImport) "In this import" else "Already in your ledger"}\n${duplicate.reason}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                        Text("Uncheck the row to skip it, or acknowledge below if this is a separate transaction.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = row.acknowledgeDuplicate, enabled = !model.busy && row.selected,
                                onCheckedChange = { model.imports.acknowledgeDuplicate(row.rowId, it) },
                                modifier = Modifier.semantics { contentDescription = "Keep transaction ${index + 1} despite possible duplicates" })
                            Text("Keep this transaction anyway", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
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
            Text("Choose your source", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PrimaryBarButton(enabled = !model.busy, onClick = chooseFile, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Choose a file") }
            BarButton(enabled = !model.busy, onClick = takePhoto, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Take a receipt photo") }
            Text("CSV, PDF, JPEG, PNG or WebP · Up to 5 MiB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DocumentExtractionHelp(model: LedgerViewModel) {
    Panel(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Before you extract", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (model.documentMime == "application/pdf") {
                Text("Preview statement reads supported US Chase/Amex text PDFs without sending them to OpenAI. It checks detail against statement totals and skips credit-card repayments.")
                Text("For receipts, scanned statements or unsupported layouts, you can choose AI extraction below.")
                BarButton(enabled = !model.busy, onClick = model.imports::extractDocumentWithAi) { Text("Send to OpenAI & extract") }
            }
            Text("Tapping “Send to OpenAI & extract” sends this document through the Ledger server to OpenAI to identify transactions. Only send documents you are comfortable sharing with that service.")
            Text("Ledger does not save the original document. Extracted rows stay in review until you confirm. AI can miss or misread details; check every amount, date and currency.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ImportDocumentSummary(model: LedgerViewModel, name: String, onRemove: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = PanelShape, border = inkBorder()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text("${if (model.documentMime == "text/csv") "CSV" else if (model.documentMime == "application/pdf") "PDF" else "Image"} · ${String.format(Locale.US, "%.1f", model.documentSize / 1024.0)} KiB",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BarButton(enabled = !model.busy, onClick = onRemove, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(if (model.importRows.isEmpty()) "Remove document" else "Start a different import") }
        }
    }
}
