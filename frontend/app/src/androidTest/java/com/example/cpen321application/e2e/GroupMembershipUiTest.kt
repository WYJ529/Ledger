package com.example.cpen321application.e2e

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.cpen321application.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real screen composition with deterministic live delivery; real sockets are covered by backend tests. */
class GroupMembershipUiTest {
    @get:Rule val compose = createComposeRule()
    private val members = listOf(GroupMember("a", "Joy", "owner"), GroupMember("b", "Alex"))
    private val group = ExpenseGroup("group-1", "Roommates", "CAD", ownerId = "a", memberCount = 2)
    private val summary = GroupExpenseSummary(group, members, 0, 0, emptyList(), emptyList())
    private val current = AtomicReference(summary)
    private val readSummary = AtomicReference<() -> GroupExpenseSummary>({ current.get() })
    private val session = Session("unused-ui-test-token", Long.MAX_VALUE,
        Account("a", "joy@example.com", "Joy", listOf("password"), "2026-10-18"))
    private lateinit var live: SplitLiveCallbacks
    private var subscriptions = 0

    private fun showGroup() {
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = listOf(current.get().group)
            override fun summary(token: String, groupId: String) = readSummary.get().invoke()
        }
        val connection: @Composable (String, String, SplitLiveCallbacks) -> Unit = { token, id, callbacks ->
            SideEffect { live = callbacks }
            DisposableEffect(token, id) {
                subscriptions++
                callbacks.subscribed()
                onDispose { subscriptions-- }
            }
        }
        compose.setContent { MaterialTheme { Scaffold { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                GroupExpensesScreen(session, {}, {}, reader = reader, initialGroupId = group.id, liveConnection = connection)
            }
        } } }
        waitFor("Add expense")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun joined(reorder: Boolean = false) {
        val added = GroupMember("c", "Taylor")
        current.set(summary.copy(group = group.copy(memberCount = 3),
            members = if (reorder) listOf(added, members[1], members[0]) else members + added))
        compose.runOnIdle { live.membershipChanged(GroupMembershipChange.Joined) }
    }

    @Test fun updatesOpenMembersDialogAndCountWithoutManualRefresh() {
        showGroup()
        compose.onNodeWithText("Members (2)").performScrollTo().performClick()
        joined()
        waitFor("Taylor")
        compose.onAllNodesWithText("Taylor").assertCountEquals(2)
        compose.onNodeWithText("Done").performClick()
        waitFor("Members (3)")
        compose.onNodeWithText("0 expenses · 3 members").assertExists()
        compose.runOnIdle { live.membershipChanged(GroupMembershipChange.Joined) }
        waitFor("Members (3)")
    }

    @Test fun preservesCustomDraftTaxTipAndPayerAcrossJoinAndRosterReordering() {
        showGroup()
        compose.onNodeWithText("Add expense").performScrollTo().performClick()
        compose.onNodeWithText("Description").performTextInput("Unfinished dinner")
        compose.onNodeWithText("Custom amounts").performClick()
        compose.onNodeWithText("15%").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Paid by Alex").performScrollTo().performClick()
        compose.onNodeWithText("Pretax amount · Joy").performScrollTo().performTextInput("20.00")
        compose.onNodeWithText("Pretax amount · Alex").performScrollTo().performTextInput("30.00")
        compose.onNodeWithContentDescription("Extra tax for Alex").performScrollTo().performClick()
        compose.onNodeWithText("Extra tax % · Alex").performScrollTo().performTextReplacement("10")
        joined(reorder = true)
        waitFor("Taylor")
        compose.onNodeWithText("Description").performScrollTo().assertTextContains("Unfinished dinner")
        compose.onNodeWithText("Pretax amount · Alex").performScrollTo().assertTextContains("30.00")
        compose.onNodeWithText("Pretax amount · Joy").performScrollTo().assertTextContains("20.00")
        compose.onNodeWithText("Extra tax % · Alex").performScrollTo().assertTextContains("10")
        compose.onNodeWithText("Total: CAD 63.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Paid by Alex").assertIsSelected()
        compose.onNodeWithContentDescription("Paid by Taylor").assertIsNotSelected()
        compose.onNodeWithContentDescription("Include Taylor in split").assertIsOff()
        compose.onNodeWithText("Pretax amount · Taylor").assertDoesNotExist()
        compose.onNodeWithText("Save expense").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("Include Taylor in split").performScrollTo().performClick()
        compose.onNodeWithText("Pretax amount · Taylor").assertTextContains("")
        compose.onNodeWithText("Save expense").performScrollTo().assertIsNotEnabled()
    }

    @Test fun preservesPercentageDraftAndRecoversOfflineMembershipOnResubscription() {
        showGroup()
        compose.onNodeWithText("Add expense").performScrollTo().performClick()
        compose.onNodeWithText("Description").performTextInput("Percentage draft")
        compose.onNodeWithText("Final total (CAD)").performTextInput("10.00")
        compose.onNodeWithText("Percentage", substring = false).performClick()
        compose.onAllNodesWithText("Share (%)")[0].performScrollTo().performTextInput("40")
        compose.onAllNodesWithText("Share (%)")[1].performScrollTo().performTextInput("60")
        current.set(summary.copy(group = group.copy(memberCount = 3), members = members + GroupMember("c", "Taylor")))
        compose.runOnIdle { live.disconnected(); live.subscribed() }
        waitFor("Taylor")
        compose.onNodeWithText("Final total (CAD)").performScrollTo().assertTextContains("10.00")
        compose.onAllNodesWithText("Share (%)")[0].performScrollTo().assertTextContains("40")
        compose.onAllNodesWithText("Share (%)")[1].performScrollTo().assertTextContains("60")
        compose.onNodeWithContentDescription("Include Taylor in split").assertIsOff()
        compose.onNodeWithText("Save expense").performScrollTo().assertIsEnabled()
        compose.runOnIdle { live.membershipChanged(GroupMembershipChange.Joined) }
        compose.onNodeWithText("Description").performScrollTo().assertTextContains("Percentage draft")
    }

    @Test fun closesGroupAndReleasesSubscriptionAfterDeletion() {
        showGroup()
        compose.onNodeWithText("Add expense").performScrollTo().performClick()
        compose.runOnIdle { live.membershipChanged(GroupMembershipChange.Deleted) }
        waitFor("This group is no longer available.")
        compose.onNodeWithText("New expense · Roommates").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, subscriptions) }
    }

    @Test fun failedRosterReadKeepsEqualDraftAndRecoversOnResubscription() {
        showGroup()
        compose.onNodeWithText("Add expense").performScrollTo().performClick()
        compose.onNodeWithText("Description").performTextInput("Equal draft")
        compose.onNodeWithText("Final total (CAD)").performTextInput("42.00")
        readSummary.set { throw IOException("Temporary roster outage") }
        joined()
        waitFor("Temporary roster outage")
        compose.onNodeWithText("Description").performScrollTo().assertTextContains("Equal draft")
        compose.onNodeWithText("Final total (CAD)").assertTextContains("42.00")
        readSummary.set { current.get() }
        compose.runOnIdle { live.subscribed() }
        waitFor("Taylor")
        compose.onNodeWithText("Temporary roster outage").assertDoesNotExist()
        compose.onNodeWithContentDescription("Include Taylor in split").assertIsOff()
        compose.onNodeWithText("Save expense").performScrollTo().assertIsEnabled()
    }

    @Test fun aDelayedOldReadCannotReplaceTheLatestRoster() {
        showGroup()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstReturned = CountDownLatch(1)
        readSummary.set {
            val loaded = current.get()
            if (loaded.members.size == 3) {
                firstStarted.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
                firstReturned.countDown()
            }
            loaded
        }
        try {
            joined()
            compose.waitUntil(10000) { firstStarted.count == 0L }
            current.set(summary.copy(group = group.copy(memberCount = 4),
                members = members + listOf(GroupMember("c", "Taylor"), GroupMember("d", "Sam"))))
            compose.runOnIdle { live.membershipChanged(GroupMembershipChange.Joined) }
            waitFor("Members (4)")
            releaseFirst.countDown()
            assertTrue(firstReturned.await(10, TimeUnit.SECONDS))
            compose.waitForIdle()
            compose.onNodeWithText("Members (4)").assertExists()
            compose.onNodeWithText("Members (3)").assertDoesNotExist()
        } finally { releaseFirst.countDown() }
    }

    @Test fun ignoresMalformedAndUnrelatedMembershipNotifications() {
        for (raw in listOf(null, 1, "{", "{}", """{"groupId":"other","type":"joined"}""",
            """{"groupId":"group-1","type":"unknown"}""")) {
            assertNull(parseMembershipChange(raw, group.id))
        }
        assertEquals(GroupMembershipChange.Joined, parseMembershipChange("""{"groupId":"group-1","type":"joined"}""", group.id))
        assertEquals(GroupMembershipChange.Deleted, parseMembershipChange("""{"groupId":"group-1","type":"deleted"}""", group.id))
    }
}
