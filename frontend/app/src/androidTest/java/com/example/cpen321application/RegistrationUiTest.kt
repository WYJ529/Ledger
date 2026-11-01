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
    @Volatile private var signedIn: Session? = null
    private var existingGoogle = false
    private var rejectLink = false
    private fun session(username: String) = Session("test-session", Long.MAX_VALUE,
        Account("sam", "", username, listOf("password"), "2026-11-01T00:00:00Z", username = username))
    private val api = object : AuthenticationApi {
        override fun register(username: String, password: String, confirmPassword: String): Session {
            calls.add(listOf("register", username, password, confirmPassword))
            return session(username)
        }
        override fun login(username: String, password: String): Session {
            calls.add(listOf("login", username, password))
            return session(username)
        }
        override fun signInWithGoogle(idToken: String): GoogleSignInResult =
            if (existingGoogle) GoogleSignInResult.SignedIn(session("existing"))
            else GoogleSignInResult.NeedsAccount(idToken, "google@example.com", "Google Name")
        override fun registerWithGoogle(idToken: String, username: String, password: String, confirmPassword: String): Session {
            calls.add(listOf("google-register", idToken, username, password, confirmPassword))
            return session(username)
        }
        override fun linkWithGoogle(idToken: String, username: String, password: String): Session {
            calls.add(listOf("google-link", idToken, username, password))
            if (rejectLink) throw ApiException(401, "Incorrect username or password.")
            return session(username)
        }
    }

    private fun begin(google: Boolean = false) {
        compose.setContent {
            CPEN321ApplicationTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AuthFlow(notice = null, authApi = api, googleTokenProvider = { _, _ -> "test-google-token" }) { signedIn = it }
                }
            }
        }
        compose.onNodeWithText(if (google) "Sign in with Google" else "New user? Sign up here!").performScrollTo().performClick()
        if (google && !existingGoogle) compose.waitUntil(5000) {
            compose.onAllNodesWithText("Finish creating your account").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun fill(confirm: String? = "correct horse") {
        compose.onNodeWithText("Username *").performScrollTo().performTextInput("Sam_User")
        compose.onNodeWithText("Password *").performScrollTo().performTextInput("correct horse")
        if (confirm != null) compose.onNodeWithText("Confirm password *").performScrollTo().performTextInput(confirm)
    }

    @Test fun registersWithOnlyUsernameAndMatchingPasswords() {
        begin()
        compose.onNodeWithText("Email *").assertDoesNotExist()
        compose.onNodeWithText("Name *").assertDoesNotExist()
        fill("different")
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.onNodeWithText("Passwords do not match.").assertExists()
        compose.runOnIdle { assertTrue(calls.isEmpty()); assertNull(signedIn) }
        compose.onNodeWithText("Confirm password *").performTextReplacement("correct horse")
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.waitUntil(5000) { signedIn != null }
        compose.runOnIdle { assertEquals(listOf("register", "sam_user", "correct horse", "correct horse"), calls.single()) }
    }

    @Test fun newGoogleUserSuppliesUsernameAndBothPasswords() {
        begin(google = true)
        compose.runOnIdle { assertNull(signedIn) }
        fill()
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.waitUntil(5000) { signedIn != null }
        compose.runOnIdle { assertEquals(listOf("google-register", "test-google-token", "sam_user", "correct horse", "correct horse"), calls.single()) }
    }

    @Test fun firstGoogleSignInCanLinkExistingAccountAndRetryWrongPassword() {
        begin(google = true)
        compose.onNodeWithText("Link an existing account").performScrollTo().performClick()
        compose.onNodeWithText("Confirm password *").assertDoesNotExist()
        fill(confirm = null)
        rejectLink = true
        compose.onNodeWithText("Link account").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Incorrect username or password.").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertNull(signedIn); rejectLink = false }
        compose.onNodeWithText("Link account").performScrollTo().performClick()
        compose.waitUntil(5000) { signedIn != null }
        compose.runOnIdle { assertEquals(listOf("google-link", "test-google-token", "sam_user", "correct horse"), calls.last()) }
    }

    @Test fun existingGoogleUserEntersAccountDirectly() {
        existingGoogle = true
        begin(google = true)
        compose.waitUntil(5000) { signedIn != null }
        compose.runOnIdle { assertEquals("existing", signedIn!!.account.username); assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("Finish creating your account").assertDoesNotExist()
    }
}
