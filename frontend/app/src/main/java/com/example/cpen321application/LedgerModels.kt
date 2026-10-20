package com.example.cpen321application

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

data class LedgerInput(
    val kind: String = "expense",
    val amount: String = "",
    val currency: String = "CAD",
    val date: String = LocalDate.now(ZoneOffset.UTC).toString(),
    val merchant: String = "",
    val category: String = "Other",
    val notes: String = ""
)

data class LedgerFx(
    val baseCurrency: String,
    val rate: String,
    val rateDate: String,
    val source: String,
    val convertedMinor: Long
)

data class LedgerRecord(
    val id: String,
    val input: LedgerInput,
    val amountMinor: Long,
    val fx: LedgerFx,
    val source: String = "manual",
    val sourceFileName: String = "",
    val revision: Int = 1,
    val createdAt: String,
    val updatedAt: String
)

data class LedgerImportRow(
    val rowId: String,
    val input: LedgerInput,
    val selected: Boolean = true,
    val acknowledgeDuplicate: Boolean = false,
    val warnings: List<String> = emptyList()
)

data class DuplicateMatch(
    val id: String,
    val merchant: String,
    val date: String,
    val amount: String,
    val currency: String,
    val score: Int,
    val reason: String,
    val withinImport: Boolean
)

data class LedgerRowReview(
    val row: LedgerImportRow,
    val errors: Map<String, String>,
    val duplicates: List<DuplicateMatch>
)

data class LedgerSummary(
    val currency: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val netMinor: Long,
    val count: Int,
    val categories: Map<String, Long>,
    val exchangeDates: List<String>
)

class LedgerException(message: String) : Exception(message)

/** Pure ledger rules. Money is decimal text at the boundary and integer minor units in storage. */
object LedgerDomain {
    val currencies = listOf("CAD", "USD", "EUR", "GBP", "CNY", "JPY")
    val categories = listOf("Food", "Transport", "Shopping", "Housing", "Health", "Entertainment", "Salary", "Other")
    const val MAX_AMOUNT_MINOR = 1_000_000_000_000L
    const val MAX_IMPORT_ROWS = 200
    private val firstRateDate = LocalDate.of(1999, 1, 4)
    private val amountPattern = Regex("^[0-9]+(\\.[0-9]{1,2})?$")
    private val nonIdentifyingTokens = setOf("the", "inc", "ltd", "llc", "co", "corp", "corporation", "pos", "debit",
        "credit", "visa", "mastercard", "purchase", "payment", "store", "shop", "restaurant", "cafe")

    fun normalize(input: LedgerInput): LedgerInput {
        val currency = input.currency.trim().uppercase(Locale.ROOT)
        val amount = input.amount.trim()
        val canonicalAmount = try { formatMinor(parseMinor(amount, currency), currency) } catch (_: LedgerException) { amount }
        return input.copy(kind = input.kind.trim().lowercase(Locale.ROOT), amount = canonicalAmount, currency = currency,
            date = input.date.trim(), merchant = input.merchant.trim().replace(Regex("\\s+"), " "),
            category = categories.firstOrNull { it.equals(input.category.trim(), true) } ?: input.category.trim(), notes = input.notes.trim())
    }

    fun decimals(currency: String): Int = if (currency == "JPY") 0 else 2

    fun validate(input: LedgerInput, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Map<String, String> {
        val value = normalize(input)
        val errors = linkedMapOf<String, String>()
        if (value.kind != "expense" && value.kind != "income") errors["kind"] = "Choose expense or income."
        if (value.currency !in currencies) errors["currency"] = "Choose a supported currency."
        try {
            parseMinor(value.amount, value.currency)
        } catch (_: LedgerException) {
            errors["amount"] = if (value.currency == "JPY") "Enter a positive whole yen amount (maximum 1,000,000,000,000)."
            else "Enter a positive amount with at most two decimals (maximum 10,000,000,000.00)."
        }
        val parsedDate = try { LocalDate.parse(value.date) } catch (_: DateTimeException) { null }
        val dateInRange = parsedDate != null && parsedDate in firstRateDate..today
        if (!Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(value.date) || !dateInRange) {
            errors["date"] = "Use a valid date from 1999-01-04 through today (YYYY-MM-DD)."
        }
        if (value.merchant.isBlank() || value.merchant.length > 120) errors["merchant"] = "Enter a merchant or description of 1–120 characters."
        if (value.category !in categories) errors["category"] = "Choose a supported category."
        if (value.notes.length > 1000) errors["notes"] = "Notes must be at most 1,000 characters."
        return errors
    }

    fun parseMinor(amount: String, currency: String): Long {
        val value = amount.trim()
        val invalidPrecision = !amountPattern.matches(value) || (currency == "JPY" && value.contains('.'))
        if (currency !in currencies || value.length > 32 || invalidPrecision) throw LedgerException("Invalid amount or currency.")
        val result = try {
            BigDecimal(value).movePointRight(decimals(currency)).longValueExact()
        } catch (_: ArithmeticException) { throw LedgerException("Amount is too large.") }
        if (result !in 1..MAX_AMOUNT_MINOR) throw LedgerException("Amount is outside the allowed range.")
        return result
    }

    fun formatMinor(amount: Long, currency: String): String =
        BigDecimal.valueOf(amount, decimals(currency)).setScale(decimals(currency)).toPlainString()

    fun convertMinor(amountMinor: Long, from: String, to: String, rate: String): Long {
        val invalidCurrencies = from !in currencies || to !in currencies
        val invalidRate = rate.length > 32 || !Regex("^[0-9]+(\\.[0-9]{1,12})?$").matches(rate)
        if (invalidCurrencies || amountMinor < 0 || invalidRate) {
            throw LedgerException("The exchange service returned an invalid rate.")
        }
        val multiplier = BigDecimal(rate)
        if (multiplier.signum() <= 0) throw LedgerException("The exchange service returned an invalid rate.")
        return try {
            val converted = BigDecimal.valueOf(amountMinor, decimals(from)).multiply(multiplier)
                .movePointRight(decimals(to)).setScale(0, RoundingMode.HALF_UP).longValueExact()
            if (converted > 9_007_199_254_740_991L) throw LedgerException("The converted amount is too large.")
            converted
        } catch (_: ArithmeticException) { throw LedgerException("The converted amount is too large.") }
    }

    fun summary(records: List<LedgerRecord>, currency: String): LedgerSummary {
        var income = 0L
        var expense = 0L
        val totals = linkedMapOf<String, Long>()
        try {
            for (record in records) {
                if (record.fx.baseCurrency != currency) throw LedgerException("Refresh the ledger after changing the reporting currency.")
                if (record.input.kind == "income") income = Math.addExact(income, record.fx.convertedMinor)
                else {
                    expense = Math.addExact(expense, record.fx.convertedMinor)
                    totals[record.input.category] = Math.addExact(totals[record.input.category] ?: 0, record.fx.convertedMinor)
                }
            }
            return LedgerSummary(currency, income, expense, Math.subtractExact(income, expense), records.size,
                totals.toList().sortedByDescending { it.second }.toMap(),
                records.filter { it.input.currency != currency }.map { it.fx.rateDate }.distinct().sorted())
        } catch (_: ArithmeticException) { throw LedgerException("The summary total is too large to display safely.") }
    }

    fun duplicateScore(first: LedgerInput, second: LedgerInput): Pair<Int, String>? {
        val a = normalize(first)
        val b = normalize(second)
        if (a.kind != b.kind || a.currency != b.currency) return null
        val amountsEqual = try { parseMinor(a.amount, a.currency) == parseMinor(b.amount, b.currency) }
        catch (_: LedgerException) { false }
        if (!amountsEqual) return null
        val gap = try { kotlin.math.abs(ChronoUnit.DAYS.between(LocalDate.parse(a.date), LocalDate.parse(b.date))) }
        catch (_: DateTimeException) { return null }
        if (gap > 3) return null
        fun merchantKey(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val left = merchantKey(a.merchant)
        val right = merchantKey(b.merchant)
        if (left.isBlank() || right.isBlank()) return null
        var similarity = 1.0
        if (left != right) {
            fun tokens(value: String) = value.split(' ').filter {
                it.isNotEmpty() && it !in nonIdentifyingTokens && !it.all(Char::isDigit)
            }.toSet()
            val leftTokens = tokens(left)
            val rightTokens = tokens(right)
            val shared = leftTokens.intersect(rightTokens).size
            val union = leftTokens.union(rightTokens).size
            if (shared == 0 || union == 0 || shared * 2 < union) return null
            similarity = shared.toDouble() / union
        }
        return Pair(50 + (35 * similarity).roundToInt() + 15 - gap.toInt() * 5,
            "Same ${a.kind} amount and currency; ${if (left == right) "same merchant" else "overlapping merchant name"}; ${if (gap == 0L) "same date" else "dates $gap days apart"}.")
    }

    fun review(rows: List<LedgerImportRow>, existing: List<LedgerRecord>): List<LedgerRowReview> {
        if (rows.size > MAX_IMPORT_ROWS) throw LedgerException("Import at most 200 transactions at a time.")
        if (rows.map { it.rowId }.toSet().size != rows.size) throw LedgerException("Import row identifiers must be unique.")
        return rows.map { row ->
            val matches = existing.mapNotNull { record ->
                duplicateScore(row.input, record.input)?.let { (score, reason) ->
                    DuplicateMatch(record.id, record.input.merchant, record.input.date, record.input.amount,
                        record.input.currency, score, reason, false)
                }
            } + rows.filter { it.rowId != row.rowId && it.selected }.mapNotNull { other ->
                duplicateScore(row.input, other.input)?.let { (score, reason) ->
                    DuplicateMatch(other.rowId, other.input.merchant, other.input.date, other.input.amount,
                        other.input.currency, score, reason, true)
                }
            }
            LedgerRowReview(row, validate(row.input), matches.sortedByDescending { it.score })
        }
    }

    fun parseCsv(text: String, defaultCurrency: String = "CAD", statementCurrency: String? = null): List<LedgerImportRow> =
        LedgerCsvImports.parseCsv(text, defaultCurrency, statementCurrency)
}
