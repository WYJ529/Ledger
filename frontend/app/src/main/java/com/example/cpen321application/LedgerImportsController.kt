package com.example.cpen321application

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal class LedgerImportsController(private val model: LedgerViewModel) {
    private var importRequestId = UUID.randomUUID().toString()
    private var reviewJob: Job? = null
    private var reviewGeneration = 0

    fun changeCsvCurrency(currency: String) {
        with(model) {
            if (!busy && currency in LedgerDomain.currencies) csvCurrency = currency
        }
    }

    fun extractDocument() = with(model) {
        extractDocumentUsing(localOnly = documentMime == "application/pdf")
    }

    fun extractDocumentWithAi() = with(model) {
        extractDocumentUsing(localOnly = false)
    }

    private fun extractDocumentUsing(localOnly: Boolean) {
        with(model) {
            val bytes = documents.pendingDocument ?: return
            val name = documentName ?: return
            val mime = documentMime
            runOperation(if (mime == "text/csv") "Preparing CSV review…" else "Extracting transactions… This may take a moment.") {
                val rows = withContext(Dispatchers.IO) {
                    if (mime == "text/csv") repository.previewCsv(bytes.toString(Charsets.UTF_8), csvCurrency.ifBlank { null })
                    else repository.previewDocument(name, mime, bytes, localOnly)
                }
                require(rows.isNotEmpty()) { "No transactions were found. Try another document or enter a transaction manually." }
                importRows = rows
                importRequestId = UUID.randomUUID().toString()
                documents.discardBytes()
                rebuildReviews()
                notice = "Review every amount and date before importing. Nothing has been saved yet."
            }
        }
    }

    fun removeDocument() {
        with(model) {
            if (!busy) { clearImport(); error = null; notice = null }
        }
    }

    fun clearImport() {
        with(model) {
            reviewGeneration++
            reviewJob?.cancel()
            documents.discardBytes()
            documentName = null
            documentMime = ""
            documentSize = 0
            csvCurrency = ""
            importRows = emptyList()
            importReviews = emptyList()
            editingImportId = null
            reviewing = false
            importRequestId = UUID.randomUUID().toString()
        }
    }

    fun editImportRow(rowId: String) {
        with(model) {
            if (busy) return
            val row = importRows.find { it.rowId == rowId } ?: return
            editingImportId = rowId
            startEditor(row.input, LedgerPage.ImportEditor)
        }
    }

    fun selectImportRow(rowId: String, selected: Boolean) {
        with(model) {
            if (busy) return
            importRows = importRows.map { if (it.rowId == rowId) it.copy(selected = selected, acknowledgeDuplicate = false) else it }
            importRequestId = UUID.randomUUID().toString()
            requestReview()
        }
    }

    fun acknowledgeDuplicate(rowId: String, acknowledged: Boolean) {
        with(model) {
            if (busy) return
            importRows = importRows.map { if (it.rowId == rowId) it.copy(acknowledgeDuplicate = acknowledged) else it }
            importRequestId = UUID.randomUUID().toString()
            requestReview()
        }
    }

    private fun requestReview() {
        with(model) {
            val generation = ++reviewGeneration
            reviewJob?.cancel()
            reviewing = true
            reviewJob = viewModelScope.launch {
                try { rebuildReviews() }
                catch (e: LedgerException) { error = e.message ?: "Could not check these rows." }
                finally { if (reviewGeneration == generation) reviewing = false }
            }
        }
    }

    suspend fun rebuildReviews() {
        with(model) {
            val rows = importRows
            val existing = records
            val reviewed = withContext(Dispatchers.IO) { repository.review(rows, existing) }
            if (rows === importRows && existing === records) importReviews = reviewed
        }
    }

    fun confirmImport() {
        with(model) {
            if (busy || reviewing) return
            val selected = importReviews.filter { it.row.selected }
            if (selected.isEmpty()) { error = "Select at least one transaction to import."; return }
            if (selected.any { it.errors.isNotEmpty() || (it.duplicates.isNotEmpty() && !it.row.acknowledgeDuplicate) }) {
                error = "Fix selected rows and resolve possible duplicates before importing."
                return
            }
            val rows = importRows
            val source = if (documentMime == "text/csv") "csv" else "receipt"
            val name = documentName.orEmpty()
            val requestId = importRequestId
            runOperation("Importing selected transactions…") {
                val count = withContext(Dispatchers.IO) { repository.commitImport(rows, source, name, requestId) }
                // The batch is durable now. A refresh-only failure must never leave an import ready to submit again.
                clearImport()
                page = LedgerPage.Dashboard
                month = null
                kindFilter = "all"
                search = ""
                notice = "$count transaction${if (count == 1) "" else "s"} imported."
                reload()
            }
        }
    }

    fun applyDraft(id: String, input: LedgerInput) {
        with(model) {
            importRows = importRows.map { row ->
                if (row.rowId == id) row.copy(input = input, acknowledgeDuplicate = false)
                else row.copy(acknowledgeDuplicate = false)
            }
            importRequestId = UUID.randomUUID().toString()
            page = LedgerPage.Import
            editingImportId = null
            error = null
            requestReview()
        }
    }
}
