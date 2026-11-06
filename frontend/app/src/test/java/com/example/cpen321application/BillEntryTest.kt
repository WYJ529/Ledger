package com.example.cpen321application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillEntryTest {
    private val row = LedgerImportRow("row", LedgerInput(amount = "24.00", currency = "USD", merchant = "Pasta Place", notes = "Lunch"),
        warnings = listOf("Check the tip."))
    private val receipt = LedgerReceipt("20.00", "1.00", "3.00", listOf(LedgerReceiptItem("Pasta", "2", "10.00")))
    private val members = listOf(GroupMember("owner", "Owner"))

    @Test fun receiptFillsEveryBillField() {
        val bill = billPrefill(row, receipt)
        assertEquals("Pasta Place", bill.description)
        assertEquals("24.00", bill.total)
        assertEquals("1.00", bill.tax)
        assertEquals("3.00", bill.tip)
        assertEquals(listOf(SplitReceiptRow("Pasta", "2", "10.00")), bill.items)
        assertEquals("USD", bill.currency)
        assertEquals(listOf("Check the tip."), bill.warnings)
        assertTrue(bill.hasReceiptDetail)
    }

    @Test fun zeroTaxAndTipLeaveTheOptionalFieldsEmpty() {
        val bill = billPrefill(row, receipt.copy(tax = "0", tip = "0.00", items = emptyList()))
        assertEquals("", bill.tax)
        assertEquals("", bill.tip)
        assertFalse(bill.hasReceiptDetail)
    }

    @Test fun aRowWithoutReceiptDetailOnlyGivesItsTotal() {
        val bill = billPrefill(row, null)
        assertEquals("24.00", bill.total)
        assertFalse(bill.hasReceiptDetail)
    }

    @Test fun personalRowKeepsTheBreakdownInItsNotes() {
        assertEquals("Lunch\nSubtotal 20.00 · Tax 1.00 · Tip 3.00", withReceiptBreakdown(row, receipt).input.notes)
        assertEquals("24.00", withReceiptBreakdown(row, receipt).input.amount)
    }

    @Test fun personalRowStaysAsItIsWithoutTaxOrTip() {
        assertEquals(row, withReceiptBreakdown(row, null))
        assertEquals(row, withReceiptBreakdown(row, receipt.copy(tax = "0", tip = "0")))
    }

    @Test fun itemFormTakesOverABillAndCountsTaxAndTip() {
        val form = GroupSplitForm()
        form.fill(billPrefill(row, receipt).copy(paidBy = "owner"))
        assertTrue(form.startingAnother)
        assertEquals("owner", form.payer)
        assertEquals(2400L, form.expectedTotal)
        assertFalse(form.valid(members))
        form.reviewed = true
        assertTrue(form.valid(members))
        form.tip = "2.00"
        assertFalse(form.valid(members))
        form.tip = "2.x"
        assertNull(form.tipCents)
        assertFalse(form.valid(members))
    }

    @Test fun formSavesAndRestoresTaxAndTip() {
        val form = GroupSplitForm(SavedSplitStart("Dinner", "owner", listOf(SplitItemUnit("1", "Soup", 500, null)), 50, 100))
        assertEquals("6.50", form.receiptTotal)
        assertEquals("0.50", form.tax)
        assertEquals("1.00", form.tip)
        assertTrue(form.valid(members))
    }

    @Test fun allocationsFollowClaimedItems() {
        val units = listOf(SplitItemUnit("1", "Burger", 1200, "a"), SplitItemUnit("2", "Fries", 600, "b"))
        val split = GroupSplitSession("s", "g", "Dinner", 2100, "CAD", "a", units, "open", 2, 2, null, 100, 200)
        assertEquals(1800L, split.subtotalCents)
        assertEquals(mapOf("a" to 1400L, "b" to 700L), splitAllocations(split))
    }

    @Test fun unclaimedItemsKeepTheirShareOfTaxAndTip() {
        val units = listOf(SplitItemUnit("1", "Burger", 1200, "a"), SplitItemUnit("2", "Fries", 600, null))
        val split = GroupSplitSession("s", "g", "Dinner", 2100, "CAD", "a", units, "open", 1, 1, null, 100, 200)
        assertEquals(1400L, splitAllocations(split)["a"])
    }
}
