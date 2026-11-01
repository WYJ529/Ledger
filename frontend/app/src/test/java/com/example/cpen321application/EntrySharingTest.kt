package com.example.cpen321application

import org.junit.Assert.*
import org.junit.Test

class EntrySharingTest {
    private val group = ExpenseGroup("group", "Roommates", "CAD")
    private val record = LedgerRecord("record", LedgerInput(amount = "12.00", merchant = "Dinner"), 1200,
        LedgerFx("CAD", "1", "2026-01-01", "Same currency", 1200), createdAt = "now", updatedAt = "now")

    @Test fun sharingUsesOriginalCurrencyAndGroupAmountLimit() {
        assertNull(shareProblem(listOf(record), group))
        assertNotNull(shareProblem(listOf(record.copy(input = record.input.copy(kind = "income"))), group))
        assertNotNull(shareProblem(listOf(record.copy(input = record.input.copy(currency = "USD"))), group))
        assertNotNull(shareProblem(listOf(record.copy(amountMinor = 100_000_001)), group))
        assertNull(shareProblem(listOf(record.copy(amountMinor = 100_000_000)), group))
        assertNotNull(shareProblem(listOf(record, record.copy(input = record.input.copy(currency = "JPY"))), group))
    }

    @Test fun zeroDecimalCurrencySharesAreAllowedWithoutUsingReportingCurrency() {
        val yen = record.copy(input = record.input.copy(currency = "JPY", amount = "1200"))
        assertNull(shareProblem(listOf(yen), group.copy(currency = "JPY")))
        assertEquals("1200", LedgerDomain.formatMinor(yen.amountMinor, yen.input.currency))
    }
}
