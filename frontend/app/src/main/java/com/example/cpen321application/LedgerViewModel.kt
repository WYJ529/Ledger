package com.example.cpen321application

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

internal enum class LedgerPage { Dashboard, Editor, Import, ImportEditor }

/** Activity-scoped state: drafts and in-flight imports survive rotation, without putting documents in a Bundle. */
internal class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LedgerRepository(application)
    var page by mutableStateOf(LedgerPage.Dashboard)
        private set
    var records by mutableStateOf(emptyList<LedgerRecord>())
        private set
    var visibleRecords by mutableStateOf(emptyList<LedgerRecord>())
        private set
    var summary by mutableStateOf<LedgerSummary?>(null)
        private set
    var baseCurrency by mutableStateOf("CAD")
        private set
    // A fresh process must not hide historical imports behind an implicit current-month filter.
    var month by mutableStateOf<String?>(null)
        private set
    var kindFilter by mutableStateOf("all")
        private set
    var search by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    var progress by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf(LedgerInput())
        private set
    var editorAttempted by mutableStateOf(false)
        private set
    var editingRecord by mutableStateOf<LedgerRecord?>(null)
        private set
    var deletingRecord by mutableStateOf<LedgerRecord?>(null)
        private set
    var selectingRecords by mutableStateOf(false)
        private set
    var selectedRecordIds by mutableStateOf(emptySet<String>())
        private set
    var deletingRecords by mutableStateOf(emptyList<LedgerRecord>())
        private set
    var confirmDiscard by mutableStateOf(false)
        private set
    var documentName by mutableStateOf<String?>(null)
        private set
    var documentMime by mutableStateOf("")
        private set
    var documentSize by mutableStateOf(0)
        private set
    var csvCurrency by mutableStateOf("")
        private set
    var importRows by mutableStateOf(emptyList<LedgerImportRow>())
        private set
    var importReviews by mutableStateOf(emptyList<LedgerRowReview>())
        private set
    var reviewing by mutableStateOf(false)
        private set
    var editingImportId by mutableStateOf<String?>(null)
        private set
    var currencyDialog by mutableStateOf(false)
        private set
    private var originalDraft = draft
    private var saveRequestId = UUID.randomUUID().toString()
    private var importRequestId = UUID.randomUUID().toString()
    private var pendingDocument: ByteArray? = null
    private var cameraFile: File? = null
    private var filterJob: Job? = null
    private var reviewJob: Job? = null
    private var reviewGeneration = 0

    init {
        // Only this feature owns this directory. A new process removes abandoned camera captures.
        File(application.cacheDir, "ledger-captures").listFiles()?.forEach { it.delete() }
        refresh()
    }

    fun refresh() = runOperation("Loading your ledger…") {
        reload()
        loading = false
    }

    private suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) { repository.load() to repository.baseCurrency() }
        records = loaded.first
        selectedRecordIds = selectedRecordIds.intersect(records.map { it.id }.toSet())
        baseCurrency = loaded.second
        rebuildVisible()
        if (importRows.isNotEmpty()) rebuildReviews()
    }

    private fun runOperation(label: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        progress = label
        error = null
        notice = null
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Something went wrong. Please try again." }
            finally { busy = false; loading = false; progress = "" }
        }
    }

    fun dismissMessage() { error = null; notice = null }
    fun changeMonth(value: String?) { month = value; filterChanged() }
    fun shiftMonth(offset: Long) { changeMonth((month?.let(YearMonth::parse) ?: YearMonth.now(ZoneOffset.UTC)).plusMonths(offset).toString()) }
    fun changeKindFilter(value: String) { kindFilter = value; filterChanged() }
    fun changeSearch(value: String) { search = value.take(160); filterChanged() }
    private fun filterChanged() {
        cancelSelection()
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            try { rebuildVisible() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not calculate these totals." }
        }
    }

    private suspend fun rebuildVisible() {
        val snapshot = records
        val selectedMonth = month
        val selectedKind = kindFilter
        val query = search.trim()
        val currency = baseCurrency
        val result = withContext(Dispatchers.IO) {
            val filtered = snapshot.filter { item ->
                (selectedMonth == null || item.input.date.startsWith(selectedMonth)) &&
                    (selectedKind == "all" || item.input.kind == selectedKind) &&
                    (query.isBlank() || listOf(item.input.merchant, item.input.category, item.input.notes)
                        .any { it.contains(query, ignoreCase = true) })
            }.sortedWith(compareByDescending<LedgerRecord> { it.input.date }.thenByDescending { it.createdAt })
            filtered to LedgerDomain.summary(filtered, currency)
        }
        if (snapshot === records && selectedMonth == month && selectedKind == kindFilter && query == search.trim() && currency == baseCurrency) {
            visibleRecords = result.first
            summary = result.second
        }
    }

    fun newTransaction() {
        cancelSelection()
        if (busy) return
        editingRecord = null
        startEditor(LedgerInput(currency = baseCurrency, date = LocalDate.now(ZoneOffset.UTC).toString()), LedgerPage.Editor)
    }

    fun editTransaction(record: LedgerRecord) {
        if (busy) return
        editingRecord = record
        startEditor(record.input, LedgerPage.Editor)
    }

    private fun startEditor(input: LedgerInput, target: LedgerPage) {
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
            importRows = importRows.map { row ->
                if (row.rowId == id) row.copy(input = draft, acknowledgeDuplicate = false)
                else row.copy(acknowledgeDuplicate = false)
            }
            importRequestId = UUID.randomUUID().toString()
            page = LedgerPage.Import
            editingImportId = null
            error = null
            requestReview()
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
            rebuildVisible()
            reload()
        }
    }

    fun askDelete(record: LedgerRecord?) { if (!busy) deletingRecord = record }

    fun startSelection() { if (!busy) { selectingRecords = true; selectedRecordIds = emptySet() } }
    fun cancelSelection() {
        selectingRecords = false
        selectedRecordIds = emptySet()
        deletingRecords = emptyList()
    }
    fun toggleSelected(id: String) {
        if (busy || !selectingRecords || visibleRecords.none { it.id == id }) return
        selectedRecordIds = if (id in selectedRecordIds) selectedRecordIds - id else selectedRecordIds + id
    }
    fun selectAllVisible() {
        if (!busy && selectingRecords) selectedRecordIds = visibleRecords.map { it.id }.toSet()
    }
    fun clearSelected() { if (!busy) selectedRecordIds = emptySet() }
    fun askDeleteSelected() {
        if (!busy) deletingRecords = visibleRecords.filter { it.id in selectedRecordIds }
    }
    fun dismissBatchDelete() { deletingRecords = emptyList() }
    fun deleteSelectedConfirmed() {
        val selected = deletingRecords
        if (selected.isEmpty()) return
        deletingRecords = emptyList()
        runOperation("Deleting selected transactions…") {
            val count = withContext(Dispatchers.IO) { repository.deleteMany(selected) }
            val ids = selected.map { it.id }.toSet()
            records = records.filterNot { it.id in ids }
            cancelSelection()
            notice = "$count transactions deleted."
            rebuildVisible()
            reload()
        }
    }
    fun deleteConfirmed() {
        val record = deletingRecord ?: return
        deletingRecord = null
        runOperation("Deleting transaction…") {
            withContext(Dispatchers.IO) { repository.delete(record.id, record.revision) }
            records = records.filter { it.id != record.id }
            if (page == LedgerPage.Editor) page = LedgerPage.Dashboard
            notice = "Transaction deleted."
            rebuildVisible()
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
        cancelSelection()
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
        if (page == LedgerPage.Import) { clearImport(); page = LedgerPage.Dashboard }
        else leaveEditor()
        error = null
    }

    fun chooseDocument(uri: Uri) = runOperation("Reading document…") {
        val selected = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            var name = "Selected document"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty().take(180)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) require(cursor.getLong(sizeIndex) <= MAX_IMPORT_BYTES) {
                        "This file is too large. Choose a file no larger than 5 MiB."
                    }
                }
            }
            val bytes = resolver.openInputStream(uri)?.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_IMPORT_BYTES) { "This file is too large. Choose a file no larger than 5 MiB." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IllegalArgumentException("This document could not be opened. Choose it again.")
            require(bytes.isNotEmpty()) { "This file is empty." }
            Triple(name.ifBlank { "Selected document" }, resolver.getType(uri).orEmpty(), bytes)
        }
        setDocument(selected.first, selected.second, selected.third)
    }

    private fun setDocument(name: String, suppliedMime: String, bytes: ByteArray) {
        val mime = when {
            name.endsWith(".csv", true) || suppliedMime in listOf("text/csv", "application/csv", "text/comma-separated-values") -> "text/csv"
            name.endsWith(".pdf", true) || suppliedMime == "application/pdf" -> "application/pdf"
            suppliedMime in listOf("image/jpeg", "image/png", "image/webp") -> suppliedMime
            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
            name.endsWith(".png", true) -> "image/png"
            name.endsWith(".webp", true) -> "image/webp"
            else -> throw IllegalArgumentException("Choose a CSV, PDF, JPEG, PNG or WebP file.")
        }
        clearImport()
        documentName = name
        documentMime = mime
        documentSize = bytes.size
        pendingDocument = bytes
        page = LedgerPage.Import
    }

    fun prepareCamera(): Uri? {
        if (busy) return null
        return try {
            cameraFile?.delete()
            val app = getApplication<Application>()
            val folder = File(app.cacheDir, "ledger-captures").apply { mkdirs() }
            val file = File.createTempFile("receipt-", ".jpg", folder)
            cameraFile = file
            FileProvider.getUriForFile(app, "${app.packageName}.ledger.files", file)
        } catch (_: Exception) { error = "The camera could not be opened. You can choose an existing image instead."; null }
    }

    fun cameraResult(success: Boolean) {
        val file = cameraFile ?: return
        cameraFile = null
        if (!success) { file.delete(); return }
        runOperation("Reading photo…") {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    require(file.length() in 1..MAX_IMPORT_BYTES.toLong()) { "This photo is too large. Choose an image no larger than 5 MiB." }
                    file.readBytes()
                }
                setDocument("Receipt photo.jpg", "image/jpeg", bytes)
            } finally { file.delete() }
        }
    }

    fun cameraUnavailable() {
        cameraFile?.delete()
        cameraFile = null
        error = "No camera app is available. Choose an existing image instead."
    }

    fun changeCsvCurrency(currency: String) { if (!busy && currency in LedgerDomain.currencies) csvCurrency = currency }

    fun extractDocument() = extractDocumentUsing(localOnly = documentMime == "application/pdf")
    fun extractDocumentWithAi() = extractDocumentUsing(localOnly = false)

    private fun extractDocumentUsing(localOnly: Boolean) {
        val bytes = pendingDocument ?: return
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
            pendingDocument = null
            rebuildReviews()
            notice = "Review every amount and date before importing. Nothing has been saved yet."
        }
    }

    fun removeDocument() { if (!busy) { clearImport(); error = null; notice = null } }
    private fun clearImport() {
        reviewGeneration++
        reviewJob?.cancel()
        pendingDocument = null
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

    fun editImportRow(rowId: String) {
        if (busy) return
        val row = importRows.find { it.rowId == rowId } ?: return
        editingImportId = rowId
        startEditor(row.input, LedgerPage.ImportEditor)
    }

    fun selectImportRow(rowId: String, selected: Boolean) {
        if (busy) return
        importRows = importRows.map { if (it.rowId == rowId) it.copy(selected = selected, acknowledgeDuplicate = false) else it }
        importRequestId = UUID.randomUUID().toString()
        requestReview()
    }

    fun acknowledgeDuplicate(rowId: String, acknowledged: Boolean) {
        if (busy) return
        importRows = importRows.map { if (it.rowId == rowId) it.copy(acknowledgeDuplicate = acknowledged) else it }
        importRequestId = UUID.randomUUID().toString()
        requestReview()
    }

    private fun requestReview() {
        val generation = ++reviewGeneration
        reviewJob?.cancel()
        reviewing = true
        reviewJob = viewModelScope.launch {
            try { rebuildReviews() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not check these rows." }
            finally { if (reviewGeneration == generation) reviewing = false }
        }
    }

    private suspend fun rebuildReviews() {
        val rows = importRows
        val existing = records
        val reviewed = withContext(Dispatchers.IO) { repository.review(rows, existing) }
        if (rows === importRows && existing === records) importReviews = reviewed
    }

    fun confirmImport() {
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

    override fun onCleared() {
        cameraFile?.delete()
        pendingDocument = null
        // A request may still be finishing on IO; close under the repository lock without blocking the main thread.
        CoroutineScope(Dispatchers.IO).launch { repository.close() }
        super.onCleared()
    }

    companion object { const val MAX_IMPORT_BYTES = 5 * 1024 * 1024 }
}
