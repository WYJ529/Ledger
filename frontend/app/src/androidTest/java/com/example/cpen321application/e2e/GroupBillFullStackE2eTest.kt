package com.example.cpen321application.e2e

import androidx.compose.ui.test.performClick
import java.util.UUID
import org.junit.Test

internal class GroupBillFullStackE2eTest : FullStackE2eTest() {
    @Test fun memberReadsTheOwnersEqualSplitInGroupExpenses() {
        val group = "Flat " + UUID.randomUUID().toString().take(6)
        createGroupWithMember(group)
        ui.openManualBill("Full-stack groceries", "30.00")
        ui.button("Save expense").performClick()
        ui.waitFor("Full-stack groceries")

        ui.signIn(member.name, member.password)
        ui.openGroup(group)
        ui.waitFor("Full-stack groceries")
        ui.waitFor("Paid by ${owner.name} · Equal split")
        ui.waitFor("Your allocated share: CAD 15.00")
        ui.button("View split").performClick()
        ui.waitFor("${owner.name}: CAD 15.00")
        ui.waitFor("${member.name}: CAD 15.00")
    }
}
