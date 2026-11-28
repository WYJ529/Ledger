package com.example.cpen321application.integration

import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two-client integration test for Let's Go Dutch: the owner runs the split in the real app while a member claims an
 * item from "another device" through the real Node backend; the claim reaches the app over Socket.IO.
 */
internal class LetsGoDutchLiveIntegrationTest : LiveGroupIntegrationTestBase() {
    // UC: Start Let's Go Dutch, Claim Items, View Live Allocations, Finalize Split.
    // Steps: owner starts an itemized split in the app -> member claims Burger through the backend ->
    // the app shows the claim live -> owner claims Fries and finalizes in the app -> the posted bill is read back.
    @Test fun ownerFinalizesASplitAfterAMemberClaimsFromAnotherDevice() {
        val owner = backend.register()
        val member = backend.otherDevice()
        val groupName = "Dutch " + UUID.randomUUID().toString().take(6)
        val groupId = backend.createGroup(owner, groupName)
        backend.join(owner, member, groupId)

        ui.signIn(owner.name, owner.password)
        ui.openGroup(groupName)
        ui.openManualBill("Dutch dinner", "18.00")
        ui.button("Let’s Go Dutch").performClick()
        ui.waitFor("Start a split session")
        enterItems()
        ui.button("Start split session").performClick()
        ui.waitFor("0 / 2 units claimed")

        val split = objects(backend.snapshot(member, groupId).getJSONArray("splitSessions")).single()
        val burger = objects(split.getJSONArray("units")).single { it.getString("description") == "Burger" }
        backend.claim(member, split.getString("id"), burger.getString("unitId"), split.getInt("version"))
        ui.waitFor("Claimed by ${member.name}")
        ui.waitFor("1 / 2 units claimed")

        ui.button("Claim").performClick()
        ui.waitFor("2 / 2 units claimed")
        ui.button("Finalize split").performClick()
        ui.waitFor("Finalized · saved to group expenses")

        val (bill, shares) = onlyBill(member, groupId)
        assertEquals("Dutch dinner", bill.getString("description"))
        assertEquals(1800L, bill.getLong("amountCents"))
        assertEquals(mapOf(member.id to 1200L, owner.id to 600L), shares)
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
