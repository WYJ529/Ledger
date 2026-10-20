package com.example.cpen321application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LedgerSelectionController(private val model: LedgerViewModel) {

    fun startSelection() {
        with(model) {
            if (!busy) { selectingRecords = true; selectedRecordIds = emptySet() }
        }
    }

    fun cancelSelection() {
        with(model) {
            selectingRecords = false
            selectedRecordIds = emptySet()
            deletingRecords = emptyList()
        }
    }

    fun toggleSelected(id: String) {
        with(model) {
            if (busy || !selectingRecords || visibleRecords.none { it.id == id }) return
            selectedRecordIds = if (id in selectedRecordIds) selectedRecordIds - id else selectedRecordIds + id
        }
    }

    fun selectAllVisible() {
        with(model) {
            if (!busy && selectingRecords) selectedRecordIds = visibleRecords.map { it.id }.toSet()
        }
    }

    fun clearSelected() {
        with(model) {
            if (!busy) selectedRecordIds = emptySet()
        }
    }

    fun askDeleteSelected() {
        with(model) {
            if (!busy) deletingRecords = visibleRecords.filter { it.id in selectedRecordIds }
        }
    }

    fun dismissBatchDelete() {
        with(model) {
            deletingRecords = emptyList()
        }
    }

    fun deleteSelectedConfirmed() {
        with(model) {
            val selected = deletingRecords
            if (selected.isEmpty()) return
            deletingRecords = emptyList()
            runOperation("Deleting selected transactions…") {
                val count = withContext(Dispatchers.IO) { repository.deleteMany(selected) }
                val ids = selected.map { it.id }.toSet()
                records = records.filterNot { it.id in ids }
                cancelSelection()
                notice = "$count transactions deleted."
                filters.rebuildVisible()
                reload()
            }
        }
    }
}
