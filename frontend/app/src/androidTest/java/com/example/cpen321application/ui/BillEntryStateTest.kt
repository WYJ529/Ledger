package com.example.cpen321application.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.cpen321application.BillPrefill
import com.example.cpen321application.SplitItemUnit
import com.example.cpen321application.SplitReceiptRow
import com.example.cpen321application.decodeSplitStart
import com.example.cpen321application.SavedSplitStart
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
        val original = SavedSplitStart("Dinner · 晚餐", "owner", listOf(SplitItemUnit("1", "Soup", 999999999850, null)), 50, 100)
        val start = decodeSplitStart(original.toJson().toString())
        assertEquals(original, start)
        val request = start.toJson().put("reviewed", true).put("requestId", "start-request-0001")
            .put("amountCents", start.units.sumOf { it.amountCents } + start.taxCents + start.tipCents)
        assertEquals(original, decodeSplitStart(request.toString()))
        assertEquals(1_000_000_000_000L, request.getLong("amountCents"))
    }

    @Test fun olderPendingStartHasNoTaxOrTip() {
        val start = decodeSplitStart("""{"description":"Dinner","paidBy":"owner","units":[]}""")
        assertEquals(0L, start.taxCents)
        assertEquals(0L, start.tipCents)
    }
}
