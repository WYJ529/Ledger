package com.example.cpen321application

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneOffset

internal class LedgerFiltersController(private val model: LedgerViewModel) {
    private var filterJob: Job? = null

    fun changeMonth(value: String?) {
        with(model) {
            month = value; filterChanged()
        }
    }

    fun shiftMonth(offset: Long) {
        with(model) {
            changeMonth((month?.let(YearMonth::parse) ?: YearMonth.now(ZoneOffset.UTC)).plusMonths(offset).toString())
        }
    }

    fun changeKindFilter(value: String) {
        with(model) {
            kindFilter = value; filterChanged()
        }
    }

    fun changeSearch(value: String) {
        with(model) {
            search = value.take(160); filterChanged()
        }
    }

    private fun filterChanged() {
        with(model) {
            selection.cancelSelection()
            filterJob?.cancel()
            filterJob = viewModelScope.launch {
                try { rebuildVisible() }
                catch (e: LedgerException) { error = e.message ?: "Could not calculate these totals." }
            }
        }
    }

    suspend fun rebuildVisible() {
        with(model) {
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
            val filtersUnchanged = selectedMonth == month && selectedKind == kindFilter && query == search.trim()
            if (snapshot === records && filtersUnchanged && currency == baseCurrency) {
                visibleRecords = result.first
                summary = result.second
            }
        }
    }
}
