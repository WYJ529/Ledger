package com.example.cpen321application

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Uses isolated session preferences; never logs out the device's real account. */
class AppNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val preferencesName = "navigation-test-${UUID.randomUUID()}"
    private val store = ViewModelStore()
    private lateinit var navigation: AccountNavigation
    private lateinit var storage: SessionStorage
    private lateinit var ledger: LedgerViewModel
    private val session = Session("unused-navigation-test-token", Long.MAX_VALUE,
        Account("navigation-test", "navigation@example.com", "Navigation Test", listOf("password"), "2026-10-18T10:00:00Z"))

    @Before fun setUp() {
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                application.getSharedPreferences(preferencesName, mode)
        }
        storage = SessionStorage(context)
        navigation = AccountNavigation(storage, mutableStateOf(AppSection.Expenses), mutableStateOf(null), mutableStateOf(null))
        compose.runOnUiThread {
            ledger = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[LedgerViewModel::class.java]
        }
    }

    @After fun tearDown() {
        compose.runOnUiThread { store.clear() }
        application.deleteSharedPreferences(preferencesName)
    }

    private fun openApp(invitation: String? = null) {
        val pending = mutableStateOf(invitation)
        compose.setContent {
            CPEN321ApplicationTheme {
                LedgerApp(ledger, pending.value, onInvitationHandled = { pending.value = null }, navigation = navigation)
            }
        }
    }

    private fun awaitDashboard() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("+ Add transaction") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("section-Expenses").assertIsSelected()
        compose.onNodeWithTag("ledger-dashboard").assertIsDisplayed()
    }

    @Test fun signedOutLaunchOpensPersonalExpensesWithThreeTabs() {
        openApp()
        awaitDashboard()
        listOf("section-Expenses", "section-Groups", "section-Me").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Welcome back").assertDoesNotExist()
        compose.onNodeWithText("Create group").assertDoesNotExist()
        compose.onNodeWithText("guest", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("local", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test fun protectedTabsRequireLoginAndAllowContinuingWithoutIt() {
        openApp()
        awaitDashboard()
        listOf("section-Groups", "section-Me").forEach { tag ->
            compose.onNodeWithTag(tag).performClick().assertIsSelected()
            compose.onNodeWithText("Welcome back").assertIsDisplayed()
            compose.onNodeWithText("Create group").assertDoesNotExist()
            compose.onNodeWithText("Log out").assertDoesNotExist()
            compose.onNodeWithText("Use without Login").performScrollTo().performClick()
            awaitDashboard()
        }
    }

    @Test fun systemBackDismissesLoginToPersonalExpenses() {
        openApp()
        awaitDashboard()
        compose.onNodeWithTag("section-Me").performClick()
        Espresso.pressBack()
        awaitDashboard()
    }

    @Test fun invitationLoginCanBeDismissedWithoutAnAccount() {
        openApp("ledger://invite/test-invitation")
        compose.onNodeWithText("Sign in or create an account to view your group invitation.").assertExists()
        compose.onNodeWithTag("section-Groups").assertIsSelected()
        compose.onNodeWithText("Use without Login").performScrollTo().performClick()
        awaitDashboard()
        compose.onNodeWithTag("section-Me").performClick()
        compose.onNodeWithText("Sign in or create an account to view your group invitation.").assertDoesNotExist()
    }

    @Test fun successfulSignInSelectsExpensesAndPersistsTheSession() {
        listOf(AppSection.Groups, AppSection.Me).forEach { section ->
            navigation.section = section
            navigation.authNotice = "Previous session expired."
            navigation.signIn(session)
            assertEquals(AppSection.Expenses, navigation.section)
            assertEquals(session, navigation.session)
            assertEquals(session, storage.load())
            assertNull(navigation.authNotice)
        }
    }

    @Test fun expirationClearsSessionAndKeepsTheProtectedLoginGate() {
        navigation.signIn(session)
        navigation.section = AppSection.Groups
        navigation.groupToOpen = "previous-group"
        navigation.endSession()
        assertNull(storage.load())
        assertNull(navigation.groupToOpen)
        openApp()
        compose.onNodeWithTag("section-Groups").assertIsSelected()
        compose.onNodeWithText("Your session has expired. Please sign in again.").assertExists()
        compose.onNodeWithText("Use without Login").performScrollTo().performClick()
        awaitDashboard()
    }

    @Test fun meShowsAccountDetailsWithoutCreateOrRefreshButtons() {
        compose.setContent {
            CPEN321ApplicationTheme {
                Scaffold { insets ->
                    Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        AccountScreen(session, onAccountUpdated = {}, onSessionExpired = {}, onLoggedOut = {})
                    }
                }
            }
        }
        compose.onNodeWithText("Me").assertIsDisplayed()
        compose.onNodeWithText("Navigation Test").assertIsDisplayed()
        compose.onNodeWithText("navigation@example.com").assertIsDisplayed()
        compose.onNodeWithText("Create group").assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("Log out").performScrollTo().assertIsDisplayed()
    }
}
