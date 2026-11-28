package com.example.cpen321application.e2e

import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import java.util.UUID
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onFirst
import org.junit.Test

internal class LetsGoDutchFullStackE2eTest : FullStackE2eTest() {
    @Test fun ownerFinalizesTheMembersItemClaim() {
        val group = "Dutch " + UUID.randomUUID().toString().take(6)
        createGroupWithMember(group)
        ui.openManualBill("Dutch dinner", "18.00")
        ui.button("Let’s Go Dutch").performClick()
        ui.waitFor("Start a split session")
        enterItems()
        ui.button("Start split session").performClick()
        ui.waitFor("0 / 2 units claimed")

        ui.signIn(member.name, member.password)
        ui.openGroup(group)
        ui.button("Claim items · Dutch dinner").performClick()
        ui.waitFor("0 / 2 units claimed")
        claimItem()
        ui.waitFor("Claimed by ${member.name}")

        ui.signIn(owner.name, owner.password)
        ui.openGroup(group)
        ui.button("Claim items · Dutch dinner").performClick()
        ui.waitFor("1 / 2 units claimed")
        ui.waitFor("Claimed by ${member.name}")
        claimItem()
        ui.waitFor("2 / 2 units claimed")
        ui.button("Finalize split").performClick()
        ui.waitFor("Finalized · saved to group expenses")

        ui.signIn(member.name, member.password)
        ui.openGroup(group)
        ui.waitFor("Dutch dinner")
        ui.button("View split").performClick()
        ui.waitFor("${member.name}: CAD 12.00")
        ui.waitFor("${owner.name}: CAD 6.00")
    }

    private fun claimItem() {
        // The snapshot can render before the live subscription enables claims.
        val claim = hasText("Claim") and hasClickAction() and isEnabled()
        compose.waitUntil(20_000) { compose.onAllNodes(claim).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(claim).onFirst().performScrollTo().performClick()
    }

    // Two receipt items that add up to the 18.00 total carried over from the bill form.
    private fun enterItems() {
        compose.onNodeWithText("Item name").performScrollTo().performTextInput("Burger")
        compose.onNodeWithText("Each").performScrollTo().performTextInput("12.00")
        ui.button("Add receipt item").performClick()
        compose.onAllNodesWithText("Item name")[1].performScrollTo().performTextInput("Fries")
        compose.onAllNodesWithText("Each")[1].performScrollTo().performTextInput("6.00")
        compose.onNode(isToggleable()).performScrollTo().performClick()
    }
}
