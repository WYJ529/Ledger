package com.example.cpen321application.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.cpen321application.BillPrefill
import com.example.cpen321application.SplitItemUnit
import com.example.cpen321application.SplitReceiptRow
import com.example.cpen321application.decodeSplitStart
import com.example.cpen321application.encodeSplitStart
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** org.json is only available on a device, so saved forms are checked here. */
@RunWith(AndroidJUnit4::class)
class BillEntryStateTest {
    @Test fun billSurvivesItsSavedForm() {
        val bill = BillPrefill("Bistro", "24.00", "1.00", "3.00", listOf(SplitReceiptRow("Pasta", "2", "10.00")), "USD", "owner", listOf("Check the tip."))
        assertEquals(bill, BillPrefill.fromJson(bill.toJson()))
    }

    @Test fun pendingStartKeepsTaxAndTip() {
        val start = decodeSplitStart(encodeSplitStart("Dinner", "owner", listOf(SplitItemUnit("1", "Soup", 500, null)), 50, 100))
        assertEquals(50L, start.taxCents)
        assertEquals(100L, start.tipCents)
    }

    @Test fun olderPendingStartHasNoTaxOrTip() {
        val start = decodeSplitStart("""{"description":"Dinner","paidBy":"owner","units":[]}""")
        assertEquals(0L, start.taxCents)
        assertEquals(0L, start.tipCents)
    }
}
