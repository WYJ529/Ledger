package com.example.cpen321application

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

internal enum class LedgerPage { Dashboard, Editor, Import, ImportEditor }

/** Activity-scoped state: drafts and in-flight imports survive rotation, without putting documents in a Bundle. */
internal class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    internal val repository = LedgerRepository(application)
    var page by mutableStateOf(LedgerPage.Dashboard)
        internal set
    var records by mutableStateOf(emptyList<LedgerRecord>())
        internal set
    var visibleRecords by mutableStateOf(emptyList<LedgerRecord>())
        internal set
    var summary by mutableStateOf<LedgerSummary?>(null)
        internal set
    var baseCurrency by mutableStateOf("CAD")
        internal set
    // A fresh process must not hide historical imports behind an implicit current-month filter.
    var month by mutableStateOf<String?>(null)
        internal set
    var kindFilter by mutableStateOf("all")
        internal set
    var search by mutableStateOf("")
        internal set
    var busy by mutableStateOf(false)
        internal set
    var loading by mutableStateOf(true)
        internal set
    var progress by mutableStateOf("")
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    var notice by mutableStateOf<String?>(null)
        internal set
    var draft by mutableStateOf(LedgerInput())
        internal set
    var editorAttempted by mutableStateOf(false)
        internal set
    var editingRecord by mutableStateOf<LedgerRecord?>(null)
        internal set
    var deletingRecord by mutableStateOf<LedgerRecord?>(null)
        internal set
    var selectingRecords by mutableStateOf(false)
        internal set
    var selectedRecordIds by mutableStateOf(emptySet<String>())
        internal set
    var deletingRecords by mutableStateOf(emptyList<LedgerRecord>())
        internal set
    var confirmDiscard by mutableStateOf(false)
        internal set
    var documentName by mutableStateOf<String?>(null)
        internal set
    var documentMime by mutableStateOf("")
        internal set
    var documentSize by mutableStateOf(0)
        internal set
    var csvCurrency by mutableStateOf("")
        internal set
    var importRows by mutableStateOf(emptyList<LedgerImportRow>())
        internal set
    var importReviews by mutableStateOf(emptyList<LedgerRowReview>())
        internal set
    var reviewing by mutableStateOf(false)
        internal set
    var editingImportId by mutableStateOf<String?>(null)
        internal set
    var currencyDialog by mutableStateOf(false)
        internal set
    private var originalDraft = draft
    private var saveRequestId = UUID.randomUUID().toString()

    val filters = LedgerFiltersController(this)
    val selection = LedgerSelectionController(this)
    val documents = LedgerDocumentsController(this)
    val imports = LedgerImportsController(this)

    init {
        // Only this feature owns this directory. A new process removes abandoned camera captures.
        File(application.cacheDir, "ledger-captures").listFiles()?.forEach { it.delete() }
        refresh()
    }

    fun refresh() = runOperation("Loading your ledger…") {
        reload()
        loading = false
    }

    internal suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) { repository.load() to repository.baseCurrency() }
        records = loaded.first
        selectedRecordIds = selectedRecordIds.intersect(records.map { it.id }.toSet())
        baseCurrency = loaded.second
        filters.rebuildVisible()
        if (importRows.isNotEmpty()) imports.rebuildReviews()
    }

    internal fun runOperation(label: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        progress = label
        error = null
        notice = null
        viewModelScope.launch {
            try { action() }
            catch (e: LedgerException) { error = e.message }
            catch (e: java.io.IOException) { error = e.message ?: "Could not read the selected document." }
            catch (e: SecurityException) { error = e.message ?: "Permission to read this document was denied." }
            catch (e: IllegalArgumentException) { error = e.message ?: "Check the selected document or transaction details." }
            finally { busy = false; loading = false; progress = "" }
        }
    }

    fun dismissMessage() { error = null; notice = null }

    fun newTransaction() {
        selection.cancelSelection()
        if (busy) return
        editingRecord = null
        startEditor(LedgerInput(currency = baseCurrency, date = LocalDate.now(ZoneOffset.UTC).toString()), LedgerPage.Editor)
    }

    fun editTransaction(record: LedgerRecord) {
        if (busy) return
        editingRecord = record
        startEditor(record.input, LedgerPage.Editor)
    }

    internal fun startEditor(input: LedgerInput, target: LedgerPage) {
        draft = input
        originalDraft = input
        editorAttempted = false
        saveRequestId = UUID.randomUUID().toString()
        error = null
        notice = null
        page = target
    }

    fun updateDraft(input: LedgerInput) {
        if (busy) return
        draft = input
        saveRequestId = UUID.randomUUID().toString()
    }

    fun saveDraft() {
        editorAttempted = true
        if (LedgerDomain.validate(draft).isNotEmpty()) {
            error = "Check the highlighted fields before saving."
            return
        }
        if (page == LedgerPage.ImportEditor) {
            val id = editingImportId ?: return
            imports.applyDraft(id, draft)
            return
        }
        val input = draft
        val existing = editingRecord
        val requestId = saveRequestId
        runOperation("Saving transaction…") {
            val saved = withContext(Dispatchers.IO) { repository.save(input, existing?.id, existing?.revision, requestId) }
            records = records.filter { it.id != saved.id } + saved
            month = input.date.take(7)
            kindFilter = "all"
            search = ""
            // A successful write must leave the editor even if the subsequent refresh fails.
            page = LedgerPage.Dashboard
            editingRecord = null
            notice = if (existing == null) "Transaction added." else "Transaction updated."
            filters.rebuildVisible()
            reload()
        }
    }

    fun askDelete(record: LedgerRecord?) { if (!busy) deletingRecord = record }

    fun deleteConfirmed() {
        val record = deletingRecord ?: return
        deletingRecord = null
        runOperation("Deleting transaction…") {
            withContext(Dispatchers.IO) { repository.delete(record.id, record.revision) }
            records = records.filter { it.id != record.id }
            if (page == LedgerPage.Editor) page = LedgerPage.Dashboard
            notice = "Transaction deleted."
            filters.rebuildVisible()
            reload()
        }
    }

    fun showCurrencyDialog(show: Boolean) { if (!busy) currencyDialog = show }
    fun changeCurrency(currency: String) {
        currencyDialog = false
        if (currency == baseCurrency) return
        runOperation("Updating reporting currency…") {
            withContext(Dispatchers.IO) { repository.setBaseCurrency(currency) }
            reload()
            notice = "Your totals now use $currency. Original amounts are preserved."
        }
    }

    fun openImport() {
        if (busy) return
        selection.cancelSelection()
        page = LedgerPage.Import
        error = null
        notice = null
    }

    fun requestBack() {
        if (busy) return
        when (page) {
            LedgerPage.Editor, LedgerPage.ImportEditor -> if (draft != originalDraft) confirmDiscard = true else leaveEditor()
            LedgerPage.Import -> if (importRows.isNotEmpty() || documentName != null) confirmDiscard = true else page = LedgerPage.Dashboard
            LedgerPage.Dashboard -> Unit
        }
    }

    private fun leaveEditor() {
        page = if (page == LedgerPage.ImportEditor) LedgerPage.Import else LedgerPage.Dashboard
        editingImportId = null
        error = null
    }

    fun dismissDiscard() { confirmDiscard = false }
    fun discardAndBack() {
        confirmDiscard = false
        if (page == LedgerPage.Import) { imports.clearImport(); page = LedgerPage.Dashboard }
        else leaveEditor()
        error = null
    }

    override fun onCleared() {
        documents.close()
        // A request may still be finishing on IO; close under the repository lock without blocking the main thread.
        CoroutineScope(Dispatchers.IO).launch { repository.close() }
        super.onCleared()
    }

    companion object { const val MAX_IMPORT_BYTES = 5 * 1024 * 1024 }
}
