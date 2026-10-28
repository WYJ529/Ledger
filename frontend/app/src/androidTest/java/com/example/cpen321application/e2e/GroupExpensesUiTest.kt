package com.example.cpen321application.e2e

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
            GroupExpensesScreen(session, onSessionExpired = {}, onExit = { exited = true }, reader = reader)
        } }
        fun waitForText(text: String) {
            compose.waitUntil(10000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
        waitForText("Roommates")
        compose.onNodeWithText("Roommates").performClick()
        waitForText("Add expense")
        compose.onNodeWithText("Add expense").performScrollTo().performClick()
        compose.onNodeWithText("Back to home").assertDoesNotExist()
        compose.onNodeWithText("Back to group expenses").performScrollTo().performClick()
        compose.onNodeWithText("Add expense").assertExists()
        compose.runOnIdle { assertFalse(exited) }

        compose.onNodeWithText("Add expense").performScrollTo().performClick()
        pressBack()
        compose.onNodeWithText("Add expense").assertExists()
        compose.runOnIdle { assertFalse(exited) }
        compose.onNodeWithText("Back to groups").performScrollTo().performClick()
        waitForText("Roommates")
        compose.onNodeWithText("Back to home").performScrollTo().performClick()
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
        compose.onNodeWithText("Final total (CAD)").performTextInput("10.00")
        compose.onNodeWithText("To pay: CAD 3.34").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tip rate · calculated before tax").assertDoesNotExist()
        compose.onNodeWithText("Save expense").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Description").performScrollTo().performTextInput("Dinner")
        compose.onNodeWithText("Save expense").performScrollTo().assertIsEnabled()
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
}