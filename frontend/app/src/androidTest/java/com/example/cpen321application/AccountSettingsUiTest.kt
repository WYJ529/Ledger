package com.example.cpen321application

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AccountSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val session = Session("unused-settings-test-token", Long.MAX_VALUE,
        Account("settings-test-${UUID.randomUUID()}", "settings@example.com", "Settings Test", listOf("password"), "2026-10-18T10:00:00Z"))

    private fun openAccount() {
        compose.setContent {
            CPEN321ApplicationTheme {
                Scaffold { insets ->
                    val scroll = rememberScrollState()
                    Column(Modifier.fillMaxSize().padding(insets).verticalScroll(scroll).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        AccountScreen(session, {}, {}, {}, onNavigate = { scroll.scrollTo(0) })
                    }
                }
            }
        }
    }

    @Test fun avatarOffersCameraAndLibraryAndCancelPreservesProfile() {
        openAccount()
        compose.onNodeWithTag("account-avatar").performClick()
        compose.onNodeWithText("Take a picture").assertIsDisplayed()
        compose.onNodeWithText("Choose from a library").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Settings Test").assertIsDisplayed()
    }

    @Test fun editExposesRequestedFieldsAndValidatesBeforeSaving() {
        openAccount()
        compose.onNodeWithTag("edit-account").performClick()
        compose.onNodeWithText("Account settings").assertIsDisplayed()
        compose.onNodeWithTag("account-name").performTextReplacement("")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("account-row-Time zone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("account-row-Default currency").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("save-account").performScrollTo().performClick()
        compose.onNodeWithText("Enter your name.").assertExists()
        compose.onNodeWithText("Account changes saved.").assertDoesNotExist()
    }

    @Test fun emailChangeRequiresCurrentPasswordAndBackConfirmsDiscard() {
        openAccount()
        compose.onNodeWithTag("edit-account").performClick()
        compose.onNodeWithTag("account-email").performTextReplacement("changed@example.com")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("save-account").performScrollTo().performClick()
        compose.onNodeWithText("Confirm your current password.").assertExists()
        Espresso.pressBack()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Discard changes?").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("settings@example.com").assertIsDisplayed()
    }

    @Test fun notificationMasterPreservesIndividualChoicesAcrossNavigation() {
        openAccount()
        compose.onNodeWithTag("account-row-Notifications").performScrollTo().performClick()
        compose.onNodeWithTag("preference-Expense activity").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("preference-Allow notifications").performScrollTo().performClick()
        compose.onNodeWithTag("preference-Expense activity").assertIsNotEnabled()
        Espresso.pressBack()
        compose.onNodeWithTag("account-row-Notifications").performScrollTo().performClick()
        compose.onNodeWithTag("preference-Allow notifications").assertIsOff().performClick()
        compose.onNodeWithTag("preference-Expense activity").assertIsOff().assertIsEnabled()
        val preferences = AccountPreferences(ApplicationProvider.getApplicationContext(), session.account.id)
        assertFalse(preferences.enabled(NotificationPreference.ExpenseActivity))
        assertTrue(preferences.notificationsEnabled)
        val otherAccount = AccountPreferences(ApplicationProvider.getApplicationContext(), "other-${UUID.randomUUID()}")
        assertTrue(otherAccount.enabled(NotificationPreference.ExpenseActivity))
    }

    @Test fun supportAndRatingFallbackAreReachable() {
        openAccount()
        compose.onNodeWithTag("account-row-Contact Us").performScrollTo().performClick()
        compose.onNodeWithText("Open support page").assertIsDisplayed()
        Espresso.pressBack()
        if (BuildConfig.LEDGER_STORE_URL.isBlank()) {
            compose.onNodeWithTag("account-row-Rate Ledger").performScrollTo().performClick()
            compose.onNodeWithText("Store ratings are not available", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
        }
        compose.onNodeWithTag("log-out").performScrollTo().performClick()
        compose.onNodeWithText("Log out?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
    }
}
