package com.example.cpen321application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSplitRulesTest {
    @Test fun `duplicate and out of order events never roll state back`() {
        assertEquals(SplitUpdateDecision.Ignore, splitUpdateDecision(7, 7))
        assertEquals(SplitUpdateDecision.Ignore, splitUpdateDecision(7, 6))
    }

    @Test fun `only the next sequence applies without recovery`() {
        assertEquals(SplitUpdateDecision.Apply, splitUpdateDecision(7, 8))
        assertEquals(SplitUpdateDecision.Recover, splitUpdateDecision(7, 9))
        assertEquals(SplitUpdateDecision.Recover, splitUpdateDecision(null, 8))
        assertEquals(SplitUpdateDecision.Recover, splitUpdateDecision(7, 0))
    }

    @Test fun `a delayed HTTP response cannot roll back a newer push update or finalization fence`() {
        assertFalse(shouldReplaceSplitState(9, "open", 8, "open"))
        assertFalse(shouldReplaceSplitState(9, "finalizing", 9, "open"))
        assertTrue(shouldReplaceSplitState(9, "finalizing", 10, "finalized"))
        assertTrue(shouldReplaceSplitState(9, "open", 9, "finalizing"))
    }

    @Test fun `receipt reconciliation uses integer cents including tax and tip lines`() {
        assertTrue(validSplitReceiptTotal("10.03", listOf(333, 333, 333, 4)))
        assertFalse(validSplitReceiptTotal("10.00", listOf(333, 333, 333, 4)))
        assertFalse(validSplitReceiptTotal("1.00", listOf(100, 0)))
        assertFalse(validSplitReceiptTotal("1.00", listOf(101, -1)))
        assertFalse(validSplitReceiptTotal("0.00", emptyList()))
    }

    @Test fun `session creation respects unit and total limits`() {
        assertTrue(validSplitReceiptTotal("2.00", List(200) { 1L }))
        assertFalse(validSplitReceiptTotal("2.01", List(201) { 1L }))
        assertFalse(validSplitReceiptTotal("1000000.01", listOf(MAX_BILL_CENTS, 1)))
    }
}
