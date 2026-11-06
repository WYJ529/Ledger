package com.example.cpen321application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SharedRulesTest {
    @Test fun ledgerAndGroupMoneyHaveIdenticalBoundaries() {
        for (currency in listOf("CAD", "JPY")) {
            val digits = Money.digits(currency)
            for (minor in listOf(1L, 29L, Money.MAX_MINOR)) {
                val text = " \t" + Money.format(minor, currency) + " \n"
                assertEquals(Money.requireMinor(text, currency), Money.parse(text, digits = digits))
            }
            for (text in listOf("", "   ", "0", "-1", "+1", "1e2", "1,000", "1.001", Money.format(Money.MAX_MINOR + 1, digits))) {
                assertNull(Money.parse(text, digits = digits))
                assertThrows(LedgerException::class.java) { Money.requireMinor(text, currency) }
            }
            assertEquals(0L, Money.parse(" 0 ", true, digits))
            assertEquals(0L, Money.optional(" \t", digits))
            assertNull(Money.optional("-1", digits))
            assertEquals(0L, adjustmentCents("   ", digits))
            assertEquals(if (digits == 0) -1L else -100L, adjustmentCents(" −1 ", digits))
            assertNull(adjustmentCents("- 1", digits))
        }
        assertEquals("-0.01", Money.format(-1))
        assertEquals("JPY 1", Money.display(1, "JPY"))
        assertEquals("CAD -0.01", Money.display(-1, "CAD"))
        assertEquals(5000, percentageBasisPoints(" 50 "))
    }

    @Test fun largeWeightedProductsKeepEveryMinorUnit() {
        assertEquals(mapOf("b" to 500000000000L, "a" to 500000000000L),
            proportionalShares(mapOf("b" to 333333333333L, "a" to 333333333333L), 333333333334L))
        assertEquals(listOf(666700000000L, 333300000000L),
            percentageBillShares(Money.MAX_MINOR, listOf("b" to 3333, "a" to 6667)).map { it.amountCents })
        val meal = requireNotNull(calculateMeal(MealCalculation("33.33", listOf(MealMember("a", 500000000001L)))))
        assertEquals(691650000001L, meal.total)
    }

    @Test fun datesAndRequestKeysUseSharedBoundaries() {
        val today = LocalDate.of(2026, 11, 6)
        listOf("2024-02-29", "1999-01-04", "2026-11-06").forEach { assertTrue(validLedgerDate(it, today)) }
        listOf("2025-02-29", "1999-01-03", "2026-11-07", "2026-1-01").forEach { assertFalse(validLedgerDate(it, today)) }
        assertFalse(validLedgerDate("2026-10-29", today, today.minusDays(7)))
        listOf("a".repeat(16), "Z_9-".repeat(20)).forEach { LedgerRequestRules.validateRequestId(it) }
        listOf("", "a".repeat(15), "a".repeat(81), "a".repeat(15) + ".").forEach {
            assertThrows(LedgerException::class.java) { LedgerRequestRules.validateRequestId(it) }
        }
    }

    @Test fun requestStateSerializesWorkAndReleasesOnCancellation() = runBlocking {
        val state = RequestState()
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { state.run { release.await() } }
        assertTrue(state.working)
        var duplicated = false
        state.run { duplicated = true }
        assertFalse(duplicated)
        first.cancel()
        first.join()
        assertFalse(state.working)
        assertNull(state.error)
        var expired = false
        state.run(onExpired = { expired = true }) { throw ApiException(401, "Expired") }
        assertTrue(expired)
        state.run { throw ApiException(401, "Wrong password") }
        assertEquals("Wrong password", state.error)
        try { state.run { throw CancellationException("cancel") }; fail("Cancellation swallowed") }
        catch (_: CancellationException) { assertFalse(state.working) }
    }
}
