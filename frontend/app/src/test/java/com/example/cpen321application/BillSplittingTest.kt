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

    @Test fun percentagesAreExactAndBounded() {
        assertEquals(0, percentageBasisPoints("0"))
        assertEquals(1, percentageBasisPoints("0.01"))
        assertEquals(3333, percentageBasisPoints("33.33"))
        assertEquals(10000, percentageBasisPoints("100.00"))
        listOf("", "-1", "100.01", "1.001", "1e2", "NaN", " 50 ", "50%").forEach {
            assertNull(percentageBasisPoints(it))
        }
    }

    @Test fun percentageRemaindersMatchTheBackendAndIgnoreSelectionOrder() {
        val percentages = listOf("a" to 3333, "b" to 3333, "c" to 3334)
        val expected = listOf(BillShare("a", 333, 3333), BillShare("b", 333, 3333), BillShare("c", 334, 3334))
        assertEquals(expected, percentageBillShares(1000, percentages))
        assertEquals(expected, percentageBillShares(1000, percentages.reversed()))
        assertEquals(listOf(BillShare("a", 1, 5000), BillShare("b", 0, 0), BillShare("c", 0, 5000)),
            percentageBillShares(1, listOf("c" to 5000, "a" to 5000, "b" to 0)))
    }

    @Test fun percentageTotalsRemainExactAtEverySupportedParticipantCount() {
        for (count in 1..100) {
            val percentages = List(count) { index -> "member-$index" to (10000 / count + if (index < 10000 % count) 1 else 0) }
            for (total in listOf(1L, 29L, 1000L, MAX_BILL_CENTS)) {
                val shares = percentageBillShares(total, percentages)
                assertEquals(total, shares.sumOf { it.amountCents })
                shares.forEach { share ->
                    assertTrue(kotlin.math.abs(share.amountCents * 10000 - total * requireNotNull(share.percentageBasisPoints)) < 10000)
                }
            }
        }
        assertEquals("33.33", percentageText(3333))
    }

    @Test fun invalidPercentageAllocationsAreRejected() {
        val invalid = listOf(emptyList(), listOf("a" to 9999), listOf("a" to 10001),
            listOf("a" to -1, "b" to 10001), listOf("a" to 5000, "a" to 5000))
        invalid.forEach { percentages ->
            try {
                percentageBillShares(100, percentages)
                fail("Invalid percentages should be rejected: $percentages")
            } catch (_: IllegalArgumentException) { /* Expected validation failure. */ }
        }
    }
}
