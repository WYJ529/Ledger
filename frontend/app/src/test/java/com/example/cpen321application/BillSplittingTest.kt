package com.example.cpen321application

import org.junit.Assert.*
import org.junit.Test

class BillSplittingTest {
    @Test fun parsesDecimalMoneyExactly() {
        assertEquals(1L, moneyCents("0.01"))
        assertEquals(120L, moneyCents("1.2"))
        assertEquals(MAX_BILL_CENTS, moneyCents("1000000.00"))
        assertEquals(0L, moneyCents("0", true))
        listOf("0", "-1", "1.001", "1e3", " 1 ", "1000000.01", "", "NaN").forEach { assertNull(moneyCents(it)) }
    }
    @Test fun remainderIsStableAcrossSelectionOrder() {
        val result = equalBillShares(1000, listOf("c", "a", "b"))
        assertEquals(listOf(BillShare("a", 334), BillShare("b", 333), BillShare("c", 333)), result)
        assertEquals(result, equalBillShares(1000, listOf("b", "c", "a")))
    }
    @Test fun tinyBillDoesNotLoseCents() {
        assertEquals(listOf(BillShare("a", 1), BillShare("b", 0)), equalBillShares(1, listOf("a", "b")))
        for (count in 1..100) {
            val shares = equalBillShares(1234567, List(count) { "member-$it" })
            assertEquals(1234567L, shares.sumOf { it.amountCents })
            assertTrue(shares.maxOf { it.amountCents } - shares.minOf { it.amountCents } <= 1)
        }
    }
    @Test fun moneyFormattingDoesNotDependOnDeviceLocale() {
        assertEquals("10.01", decimalAmount(1001))
        assertEquals("CAD 0.01", displayMoney("CAD", 1))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsRepeatedMembers() {
        equalBillShares(100, listOf("a", "a"))
    }
}
