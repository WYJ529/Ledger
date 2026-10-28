package com.example.cpen321application

import org.junit.Assert.*
import org.junit.Test

class BillSplittingTest {
    @Test fun mealUsesIndividualExtraRatesAndPretaxTips() {
        val result = calculateMeal(MealCalculation("15", listOf(MealMember("a", 2000), MealMember("b", 3000, "10"))))!!
        assertEquals(5000L, result.subtotal)
        assertEquals(550L, result.tax)
        assertEquals(750L, result.tip)
        assertEquals(listOf(BillShare("a", 2400), BillShare("b", 3900)), result.shares)
        assertEquals(result.total, result.shares.sumOf { it.amountCents })
    }
    @Test fun mealRoundsEachComponentAndValidatesInputs() {
        val result = calculateMeal(MealCalculation("10", listOf(MealMember("b", 5, "10"), MealMember("a", 0))))!!
        assertEquals(listOf(BillShare("a", 0), BillShare("b", 7)), result.shares)
        assertNull(calculateMeal(MealCalculation("101", listOf(MealMember("a", 100)))))
        assertNull(calculateMeal(MealCalculation("0", listOf(MealMember("a", 100, "-1")))))
        assertNull(calculateMeal(MealCalculation("0", listOf(MealMember("a", 0)))))
        assertNull(calculateMeal(MealCalculation("0", listOf(MealMember("a", MAX_BILL_CENTS)))))
        assertNull(calculateMeal(MealCalculation("0", listOf(MealMember("a", 1), MealMember("a", 1)))))
    }
    @Test fun tipsUsePretaxCentsAndRoundHalfUp() {
        assertEquals(0L, tipCents(1000, "0"))
        assertEquals(100L, tipCents(1000, "10"))
        assertEquals(150L, tipCents(1000, "15"))
        assertEquals(200L, tipCents(1000, "20"))
        assertEquals(1L, tipCents(5, "10"))
        assertEquals(175L, tipCents(1000, "17.5"))
        assertEquals(MAX_BILL_CENTS, tipCents(MAX_BILL_CENTS, "100"))
        listOf("-1", "100.01", "1.001", "", "NaN").forEach { assertNull(tipCents(1000, it)) }
        val finalTotal = 1000L + 123L + tipCents(1000, "15")!!
        assertEquals(1273L, equalBillShares(finalTotal, listOf("a", "b", "c")).sumOf { it.amountCents })
    }
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
