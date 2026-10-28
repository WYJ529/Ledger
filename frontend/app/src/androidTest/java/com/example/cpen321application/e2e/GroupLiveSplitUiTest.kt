package com.example.cpen321application.e2e

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.cpen321application.GroupMember
import com.example.cpen321application.GroupSplitSession
import com.example.cpen321application.SplitItemUnit
import com.example.cpen321application.SplitSessionContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Device-level coverage for the claim UI; auth, ordering and retry persistence are API/domain tests. */
class GroupLiveSplitUiTest {
    @get:Rule val compose = createComposeRule()
    private val members = listOf(GroupMember("a", "Joy", "owner"), GroupMember("b", "Alex"))
    private val partial = GroupSplitSession(
        id = "split-1", groupId = "group-1", description = "Dinner", amountCents = 600,
        currency = "CAD", paidBy = "a",
        units = listOf(
            SplitItemUnit("soup", "Soup", 100, null),
            SplitItemUnit("rice", "Rice", 200, "a"),
            SplitItemUnit("tea", "Tea", 300, "b"),
        ),
        status = "open", version = 2, sequence = 3, finalizedExpenseId = null,
    )
    private val complete = partial.copy(
        units = partial.units.map { if (it.ownerId == null) it.copy(ownerId = "a") else it },
        version = 3, sequence = 4,
    )

    @Composable private fun TestPage(content: @Composable ColumnScope.() -> Unit) {
        MaterialTheme {
            Scaffold { insets ->
                Column(
                    Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp), content = content,
                )
            }
        }
    }

    @Test fun showsCanonicalTotalsAndMemberAllocations() {
        compose.setContent { TestPage {
            SplitSessionContent(partial, members, "a", isOwner = true, enabled = true, onClaim = { _, _ -> }, onFinalize = {})
        } }
        compose.onNodeWithText("Paid by Joy · CAD 6.00").assertIsDisplayed()
        compose.onNodeWithText("Assigned CAD 5.00 · Unassigned CAD 1.00").assertIsDisplayed()
        compose.onNodeWithText("Joy: CAD 2.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Alex: CAD 3.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 / 3 units claimed · session version 2").performScrollTo().assertIsDisplayed()
    }

    @Test fun availableUnitCanBeClaimedOwnUnitReleasedAndOthersAreTaken() {
        val actions = mutableListOf<Pair<String, String>>()
        compose.setContent { TestPage {
            SplitSessionContent(partial, members, "a", isOwner = true, enabled = true,
                onClaim = { unit, action -> actions += unit.unitId to action }, onFinalize = {})
        } }
        compose.onNodeWithText("Claim").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Release").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Taken").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("soup" to "claim", "rice" to "unclaim"), actions) }
    }

    @Test fun recoveryOrOfflineStateDisablesClaimAndRelease() {
        compose.setContent { TestPage {
            SplitSessionContent(partial, members, "a", isOwner = true, enabled = false, onClaim = { _, _ -> }, onFinalize = {})
        } }
        compose.onNodeWithText("Claim").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Release").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Finalize split").performScrollTo().assertIsNotEnabled()
    }

    @Test fun ownerCanFinalizeOnlyWhenAllUnitsAreAssignedAndCallsBackOnce() {
        val displayed = mutableStateOf(partial)
        var finalized = 0
        compose.setContent { TestPage {
            SplitSessionContent(displayed.value, members, "a", isOwner = true, enabled = true,
                onClaim = { _, _ -> }, onFinalize = { finalized++ })
        } }
        compose.onNodeWithText("Finalize split").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { displayed.value = complete }
        compose.onNodeWithText("Finalize split").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, finalized) }
    }

    @Test fun nonOwnerCannotFinalizeEvenWhenComplete() {
        compose.setContent { TestPage {
            SplitSessionContent(complete, members, "b", isOwner = false, enabled = true, onClaim = { _, _ -> }, onFinalize = {})
        } }
        compose.onNodeWithText("Finalize split").assertDoesNotExist()
    }

    @Test fun finalizedSessionShowsPostedStatusAndHasNoMutationButtons() {
        val finalized = complete.copy(status = "finalized", finalizedExpenseId = "expense-1", version = 4, sequence = 5)
        compose.setContent { TestPage {
            SplitSessionContent(finalized, members, "a", isOwner = true, enabled = true, onClaim = { _, _ -> }, onFinalize = {})
        } }
        compose.onNodeWithText("Finalized · expense expense-1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Claim").assertDoesNotExist()
        compose.onNodeWithText("Release").assertDoesNotExist()
        compose.onNodeWithText("Finalize split").assertDoesNotExist()
    }

    @Test fun completeSessionCannotFinalizeDuringRecovery() {
        compose.setContent { TestPage {
            SplitSessionContent(complete, members, "a", isOwner = true, enabled = false, onClaim = { _, _ -> }, onFinalize = {})
        } }
        compose.onNodeWithText("Finalize split").performScrollTo().assertIsNotEnabled()
    }
}
