package com.example.cpen321application

import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale
import com.example.cpen321application.LedgerDomain.MAX_IMPORT_ROWS
import com.example.cpen321application.LedgerDomain.categories
import com.example.cpen321application.LedgerDomain.currencies

private data class CsvColumns(val headers: List<String?>, val amex: Boolean, val chase: Boolean,
    val currency: String, val warnings: List<String>)

internal object LedgerCsvImports {
    /** RFC 4180 CSV, including BOM, escaped quotes and newlines inside a quoted field. */
    fun parseCsv(text: String, defaultCurrency: String = "CAD", statementCurrency: String? = null): List<LedgerImportRow> {
        if (text.toByteArray(Charsets.UTF_8).size > 5 * 1024 * 1024) throw LedgerException("CSV files must be 5 MB or smaller.")
        val fallbackCurrency = (statementCurrency ?: defaultCurrency).trim().uppercase(Locale.ROOT)
        if (fallbackCurrency !in currencies) throw LedgerException("Choose a supported default currency.")
        val table = LedgerCsvTableReader(text.removePrefix("\uFEFF")).read()
        if (table.isEmpty()) throw LedgerException("The CSV file is empty.")
        val aliases = mapOf(
            "date" to "date", "transactiondate" to "date", "posteddate" to "date", "postingdate" to "date",
            "amount" to "amount", "total" to "amount", "transactionamount" to "amount",
            "merchant" to "merchant", "merchantname" to "merchant", "description" to "merchant", "payee" to "merchant", "vendor" to "merchant",
            "currency" to "currency", "currencycode" to "currency", "curr" to "currency", "kind" to "kind", "type" to "kind",
            "transactiontype" to "kind", "direction" to "kind",
            "category" to "category", "notes" to "notes", "note" to "notes", "memo" to "notes"
        )
        val rawHeaders = table.first().map { it.trim().lowercase(Locale.ROOT).replace(Regex("[\\s_-]+"), "") }
        val amex = rawHeaders.containsAll(listOf("date", "description", "cardmember", "account#", "amount"))
        val chase = rawHeaders.containsAll(listOf("transactiondate", "postdate", "description", "type", "amount"))
        val bank = amex || chase
        val headers = rawHeaders.map { aliases[it] }
        for (required in listOf("date", "amount", "merchant")) {
            if (required !in headers) throw LedgerException("CSV requires date, amount, and merchant (or description) columns.")
        }
        if (headers.filterNotNull().size != headers.filterNotNull().toSet().size) throw LedgerException("CSV contains duplicate recognized columns.")
        val ignored = table.first().filterIndexed { index, _ -> headers[index] == null }
        val commonWarnings = mutableListOf<String>()
        if (bank && "currency" !in headers && statementCurrency == null) throw LedgerException("This bank CSV does not identify its currency. Choose the statement currency before previewing; the ledger reporting currency is not evidence of the card currency.")
        if (ignored.isNotEmpty()) commonWarnings.add("Ignored columns: ${ignored.joinToString(", ").take(250)}")
        if ("currency" !in headers) commonWarnings.add("No currency column: using $fallbackCurrency. Confirm this currency before importing.")
        val data = table.drop(1)
        if (data.size > MAX_IMPORT_ROWS) throw LedgerException("Import at most 200 transactions at a time.")
        if (data.isEmpty()) throw LedgerException("The CSV has no transactions.")
        val columns = CsvColumns(headers, amex, chase, fallbackCurrency, commonWarnings)
        val result = data.mapIndexedNotNull { index, cells -> parseRow(index, cells, columns) }
        val skippedPayments = data.size - result.size
        if (bank && result.isEmpty()) throw LedgerException("Skipped $skippedPayments credit-card repayments. No transactions remain to import.")
        return if (bank) result.map { it.copy(warnings = listOf("Skipped $skippedPayments credit-card repayments; repayments are transfers, not income.") + it.warnings) } else result
    }

    private fun parseRow(index: Int, cells: List<String>, columns: CsvColumns): LedgerImportRow? {
        fun cell(name: String, fallback: String = ""): String {
            val column = columns.headers.indexOf(name)
            return if (column < 0) fallback else cells.getOrNull(column)?.trim().orEmpty()
        }
        val warnings = columns.warnings.toMutableList()
        if (cells.size != columns.headers.size) warnings.add("This row has ${cells.size} fields; the header has ${columns.headers.size}. Review all values.")
        val bank = columns.amex || columns.chase
        val rawAmount = cell("amount")
        val rawKind = cell("kind").lowercase(Locale.ROOT)
        val merchant = cell("merchant")
        val bankCredit = if (columns.amex) rawAmount.startsWith('-') else !rawAmount.startsWith('-')
        val repayment = isCardRepayment(merchant) || (columns.chase && rawKind == "payment")
        if (bank && bankCredit && repayment) return null
        var kind = when (rawKind) { "debit" -> "expense"; "credit" -> "income"; "" -> "expense"; else -> rawKind }
        val amount = (if (rawAmount.startsWith('-') || bank && rawAmount.startsWith('+')) rawAmount.drop(1) else rawAmount).let {
            if (bank && Regex("^\\d{1,3}(,\\d{3})+(\\.\\d{1,2})?$").matches(it)) it.replace(",", "") else it
        }
        if (bank) {
            kind = if (bankCredit) "income" else "expense"
            if (kind == "income") warnings.add("Card refund/credit retained for review. Ledger represents credits as income; check the merchant and amount.")
        } else if (rawKind.isEmpty()) warnings.add("Type defaulted to expense. Confirm expense or income before importing.")
        if (!bank && rawAmount.startsWith('-')) {
            warnings.add(if (kind == "income") "This row says income but has a negative amount. It is marked as an expense; confirm the correct type."
                else "Negative statement amount converted to a positive expense amount.")
            kind = "expense"
        }
        val category = cell("category", "Other").let { raw -> categories.firstOrNull { it.equals(raw, true) } ?: if (bank) "Other" else raw }
        val date = if (bank) bankCsvDate(cell("date")) else cell("date")
        return LedgerImportRow("csv-${index + 1}", LedgerInput(kind, amount, cell("currency", columns.currency).uppercase(Locale.ROOT),
            date, merchant, category, cell("notes")), warnings = warnings)
    }

    private fun isCardRepayment(description: String): Boolean = Regex(
        "\\b(?:payment\\s+(?:thank\\s*you|received)|(?:mobile|online|autopay|automatic|internet)\\s+payment|payment\\s*[-–]\\s*thank\\s*you)\\b", RegexOption.IGNORE_CASE
    ).containsMatchIn(description)

    private fun bankCsvDate(raw: String): String {
        val match = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").matchEntire(raw) ?: return raw
        return try { LocalDate.of(match.groupValues[3].toInt(), match.groupValues[1].toInt(), match.groupValues[2].toInt()).toString() }
            catch (_: DateTimeException) { raw }
    }

}
