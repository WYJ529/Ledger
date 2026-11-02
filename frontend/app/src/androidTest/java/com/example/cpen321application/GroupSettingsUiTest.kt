package com.example.cpen321application

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GroupSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val members = listOf(GroupMember("owner", "Owner", "owner"), GroupMember("member", "Member"))
    private val group = ExpenseGroup("group", "Roommates", "CAD", ownerId = "owner", memberCount = 2)
    private val summary = GroupExpenseSummary(group, members, 1000, 1,
        listOf(BillShare("owner", 500), BillShare("member", 500)), emptyList())

    @Composable private fun Page(content: @Composable ColumnScope.() -> Unit) {
        CPEN321ApplicationTheme {
            Scaffold { insets ->
                Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
            }
        }
    }

    @Test fun settingsIconOpensRosterAndBackReturnsToExpenses() {
        val session = Session("unused-settings-ui-token", Long.MAX_VALUE,
            Account("owner", "owner@example.com", "Owner", listOf("password"), "2026-10-30"))
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = listOf(group)
            override fun summary(token: String, groupId: String) = this@GroupSettingsUiTest.summary
        }
        compose.setContent { Page {
            GroupExpensesScreen(session, {}, {}, reader = reader, initialGroupId = group.id, liveConnection = { _, _, _ -> })
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Group settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Group settings").performClick()
        compose.onNodeWithText("Group settings").assertExists()
        compose.onNodeWithText("Members (2)").assertExists()
        compose.onNodeWithTag("leave-group").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to group expenses").performScrollTo().performClick()
        compose.onNodeWithText("Total group spending").assertExists()
        compose.onNodeWithTag("leave-group").assertDoesNotExist()
    }

    @Test fun ownerMustConfirmRemovalAndCannotRemoveThemselves() {
        var removed: String? = null
        compose.setContent { Page { GroupSettingsScreen(summary, "owner", false, null, { removed = it }, {}) } }
        compose.onNodeWithTag("remove-member-owner").assertDoesNotExist()
        compose.onNodeWithTag("remove-member-member").performScrollTo().performClick()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithTag("remove-member-member").performClick()
        compose.onNodeWithText("Remove member").performClick()
        compose.runOnIdle { assertEquals("member", removed) }
    }

    @Test fun memberCanLeaveWithConfirmationButCannotRemoveAnyone() {
        var left = false
        compose.setContent { Page { GroupSettingsScreen(summary, "member", false, null, {}, { left = true }) } }
        compose.onNodeWithText("Remove").assertDoesNotExist()
        compose.onNodeWithTag("leave-group").performScrollTo().performClick()
        compose.onNodeWithText("Leave Roommates?").assertIsDisplayed()
        compose.runOnIdle { assertFalse(left) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertFalse(left) }
        compose.onNodeWithTag("leave-group").performClick()
        compose.onNodeWithText("Confirm leave").performClick()
        compose.runOnIdle { assertTrue(left) }
    }

    @Test fun ownerMustExplicitlyConfirmIrreversibleGroupDeletion() {
        var left = false
        compose.setContent { Page { GroupSettingsScreen(summary, "owner", false, null, {}, { left = true }) } }
        compose.onNodeWithTag("leave-group").performScrollTo().performClick()
        compose.onNodeWithText("Delete group?").assertIsDisplayed()
        compose.onNodeWithText("You are the owner.", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertFalse(left) }
        compose.onNode(hasText("Delete group") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertTrue(left) }
    }

    @Test fun pendingRequestsDisableMembershipActionsAndErrorsRemainVisible() {
        compose.setContent { Page { GroupSettingsScreen(summary, "owner", true, "Could not save. Try again.", {}, {}) } }
        compose.onNodeWithTag("remove-member-member").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("leave-group").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Could not save. Try again.").assertExists()
    }

    @Test fun summaryStillShowsMoneyAllocatedToFormerMembers() {
        compose.setContent { Page { GroupSummaryContent(summary.copy(members = members.take(1)), "owner") } }
        compose.onNodeWithText("Former members (saved expenses)").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("CAD 5.00").assertCountEquals(2)
    }

    @Test fun unavailableGroupReturnsToTheGroupList() {
        val session = Session("unused-settings-ui-token", Long.MAX_VALUE,
            Account("member", "member@example.com", "Member", listOf("password"), "2026-10-30"))
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = emptyList<ExpenseGroup>()
            override fun summary(token: String, groupId: String): GroupExpenseSummary = throw ApiException(404, "Group not found.")
        }
        compose.setContent { Page { GroupExpensesScreen(session, {}, {}, reader = reader, initialGroupId = group.id,
            liveConnection = { _, _, _ -> }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("No groups yet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("This group is no longer available.").assertExists()
        compose.onNodeWithText("Refresh groups").assertDoesNotExist()
    }

    @Test fun liveChangesRefreshHeadlineAndRosterWithoutDiscardingExpenseDraft() {
        val session = Session("unused-settings-ui-token", Long.MAX_VALUE,
            Account("owner", "owner@example.com", "Owner", listOf("password"), "2026-10-30"))
        val latest = java.util.concurrent.atomic.AtomicReference(summary)
        var callbacks: SplitLiveCallbacks? = null
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = listOf(latest.get().group)
            override fun summary(token: String, groupId: String) = latest.get()
        }
        compose.setContent { Page { GroupExpensesScreen(session, {}, {}, reader = reader, initialGroupId = group.id,
            liveConnection = { _, _, live -> androidx.compose.runtime.SideEffect { callbacks = live } }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Roommates (2)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Members").assertDoesNotExist()
        latest.set(summary.copy(group = group.copy(name = "Dinner", memberCount = 3),
            members = members + GroupMember("third", "Third")))
        compose.runOnIdle { callbacks!!.groupChanged() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Dinner (3)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add expense").performScrollTo().performClick()
        compose.onNodeWithText("Description").performScrollTo().performTextInput("Unsaved dinner")
        compose.onNodeWithText("Final total (CAD)").performScrollTo().performTextInput("23.45")
        latest.set(latest.get().copy(group = group.copy(name = "Dinner renamed", memberCount = 3)))
        compose.runOnIdle { callbacks!!.groupChanged() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("New expense · Dinner renamed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unsaved dinner").assertExists()
        compose.onNodeWithText("23.45").assertExists()
        compose.onNodeWithContentDescription("Paid by Third").assertExists()
    }
}
