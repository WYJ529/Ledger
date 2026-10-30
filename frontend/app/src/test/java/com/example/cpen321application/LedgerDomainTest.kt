package com.example.cpen321application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LedgerDomainTest {
    private val today = LocalDate.of(2026, 10, 16)
    private fun input(amount: String = "12.34", currency: String = "CAD", date: String = "2026-10-15", merchant: String = "Coffee Corner") =
        LedgerInput(amount = amount, currency = currency, date = date, merchant = merchant, category = "Food")

    private fun record(id: String = "saved", input: LedgerInput = input(), converted: Long = 1234, base: String = "CAD") =
        LedgerRecord(id, input, LedgerDomain.parseMinor(input.amount, input.currency),
            LedgerFx(base, "1", input.date, "Test rate", converted), createdAt = "2026-10-16T00:00:00Z", updatedAt = "2026-10-16T00:00:00Z")

    @Test fun decimalAmountsAreExactAndCanonical() {
        assertEquals(29L, LedgerDomain.parseMinor("0.29", "CAD"))
        assertEquals(1230L, LedgerDomain.parseMinor("0012.3", "USD"))
        assertEquals(12L, LedgerDomain.parseMinor("12", "JPY"))
        assertEquals("0.29", LedgerDomain.formatMinor(29, "CAD"))
        assertEquals("-0.01", LedgerDomain.formatMinor(-1, "EUR"))
        assertEquals("12.30", LedgerDomain.normalize(input("0012.3")).amount)
        assertEquals(LedgerDomain.MAX_AMOUNT_MINOR, LedgerDomain.parseMinor("10000000000.00", "CAD"))
        assertEquals(LedgerDomain.MAX_AMOUNT_MINOR, LedgerDomain.parseMinor("1000000000000", "JPY"))
    }

    @Test fun moneyRejectsSignsScientificNotationAndExtraPrecision() {
        for (amount in listOf("0", "-1", "+1", "1e3", "NaN", "Infinity", "1,000.00", ".5", "1.", "1.001", "10000000000.01")) {
            assertThrows(amount, LedgerException::class.java) { LedgerDomain.parseMinor(amount, "CAD") }
        }
        assertThrows(LedgerException::class.java) { LedgerDomain.parseMinor("1.00", "JPY") }
        assertThrows(LedgerException::class.java) { LedgerDomain.parseMinor("1", "ABC") }
    }

    @Test fun validationUsesRealCalendarDatesAndUtcBoundary() {
        assertTrue(LedgerDomain.validate(input(date = "2024-02-29"), today).isEmpty())
        assertTrue(LedgerDomain.validate(input(date = "1999-01-04"), today).isEmpty())
        assertTrue(LedgerDomain.validate(input(date = "2026-10-16"), today).isEmpty())
        for (date in listOf("2026-02-29", "2026-04-31", "2026-13-01", "1999-01-03", "2026-10-17", "10/15/2026", "2026-1-01")) {
            assertTrue(date, LedgerDomain.validate(input(date = date), today).containsKey("date"))
        }
    }

    @Test fun normalizationRetainsMeaningWhileValidationCatchesUnsupportedValues() {
        val normalized = LedgerDomain.normalize(input().copy(kind = " Income ", currency = " cad ", merchant = "  Coffee   Corner ", category = " food ", notes = " note "))
        assertEquals("income", normalized.kind)
        assertEquals("CAD", normalized.currency)
        assertEquals("Coffee Corner", normalized.merchant)
        assertEquals("Food", normalized.category)
        assertEquals("note", normalized.notes)
        assertTrue(LedgerDomain.validate(normalized, today).isEmpty())
        val errors = LedgerDomain.validate(input().copy(kind = "refund", currency = "CHF", merchant = " ", category = "Unknown", notes = "a".repeat(1001)), today)
        assertTrue(errors.keys.containsAll(listOf("kind", "currency", "merchant", "category", "notes")))
    }

    @Test fun fxUsesDecimalHalfUpAndCurrencyPrecision() {
        assertEquals(1L, LedgerDomain.convertMinor(1, "USD", "CAD", "0.5"))
        assertEquals(0L, LedgerDomain.convertMinor(1, "USD", "JPY", "1"))
        assertEquals(2L, LedgerDomain.convertMinor(100, "USD", "JPY", "1.5"))
        assertEquals(125L, LedgerDomain.convertMinor(1, "JPY", "CAD", "1.25"))
        assertEquals(29L, LedgerDomain.convertMinor(29, "USD", "CAD", "1"))
        assertEquals(1375L, LedgerDomain.convertMinor(1000, "USD", "CAD", "1.375"))
    }

    @Test fun fxRejectsInvalidRatesAndUnsafeResults() {
        for (rate in listOf("0", "-1", "1e3", "NaN", "0.0000000000001")) {
            assertThrows(LedgerException::class.java) { LedgerDomain.convertMinor(100, "USD", "CAD", rate) }
        }
        assertThrows(LedgerException::class.java) { LedgerDomain.convertMinor(1_000_000_000_000, "JPY", "CAD", "1000000000") }
    }

    @Test fun summaryUsesConvertedAmountsAndOnlyExpensesInCategories() {
        val expense = record(input = input(currency = "USD"), converted = 1700)
        val income = record("salary", input().copy(kind = "income", amount = "50.00", category = "Salary"), converted = 5000)
        val secondExpense = record("bus", input().copy(category = "Transport"), converted = 300)
        val summary = LedgerDomain.summary(listOf(expense, income, secondExpense), "CAD")
        assertEquals(2000L, summary.expenseMinor)
        assertEquals(5000L, summary.incomeMinor)
        assertEquals(3000L, summary.netMinor)
        assertEquals(mapOf("Food" to 1700L, "Transport" to 300L), summary.categories)
        assertEquals(listOf("2026-10-15"), summary.exchangeDates)
        assertEquals(3, summary.count)
        assertFalse(summary.categories.containsKey("Salary"))
    }

    @Test fun summariesRejectMixedReportingCurrenciesAndOverflow() {
        assertThrows(LedgerException::class.java) { LedgerDomain.summary(listOf(record(base = "USD")), "CAD") }
        assertThrows(LedgerException::class.java) { LedgerDomain.summary(listOf(record(converted = Long.MAX_VALUE), record("two", converted = 1)), "CAD") }
        assertEquals(0L, LedgerDomain.summary(emptyList(), "CAD").netMinor)
    }

    @Test fun duplicatesMatchMerchantVariantsAndNearbyDatesButRequireSameOriginalMoney() {
        val original = input(merchant = "Café Corner")
        assertEquals(100, LedgerDomain.duplicateScore(original, original.copy(merchant = "CAFE CORNER"))?.first)
        assertEquals(85, LedgerDomain.duplicateScore(original, original.copy(date = "2026-10-12"))?.first)
        assertNotNull(LedgerDomain.duplicateScore(original, original.copy(merchant = "POS Cafe Corner #123")))
        assertNull(LedgerDomain.duplicateScore(original, original.copy(date = "2026-10-11")))
        assertNull(LedgerDomain.duplicateScore(original, original.copy(kind = "income")))
        assertNull(LedgerDomain.duplicateScore(original, original.copy(currency = "USD")))
        assertNull(LedgerDomain.duplicateScore(original, original.copy(amount = "12.35")))
    }

    @Test fun genericMerchantTokensDoNotCreateFalseDuplicates() {
        assertNull(LedgerDomain.duplicateScore(input(merchant = "Blue Restaurant Shop"), input(merchant = "Red Restaurant Shop")))
        assertNull(LedgerDomain.duplicateScore(input(merchant = "POS VISA 123"), input(merchant = "CREDIT VISA 123")))
        assertNull(LedgerDomain.duplicateScore(input(merchant = " "), input(merchant = " ")))
    }

    @Test fun reviewFindsExistingAndSelectedBatchDuplicatesWithoutChangingSelection() {
        val first = LedgerImportRow("row1", input())
        val second = LedgerImportRow("row2", input())
        val review = LedgerDomain.review(listOf(first, second), listOf(record()))
        assertEquals(2, review.first().duplicates.size)
        assertTrue(review.first().duplicates.any { it.withinImport && it.id == "row2" })
        assertTrue(review.first().duplicates.any { !it.withinImport && it.id == "saved" })
        assertTrue(review.all { it.row.selected && !it.row.acknowledgeDuplicate })
        assertTrue(LedgerDomain.review(listOf(first, second.copy(selected = false)), emptyList()).first().duplicates.isEmpty())
    }

    @Test fun csvHandlesBomEscapedQuotesEmbeddedNewlinesAndHeaderAliases() {
        val csv = "\uFEFFPosting Date,Transaction Amount,Description,curr,type,notes\r\n" +
            "2026-10-15,12.34,\"Coffee, \"\"Corner\"\"\",USD,debit,\"first\r\nsecond\"\r\n"
        val row = LedgerDomain.parseCsv(csv).single()
        assertEquals("Coffee, \"Corner\"", row.input.merchant)
        assertEquals("first\nsecond", row.input.notes)
        assertEquals("USD", row.input.currency)
        assertEquals("expense", row.input.kind)
        assertEquals("Other", row.input.category)
        assertTrue(LedgerDomain.validate(row.input, today).isEmpty())
    }

    @Test fun csvDefaultsAreExplicitAndNegativeIncomeNeedsReview() {
        val defaulted = LedgerDomain.parseCsv("date,amount,merchant,unused\n2026-10-15,2.50,Shop,something", "USD").single()
        assertEquals("USD", defaulted.input.currency)
        assertTrue(defaulted.warnings.any { it.contains("currency") })
        assertTrue(defaulted.warnings.any { it.contains("expense") })
        assertTrue(defaulted.warnings.any { it.contains("Ignored") })
        val signed = LedgerDomain.parseCsv("date,amount,merchant,kind\n2026-10-15,-2.50,Shop,income").single()
        assertEquals("2.50", signed.input.amount)
        assertEquals("expense", signed.input.kind)
        assertTrue(signed.warnings.any { it.contains("income") })
    }

    @Test fun csvRetainsMalformedAmountsDatesAndBlankRowsForCorrection() {
        val rows = LedgerDomain.parseCsv("date,amount,merchant,currency,kind\n2026-02-30,1e3,Shop,CHF,refund\n,,,,\n")
        assertEquals(2, rows.size)
        assertEquals("refund", rows.first().input.kind)
        assertEquals("1e3", rows.first().input.amount)
        val reviews = LedgerDomain.review(rows, emptyList())
        assertTrue(reviews.first().errors.keys.containsAll(listOf("date", "amount", "currency", "kind")))
        assertTrue(reviews.last().errors.keys.containsAll(listOf("date", "amount", "merchant", "currency")))
    }

    @Test fun amexCsvUsesExplicitCurrencySkipsRepaymentsAndRetainsRefunds() {
        val csv = "Date,Description,Card Member,Account #,Amount\n" +
            "06/02/2025,ONLINE PAYMENT - THANK YOU,Private Name,12345,-50.00\n" +
            "06/03/2025,Shop refund,Private Name,12345,-4.00\n" +
            "06/04/2025,Example Payment Solutions,Private Name,12345,20.00"
        assertThrows(LedgerException::class.java) { LedgerDomain.parseCsv(csv, "CAD") }
        val rows = LedgerDomain.parseCsv(csv, "CAD", "USD")
        assertEquals(2, rows.size)
        assertEquals(listOf("income", "expense"), rows.map { it.input.kind })
        assertEquals("2025-06-03", rows[0].input.date)
        assertEquals("4.00", rows[0].input.amount)
        assertTrue(rows.all { it.input.currency == "USD" && it.input.notes.isBlank() })
        assertTrue(rows.all { it.warnings.any { warning -> warning.contains("Skipped 1") } })
        assertTrue(rows[0].warnings.any { it.contains("refund") })
        assertTrue(rows.none { it.toString().contains("Private Name") || it.toString().contains("12345") })
    }

    @Test fun chaseCsvHasOppositeAmountConventionAndDoesNotImportPayments() {
        val csv = "Transaction Date,Post Date,Description,Category,Type,Amount,Memo\n" +
            "06/02/2025,06/03/2025,Shop,Groceries,Sale,-10.00,\n" +
            "06/03/2025,06/04/2025,Shop,Groceries,Return,2.00,\n" +
            "06/05/2025,06/05/2025,Thank you,,Payment,8.00,"
        val rows = LedgerDomain.parseCsv(csv, "CAD", "USD")
        assertEquals(listOf("expense", "income"), rows.map { it.input.kind })
        assertEquals(listOf("10.00", "2.00"), rows.map { it.input.amount })
        assertEquals("2025-06-02", rows[0].input.date)
        assertTrue(rows.all { it.input.category == "Other" })
    }

    @Test fun bankCsvKeepsInvalidDatesAndDoesNotGuessGenericCsvLocale() {
        val badDate = LedgerDomain.parseCsv("Date,Description,Card Member,Account #,Amount\n02/30/2025,Shop,A,1,2.00", "CAD", "USD").single()
        assertEquals("02/30/2025", badDate.input.date)
        assertTrue(LedgerDomain.validate(badDate.input).containsKey("date"))
        assertEquals("06/02/2025", LedgerDomain.parseCsv("date,merchant,amount\n06/02/2025,Shop,2.00").single().input.date)
        assertThrows(LedgerException::class.java) {
            LedgerDomain.parseCsv("Date,Description,Card Member,Account #,Amount\n06/02/2025,ONLINE PAYMENT - THANK YOU,A,1,-2.00", "CAD", "USD")
        }
    }

    @Test fun bankCsvDoesNotSilentlyFixMalformedGroupingOrDiscardPaymentReversals() {
        val csv = "Date,Description,Card Member,Account #,Amount\n" +
            "06/02/2025,Shop,A,1,\"1,234.50\"\n06/03/2025,Shop,A,1,\"12,34.50\"\n06/04/2025,ONLINE PAYMENT REVERSAL,A,1,20.00"
        val rows = LedgerDomain.parseCsv(csv, "CAD", "USD")
        assertEquals(listOf("1234.50", "12,34.50", "20.00"), rows.map { it.input.amount })
        assertTrue(LedgerDomain.validate(rows[1].input).containsKey("amount"))
        assertEquals("expense", rows[2].input.kind)
    }

    @Test fun csvRejectsAmbiguousHeadersBrokenQuotesAndOversizedImports() {
        for (csv in listOf("date,amount\n2026-10-15,1", "date,amount,merchant,payee\n2026-10-15,1,A,B",
            "date,amount,merchant\n2026-10-15,1,\"A", "date,amount,merchant\n2026-10-15,1,\"A\"B")) {
            assertThrows(LedgerException::class.java) { LedgerDomain.parseCsv(csv) }
        }
        assertThrows(LedgerException::class.java) { LedgerDomain.parseCsv("date,amount,merchant\n" + "2026-10-15,1,Shop\n".repeat(201)) }
        assertThrows(LedgerException::class.java) { LedgerDomain.parseCsv("date,amount,merchant\n\n") }
    }

    @Test fun addedCurrenciesUseTheirOwnPrecision() {
        assertEquals(listOf("CAD", "USD", "EUR", "GBP", "CNY", "JPY", "AUD", "HKD", "SGD", "KRW", "MXN", "INR"), LedgerDomain.currencies)
        assertEquals(listOf("JPY", "KRW"), LedgerDomain.currencies.filter { LedgerDomain.decimals(it) == 0 })
        assertEquals(15000L, LedgerDomain.parseMinor("15000", "KRW"))
        assertThrows(LedgerException::class.java) { LedgerDomain.parseMinor("15000.5", "KRW") }
        assertEquals(24999L, LedgerDomain.parseMinor("249.99", "INR"))
        assertEquals("15000", LedgerDomain.formatMinor(15000, "KRW"))
        assertEquals("249.99", LedgerDomain.formatMinor(24999, "MXN"))
        assertEquals(1470L, LedgerDomain.convertMinor(15000, "KRW", "CAD", "0.00098"))
        assertTrue(LedgerDomain.validate(LedgerInput("expense", "15000.5", "KRW", "2026-10-10", "Shop", "Food", ""))
            .getValue("amount").contains("whole KRW"))
    }
}
