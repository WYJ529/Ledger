package com.example.cpen321application.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.cpen321application.*
import androidx.test.espresso.Espresso.pressBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real Compose form/summary behavior on device; saving/persistence is covered by API integration tests. */
class GroupExpensesUiTest {
    @get:Rule val compose = createComposeRule()
    private val members = listOf(GroupMember("a", "Joy"), GroupMember("b", "Alex"), GroupMember("c", "Sam"))
    private val bill = GroupBill("bill-1", "Dinner", 1000, "a", "equal", "2026-10-18T10:00:00Z",
        listOf(BillShare("a", 334), BillShare("b", 333), BillShare("c", 333)))
    private val summary = GroupExpenseSummary(ExpenseGroup("group-1", "Roommates", "CAD"), members, 1000, 1, bill.shares, listOf(bill))
    private val session = Session("unused-ui-test-token", Long.MAX_VALUE, Account("a", "joy@example.com", "Joy", listOf("password"), "2026-10-18"))

    @Composable private fun TestPage(content: @Composable ColumnScope.() -> Unit) {
        MaterialTheme {
            Scaffold { insets ->
                Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
            }
        }
    }

    @Test fun buttonAndSystemBackReturnOneLevelAtATime() {
        var exited = false
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = listOf(summary.group)
            override fun summary(token: String, groupId: String) = this@GroupExpensesUiTest.summary
        }
        compose.setContent { TestPage {
            GroupExpensesScreen(session, GroupScreenCallbacks(onSessionExpired = {}, onExit = { exited = true }), services = GroupScreenServices(reader))
        } }
        fun waitForText(text: String) {
            compose.waitUntil(10000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
        waitForText("Roommates")
        compose.onNodeWithText("Roommates").performClick()
        waitForText("+ Add a bill")
        compose.onNodeWithText("+ Add a bill").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back to home").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to group expenses").performScrollTo().performClick()
        compose.onNodeWithText("+ Add a bill").assertExists()
        compose.runOnIdle { assertFalse(exited) }

        compose.onNodeWithText("+ Add a bill").performScrollTo().performClick()
        pressBack()
        compose.onNodeWithText("+ Add a bill").assertExists()
        compose.runOnIdle { assertFalse(exited) }
        compose.onNodeWithContentDescription("Back to groups").performScrollTo().performClick()
        waitForText("Roommates")
        pressBack()
        compose.runOnIdle { assertTrue(exited) }
    }

    @Test fun summaryAndExpandableBillShowSavedShares() {
        compose.setContent { TestPage { GroupSummaryContent(summary, "a"); BillCard(bill, summary) } }
        compose.onAllNodesWithText("CAD 10.00").assertCountEquals(2)
        compose.onNodeWithText("Your allocated share: CAD 3.34").assertExists()
        compose.onNodeWithText("View split").performScrollTo().performClick()
        compose.onNodeWithText("Alex: CAD 3.33").performScrollTo().assertIsDisplayed()
    }

    @Test fun equalSplitUsesOnlyFinalTotal() {
        compose.setContent { TestPage { BillEditor(summary, session, {}, {}) } }
        compose.onNodeWithText("Equal + adjustments", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Equal split", substring = false).assertExists()
        compose.onNodeWithText("Custom amounts", substring = false).assertExists()
        compose.onNodeWithText("Percentage", substring = false).assertExists()
        compose.onNodeWithText("Final total (CAD)").performTextInput("10.00")
        compose.onNodeWithText("To pay: CAD 3.34").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tip rate · calculated before tax").assertDoesNotExist()
        compose.onNodeWithText("Save expense").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Description").performScrollTo().performTextInput("Dinner")
        compose.onNodeWithText("Save expense").performScrollTo().assertIsEnabled()
    }

    @Test fun typedTaxAndTipMustLeaveASubtotal() {
        compose.setContent { TestPage { BillEditor(summary, session, {}, {}) } }
        compose.onNodeWithText("Description").performTextInput("Dinner")
        compose.onNodeWithText("Final total (CAD)").performTextInput("10.00")
        compose.onNodeWithText("Tax (optional)").performScrollTo().performTextInput("6.00")
        compose.onNodeWithText("Tip (optional)").performScrollTo().performTextInput("4.00")
        compose.onNodeWithText("Tax and tip must be valid and smaller than the total.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save expense").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Tip (optional)").performScrollTo().performTextReplacement("1.00")
        compose.onNodeWithText("Save expense").performScrollTo().assertIsEnabled()
    }

    @Test fun receiptFillsTheEditorAndCustomAmountsShareItsTaxAndTip() {
        val receipt = BillPrefill("Bistro", "66.00", "3.00", "3.00", listOf(SplitReceiptRow("Pasta", "1", "20.00")), "CAD")
        compose.setContent { TestPage { BillEditor(summary, session, {}, {}, prefill = receipt) } }
        compose.onNodeWithText("Bistro").assertExists()
        compose.onNodeWithText("Custom amounts").performClick()
        compose.onNodeWithText("Final total (CAD)").performScrollTo().assertTextContains("66.00")
        compose.onNodeWithText("Tip rate · calculated before tax").assertDoesNotExist()
        compose.onNodeWithText("Pretax amount · Joy").performScrollTo().performTextInput("20.00")
        compose.onNodeWithText("Pretax amount · Alex").performScrollTo().performTextInput("40.00")
        compose.onNodeWithText("Pretax amount · Sam").performScrollTo().performTextInput("0")
        compose.onNodeWithText("Assigned before tax: CAD 60.00 of CAD 60.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("To pay: CAD 22.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("To pay: CAD 44.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save expense").performScrollTo().assertIsEnabled()
    }

    @Test fun receiptInAnotherCurrencyIsFlagged() {
        val receipt = BillPrefill("Bistro", "66.00", currency = "USD", warnings = listOf("Check the tip."))
        compose.setContent { TestPage { BillEditor(summary, session, {}, {}, prefill = receipt) } }
        compose.onNodeWithText("This receipt is in USD", substring = true).assertExists()
        compose.onNodeWithText("Review: Check the tip.").assertExists()
    }

    @Test fun byItemHandsTheBillToLetsGoDutch() {
        val owned = summary.copy(group = summary.group.copy(ownerId = session.account.id))
        val receipt = BillPrefill("Bistro", "66.00", "3.00", items = listOf(SplitReceiptRow("Pasta", "1", "20.00")))
        var handed: BillPrefill? = null
        compose.setContent { TestPage { BillEditor(owned, session, {}, {}, prefill = receipt, onByItem = { handed = it }) } }
        compose.onNodeWithText("Tip (optional)").performScrollTo().performTextInput("2.00")
        compose.onNodeWithText("Let’s Go Dutch").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("2.00", handed?.tip)
            assertEquals(receipt.items, handed?.items)
        }
    }

    @Test fun onlyTheOwnerCanSplitByItem() {
        val shared = summary.copy(group = summary.group.copy(ownerId = "someone-else"))
        compose.setContent { TestPage { BillEditor(shared, session, {}, {}, onByItem = {}) } }
        compose.onNodeWithText("Let’s Go Dutch").assertIsNotEnabled()
        compose.onNodeWithText("Only the group owner can start Let’s Go Dutch.").assertExists()
    }

    @Test fun savedExpensesCannotBecomeAByItemSplit() {
        compose.setContent { TestPage { BillEditor(summary, session, {}, {}, bill = bill) } }
        compose.onNodeWithText("Let’s Go Dutch").assertDoesNotExist()
    }

    @Test fun addBillOpensTheSharedEntryForThisGroup() {
        var opened: ExpenseGroup? = null
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = listOf(summary.group)
            override fun summary(token: String, groupId: String) = this@GroupExpensesUiTest.summary
        }
        compose.setContent { TestPage {
            GroupExpensesScreen(session, GroupScreenCallbacks(onSessionExpired = {}, onExit = {}, onAddBill = { opened = it }),
                GroupScreenEntry(summary.group.id), GroupScreenServices(reader))
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("+ Add a bill").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("+ Add a bill").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(summary.group, opened) }
    }

    private fun split(description: String) = GroupSplitSession("split-$description", summary.group.id, description, 600, "CAD", "a",
        listOf(SplitItemUnit("1", "Soup", 600, null)), "open", 0, 1, null)

    private fun openGroupWith(splits: List<GroupSplitSession>) {
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = listOf(summary.group)
            override fun summary(token: String, groupId: String) = this@GroupExpensesUiTest.summary
            override fun openSplits(token: String, groupId: String) = splits
        }
        compose.setContent { TestPage {
            GroupExpensesScreen(session, GroupScreenCallbacks(onSessionExpired = {}, onExit = {}),
                GroupScreenEntry(summary.group.id), GroupScreenServices(reader, liveConnection = { _, _, _ -> }))
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("+ Add a bill").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun groupPageHasNoLetsGoDutchEntryWithoutAnOpenSplit() {
        openGroupWith(emptyList())
        compose.onNodeWithText("Let’s Go Dutch").assertDoesNotExist()
        compose.onNodeWithText("Claim items", substring = true).assertDoesNotExist()
    }

    @Test fun openSplitStaysReachableForClaims() {
        openGroupWith(listOf(split("Dinner")))
        compose.waitUntil(10000) { compose.onAllNodesWithText("Claim items · Dinner").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("+ Add a bill").assertExists()
    }

    @Test fun severalOpenSplitsShareOneEntry() {
        openGroupWith(listOf(split("Dinner"), split("Lunch")))
        compose.waitUntil(10000) { compose.onAllNodesWithText("Claim items · 2 open splits").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun importedShareIsGrayedOut() {
        var opened = false
        compose.setContent { TestPage { BillCard(bill.copy(importedByMe = true), summary, onImport = { opened = true }) } }
        compose.onNodeWithText("Imported").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithText("Import my share").assertDoesNotExist()
        compose.runOnIdle { assertFalse(opened) }
    }

    @Test fun shareNotYetImportedCanBeImported() {
        compose.setContent { TestPage { BillCard(bill, summary, onImport = {}) } }
        compose.onNodeWithText("Import my share").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Imported").assertDoesNotExist()
    }

    @Test fun localReceiptGraysTheButtonAndRepeatsTheServerMark() {
        val marks = mutableListOf<String>()
        val mine = bill.copy(shares = listOf(BillShare(session.account.id, 1000)))
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = listOf(summary.group)
            override fun summary(token: String, groupId: String) = this@GroupExpensesUiTest.summary.copy(expenses = listOf(mine))
        }
        compose.setContent { TestPage {
            GroupExpensesScreen(session, GroupScreenCallbacks(onSessionExpired = {}, onExit = {}), GroupScreenEntry(summary.group.id),
                GroupScreenServices(reader, liveConnection = { _, _, _ -> }, localImports = { _, _, _, ids -> ids.toSet() },
                    markImported = { _, _, id -> synchronized(marks) { marks.add(id) } }))
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Imported").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Imported").performScrollTo().assertIsNotEnabled()
        compose.waitUntil(10000) { synchronized(marks) { mine.id in marks } }
    }

    private class SlowReader(private val base: GroupExpenseSummary) : GroupExpenseReader {
        @Volatile var fail = false
        @Volatile var gate: java.util.concurrent.CountDownLatch? = null
        override fun groups(token: String) = listOf(base.group)
        override fun summary(token: String, groupId: String): GroupExpenseSummary {
            gate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (fail) throw java.io.IOException("offline")
            return base
        }
    }

    private fun openLiveGroup(reader: GroupExpenseReader): () -> SplitLiveCallbacks? {
        var live: SplitLiveCallbacks? = null
        compose.setContent { TestPage {
            GroupExpensesScreen(session, GroupScreenCallbacks(onSessionExpired = {}, onExit = {}), GroupScreenEntry(summary.group.id),
                GroupScreenServices(reader, liveConnection = { _, _, callbacks -> live = callbacks }))
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("+ Add a bill").fetchSemanticsNodes().isNotEmpty() }
        return { live }
    }

    @Test fun slowRefreshKeepsTheGroupAndOnlyShowsRefreshing() {
        val reader = SlowReader(summary)
        val live = openLiveGroup(reader)
        val gate = java.util.concurrent.CountDownLatch(1)
        reader.gate = gate
        compose.runOnIdle { live()?.groupChanged?.invoke() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Refreshing…").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("+ Add a bill").assertExists()
        compose.onNodeWithText("Total group spending").assertExists()
        gate.countDown()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Refreshing…").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("+ Add a bill").assertExists()
    }

    @Test fun failedRefreshKeepsTheGroupAndOffersRetry() {
        val reader = SlowReader(summary)
        val live = openLiveGroup(reader)
        reader.fail = true
        compose.runOnIdle { live()?.groupChanged?.invoke() }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Couldn't refresh. Showing the last update.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("+ Add a bill").assertExists()
        compose.onNodeWithText("Total group spending").assertExists()
        reader.fail = false
        compose.onNodeWithTag("live-status").onChildren().filterToOne(hasText("Retry")).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Couldn't refresh. Showing the last update.").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun firstLoadFailureStillShowsTheErrorWithRetry() {
        val reader = SlowReader(summary).apply { fail = true }
        compose.setContent { TestPage {
            GroupExpensesScreen(session, GroupScreenCallbacks(onSessionExpired = {}, onExit = {}), GroupScreenEntry(summary.group.id),
                GroupScreenServices(reader, liveConnection = { _, _, _ -> }))
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("offline").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("+ Add a bill").assertDoesNotExist()
        reader.fail = false
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("+ Add a bill").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun billFromTheEntryOpensItsGroupEditor() {
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = listOf(summary.group)
            override fun summary(token: String, groupId: String) = this@GroupExpensesUiTest.summary
        }
        compose.setContent { TestPage {
            GroupExpensesScreen(session, GroupScreenCallbacks(onSessionExpired = {}, onExit = {}),
                GroupScreenEntry(summary.group.id, bill = BillPrefill("Bistro", "66.00")), GroupScreenServices(reader))
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Bistro").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Final total (CAD)").performScrollTo().assertTextContains("66.00")
    }

    @Test fun customMealSupportsIndividualExtraTaxAndPreservesOnModeSwitch() {
        compose.setContent { TestPage { BillEditor(summary, session, {}, {}) } }
        compose.onNodeWithText("Description").performTextInput("Dinner")
        compose.onNodeWithText("Custom amounts").performClick()
        compose.onNodeWithText("Final total (CAD)").assertDoesNotExist()
        compose.onNodeWithText("15%").performScrollTo().performClick()
        compose.onNodeWithText("Pretax amount · Joy").performScrollTo().performTextInput("20.00")
        compose.onNodeWithText("Pretax amount · Alex").performScrollTo().performTextInput("30.00")
        compose.onNodeWithText("Pretax amount · Sam").performScrollTo().performTextInput("0")
        compose.onNodeWithContentDescription("Extra tax for Alex").performScrollTo().performClick()
        compose.onNodeWithText("Extra tax % · Alex").performScrollTo().performTextReplacement("10")
        compose.onNodeWithText("Total: CAD 63.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save expense").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Equal split").performScrollTo().performClick()
        compose.onNodeWithText("Custom amounts").performScrollTo().performClick()
        compose.onNodeWithText("Extra tax % · Alex").performScrollTo().assertTextContains("10")
        compose.onNodeWithText("Total: CAD 63.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Extra tax for Alex").performScrollTo().performClick()
        compose.onNodeWithText("Total: CAD 60.00").performScrollTo().assertIsDisplayed()
    }

    @Test fun savedMealReopensWithOriginalPretaxAndRates() {
        val meal = MealCalculation("15.00", listOf(MealMember("a", 2000), MealMember("b", 3000, "10.00")))
        val existing = bill.copy(splitMode = "custom", amountCents = 6300, subtotalCents = 5000, taxCents = 550, tipCents = 750,
            shares = listOf(BillShare("a", 2400), BillShare("b", 3900)), customCalculation = meal)
        compose.setContent { TestPage { BillEditor(summary, session, {}, {}, bill = existing) } }
        compose.onNodeWithText("Pretax amount · Joy").performScrollTo().assertTextContains("20.00")
        compose.onNodeWithText("Extra tax % · Alex").performScrollTo().assertTextContains("10.00")
        compose.onNodeWithText("Total: CAD 63.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save changes").performScrollTo().assertIsEnabled()
    }

    @Test fun legacyCustomSharesRequireExplicitConversion() {
        val existing = bill.copy(splitMode = "custom", canEdit = true, revision = 3)
        compose.setContent { TestPage { BillEditor(summary, session, {}, {}, bill = existing) } }
        compose.onNodeWithText("Final total (CAD)").assertTextContains("10.00")
        compose.onAllNodesWithText("Saved final share (CAD)")[0].performScrollTo().assertTextContains("3.34")
        compose.onNodeWithText("Save changes").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Recalculate from pretax amounts").performScrollTo().performClick()
        compose.onNodeWithText("Save changes").performScrollTo().assertIsNotEnabled()
    }

    @Test fun percentageSplitRequiresOneHundredPercentAndShowsCanonicalAmounts() {
        compose.setContent { TestPage { BillEditor(summary, session, {}, {}) } }
        compose.onNodeWithText("Description").performTextInput("Percentage dinner")
        compose.onNodeWithText("Final total (CAD)").performTextInput("10.00")
        compose.onNodeWithText("Percentage", substring = false).performScrollTo().performClick()
        compose.onAllNodesWithText("Share (%)")[0].performScrollTo().performTextInput("33.33")
        compose.onAllNodesWithText("Share (%)")[1].performScrollTo().performTextInput("33.33")
        compose.onAllNodesWithText("Share (%)")[2].performScrollTo().performTextInput("33.33")
        compose.onNodeWithText("Save expense").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Assigned: 99.99% / 100.00%").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Share (%)")[2].performScrollTo().performTextReplacement("33.34")
        compose.onNodeWithText("Save expense").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("To pay: CAD 3.34").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("To pay: CAD 3.33").assertCountEquals(2)
        compose.onAllNodesWithText("Share (%)")[0].performScrollTo().performTextReplacement("-1")
        compose.onNodeWithText("Save expense").performScrollTo().assertIsNotEnabled()
    }

    @Test fun percentageEditKeepsOriginalPercentagesWhenChangingTheTotal() {
        val existing = bill.copy(splitMode = "percentage", canEdit = true, shares = listOf(
            BillShare("a", 333, 3333), BillShare("b", 333, 3333), BillShare("c", 334, 3334)))
        compose.setContent { TestPage { BillEditor(summary, session, {}, {}, bill = existing) } }
        compose.onAllNodesWithText("Share (%)")[0].performScrollTo().assertTextContains("33.33")
        compose.onAllNodesWithText("Share (%)")[2].performScrollTo().assertTextContains("33.34")
        compose.onNodeWithText("Final total (CAD)").performScrollTo().performTextReplacement("12.00")
        compose.onAllNodesWithText("To pay: CAD 4.00").assertCountEquals(3)
        compose.onNodeWithText("Save changes").performScrollTo().assertIsEnabled()
    }

    @Test fun percentageBillDetailsShowStoredPercentages() {
        val existing = bill.copy(splitMode = "percentage", shares = listOf(BillShare("a", 1000, 10000)))
        compose.setContent { TestPage { BillCard(existing, summary) } }
        compose.onNodeWithText("Paid by Joy · Percentage split").assertExists()
        compose.onNodeWithText("View split").performClick()
        compose.onNodeWithText("Joy: CAD 10.00 · 100.00%").assertIsDisplayed()
    }

    @Test fun adjustedSplitKeepsSavedAdjustmentsAndRejectsExcessiveAmounts() {
        val existing = bill.copy(splitMode = "adjusted", shares = listOf(
            BillShare("a", 400, adjustmentCents = 100), BillShare("b", 300, adjustmentCents = 0), BillShare("c", 300, adjustmentCents = 0)))
        compose.setContent { TestPage { BillEditor(summary, session, {}, {}, bill = existing) } }
        compose.onAllNodesWithText("Adjustment (CAD)")[0].performScrollTo().assertTextContains("1.00")
        compose.onNodeWithText("To pay: CAD 4.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save changes").performScrollTo().assertIsEnabled()
        compose.onAllNodesWithText("Adjustment (CAD)")[0].performScrollTo().performTextReplacement("11.00")
        compose.onNodeWithText("Save changes").performScrollTo().assertIsNotEnabled()
    }

    @Test fun deleteButtonAppearsOnlyOnExpensesTheMemberMayChange() {
        compose.setContent { TestPage {
            BillCard(bill.copy(id = "mine", description = "Mine", canEdit = true), summary, onEdit = {}, onDelete = {})
            BillCard(bill.copy(id = "theirs", description = "Theirs", canEdit = false), summary, onEdit = {}, onDelete = {})
        } }
        compose.onAllNodesWithText("Delete expense").assertCountEquals(1)
        compose.onAllNodesWithText("Edit expense").assertCountEquals(1)
    }

    @Test fun deleteAsksForConfirmationThenRemovesTheExpense() {
        val editable = bill.copy(canEdit = true, revision = 3)
        var current = summary.copy(expenses = listOf(editable))
        val deleted = mutableListOf<Triple<String, String, Int>>()
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = listOf(summary.group)
            override fun summary(token: String, groupId: String) = current
        }
        compose.setContent { TestPage {
            GroupExpensesScreen(session, GroupScreenCallbacks({}, {}), GroupScreenEntry(summary.group.id), GroupScreenServices(reader, liveConnection = { _, _, _ -> },
                deleteBill = { _, groupId, billId, revision ->
                    deleted += Triple(groupId, billId, revision)
                    current = current.copy(expenses = emptyList(), expenseCount = 0, totalCents = 0)
                }))
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Delete expense").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Delete expense").performScrollTo().performClick()
        compose.onNodeWithText("Delete \"Dinner\" (CAD 10.00) for all members? This cannot be undone.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Delete expense?").assertDoesNotExist()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }

        compose.onNodeWithText("Delete expense").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Expense deleted.").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf(Triple("group-1", "bill-1", 3)), deleted) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Dinner").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Delete expense?").assertDoesNotExist()
    }

    @Test fun deleteConflictKeepsTheExpenseAndReloadsIt() {
        var reads = 0
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = listOf(summary.group)
            override fun summary(token: String, groupId: String) = summary.copy(expenses = listOf(bill.copy(canEdit = true))).also { reads++ }
        }
        compose.setContent { TestPage {
            GroupExpensesScreen(session, GroupScreenCallbacks({}, {}), GroupScreenEntry(summary.group.id), GroupScreenServices(reader, liveConnection = { _, _, _ -> },
                deleteBill = { _, _, _, _ -> throw ApiException(409, "This expense changed. Review it before deleting.") }))
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Delete expense").fetchSemanticsNodes().isNotEmpty() }
        val readsBefore = compose.runOnIdle { reads }

        compose.onNodeWithText("Delete expense").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("This expense changed. Review it before deleting.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10000) { reads > readsBefore }
        compose.onNodeWithText("Delete expense?").assertExists()
        compose.onAllNodesWithText("Dinner").assertCountEquals(1)
    }
}
