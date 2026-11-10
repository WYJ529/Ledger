package com.example.cpen321application.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.cpen321application.Account
import com.example.cpen321application.InvitationScreen
import com.example.cpen321application.Session
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InvitationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun invalidInvitationReleasesTheRequestAndAllowsRejection() {
        val session = Session("unused-token", Long.MAX_VALUE, Account("me", "", "Me", emptyList(), ""))
        var rejected = false
        compose.setContent {
            MaterialTheme {
                Column {
                    InvitationScreen(session, "not-an-invitation", onAccepted = { error("Unexpected acceptance") },
                        onRejected = { rejected = true }, onSessionExpired = { error("Unexpected sign-out") })
                }
            }
        }
        compose.onNodeWithText("Invalid invitation link.").assertIsDisplayed()
        compose.onNodeWithText("Reject — return home").performClick()
        compose.runOnIdle { assertTrue(rejected) }
    }
}
