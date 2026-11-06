package com.example.cpen321application

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.json.JSONArray
import org.json.JSONObject

/** A bill from the shared entry, ready to fill a group's forms. Amounts are decimal text as printed or typed. */
internal data class BillPrefill(
    val description: String = "",
    val total: String = "",
    val tax: String = "",
    val tip: String = "",
    val items: List<SplitReceiptRow> = emptyList(),
    val currency: String = "",
    val paidBy: String = "",
    val warnings: List<String> = emptyList(),
) {
    val hasReceiptDetail: Boolean get() = tax.isNotBlank() || tip.isNotBlank() || items.isNotEmpty()

    fun toJson(): String {
        val rows = JSONArray()
        items.forEach { rows.put(JSONObject().put("name", it.name).put("quantity", it.quantity).put("price", it.price)) }
        return JSONObject().put("description", description).put("total", total).put("tax", tax).put("tip", tip)
            .put("items", rows).put("currency", currency).put("paidBy", paidBy).put("warnings", JSONArray(warnings)).toString()
    }

    companion object {
        fun fromJson(text: String): BillPrefill {
            val json = JSONObject(text)
            val rows = json.getJSONArray("items")
            val warnings = json.getJSONArray("warnings")
            return BillPrefill(json.getString("description"), json.getString("total"), json.getString("tax"), json.getString("tip"),
                List(rows.length()) { index ->
                    rows.getJSONObject(index).let { SplitReceiptRow(it.getString("name"), it.getString("quantity"), it.getString("price")) }
                },
                json.getString("currency"), json.getString("paidBy"), List(warnings.length()) { warnings.getString(it) })
        }
    }
}

/** Where the entry sends the user: to a group, with a bill to finish there unless they only went back. */
internal class BillRoute(val groupId: String, val bill: BillPrefill? = null)

// A zero tax or tip is the same as none, and leaves the form's optional field empty.
private fun printedAmount(text: String): String =
    text.trim().takeUnless { moneyCents(it, allowZero = true) == 0L }.orEmpty()

internal fun billPrefill(row: LedgerImportRow, receipt: LedgerReceipt?): BillPrefill = BillPrefill(
    description = row.input.merchant.take(100),
    total = row.input.amount,
    tax = receipt?.let { printedAmount(it.tax) }.orEmpty(),
    tip = receipt?.let { printedAmount(it.tip) }.orEmpty(),
    items = receipt?.items.orEmpty().map { SplitReceiptRow(it.name.take(80), it.quantity, it.unitPrice) },
    currency = row.input.currency,
    warnings = row.warnings,
)

/** A personal ledger row holds one amount, so a receipt's tax and tip stay readable in its notes. */
internal fun withReceiptBreakdown(row: LedgerImportRow, receipt: LedgerReceipt?): LedgerImportRow {
    if (receipt == null || (printedAmount(receipt.tax).isEmpty() && printedAmount(receipt.tip).isEmpty())) return row
    val breakdown = tf("Subtotal %1\$s · Tax %2\$s · Tip %3\$s", receipt.subtotal, receipt.tax, receipt.tip)
    val notes = listOf(row.input.notes, breakdown).filter { it.isNotBlank() }.joinToString("\n").take(1000)
    return row.copy(input = row.input.copy(notes = notes))
}

/** The shared Add a bill entry: who a bill is for, and handing it to that group once it is known. */
internal class LedgerBillEntry(private val model: LedgerViewModel) {
    /** The group the bill goes to; null keeps it in this ledger. */
    var target by mutableStateOf<ExpenseGroup?>(null)
        private set
    var route by mutableStateOf<BillRoute?>(null)
        private set

    // The group the entry was opened from, so going back returns there.
    private var origin: String? = null

    fun open(group: ExpenseGroup? = null) {
        if (model.busy) return
        target = group
        origin = group?.id
        model.openImport()
    }

    fun choose(group: ExpenseGroup?) {
        if (!model.busy) target = group
    }

    fun enterManually() {
        val group = target
        if (group == null) model.newTransaction() else handOver(group.id, BillPrefill())
    }

    /** One bill goes to the chosen group. Several rows are a statement, which only this ledger can review. */
    fun accept(extraction: LedgerExtraction): Boolean {
        val group = target ?: return false
        val row = extraction.rows.singleOrNull()
        if (row == null) {
            target = null
            return false
        }
        handOver(group.id, billPrefill(row, extraction.receipt))
        return true
    }

    /** CSV files always hold several transactions. */
    fun documentChosen(mime: String) {
        if (mime == "text/csv" && target != null) {
            target = null
            model.notice = "Statements and CSV files hold several transactions, so they stay in My Expenses."
        }
    }

    fun leave() {
        val returnTo = origin
        reset()
        model.page = LedgerPage.Dashboard
        if (returnTo != null) route = BillRoute(returnTo)
    }

    fun takeRoute(): BillRoute? = route.also { route = null }

    fun reset() {
        target = null
        origin = null
    }

    private fun handOver(groupId: String, bill: BillPrefill) {
        reset()
        model.imports.clearImport()
        model.page = LedgerPage.Dashboard
        route = BillRoute(groupId, bill)
    }
}

/** What a group form should know about a bill that was read from a document. */
@Composable
internal fun BillPrefillNotes(bill: BillPrefill, currency: String) {
    if (bill.currency.isNotBlank() && bill.currency != currency) {
        Text(tf("This receipt is in %1\$s but the group uses %2\$s. Amounts are not converted; check them before saving.", bill.currency, currency),
            color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
    }
    bill.warnings.distinct().forEach { warning ->
        Text(tf("Review: %1\$s", t(warning)), style = MaterialTheme.typography.bodySmall)
    }
}
