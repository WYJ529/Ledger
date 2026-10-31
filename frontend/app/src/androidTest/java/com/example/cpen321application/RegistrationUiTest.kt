package com.example.cpen321application

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RegistrationUiTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<List<String>>()
    private var signedIn: Session? = null
    private var sends = 0
    private var rejectCode = false
    private val api = object : PasswordRegistrationApi {
        override fun requestRegistrationCode(email: String): RegistrationChallenge {
            sends++
            return RegistrationChallenge("challenge-$sends", email, 0)
        }
        override fun register(email: String, password: String, displayName: String, verificationId: String, code: String): Session {
            calls.add(listOf(email, password, displayName, verificationId, code))
            if (rejectCode) throw ApiException(400, "The verification code is incorrect.")
            return Session("test-session", Long.MAX_VALUE,
                Account("sam", email, displayName, listOf("password"), "2026-10-30T00:00:00Z"))
        }
    }

    private fun begin() {
        compose.setContent {
            CPEN321ApplicationTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AuthFlow(notice = null, registrationApi = api) { signedIn = it }
                }
            }
        }
        compose.onNodeWithText("New user? Sign up here!").performScrollTo().performClick()
        compose.onNodeWithText("Name *").performScrollTo().performTextInput("Sam")
        compose.onNodeWithText("Email *").performScrollTo().performTextInput("Sam@example.com")
        compose.onNodeWithText("Password *").performScrollTo().performTextInput("correct horse")
        compose.onNodeWithText("Confirm password *").performScrollTo().performTextInput("correct horse")
        compose.onNodeWithText("Send verification code").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Verify your email").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun accountIsCreatedOnlyAfterCodeEntryAndSuccessfulVerification() {
        begin()
        compose.runOnIdle { assertNull(signedIn); assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Verify and create account").performScrollTo().performClick()
        compose.onNodeWithText("Enter the 6-digit code from your email.").assertExists()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Verification code *").performScrollTo().performTextInput("001234")
        compose.onNodeWithText("Verify and create account").performScrollTo().performClick()
        compose.waitUntil(5000) { signedIn != null }
        compose.runOnIdle { assertEquals(listOf("sam@example.com", "correct horse", "Sam", "challenge-1", "001234"), calls.single()) }
    }

    @Test fun invalidCodeShowsAnErrorAndResendUsesTheNewChallenge() {
        rejectCode = true
        begin()
        compose.onNodeWithText("Verification code *").performScrollTo().performTextInput("123456")
        compose.onNodeWithText("Verify and create account").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("The verification code is incorrect.").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertNull(signedIn); rejectCode = false }
        compose.onNodeWithText("Resend code").performScrollTo().performClick()
        compose.waitUntil(5000) { sends == 2 }
        compose.onNodeWithText("Verification code *").performScrollTo().performTextInput("654321")
        compose.onNodeWithText("Verify and create account").performScrollTo().performClick()
        compose.waitUntil(5000) { signedIn != null }
        compose.runOnIdle { assertEquals("challenge-2", calls.last()[3]); assertEquals("654321", calls.last()[4]) }
    }
}
