package com.example.cpen321application

import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Exercises Android lock UI and lifecycle using isolated preferences and deterministic OS callbacks. */
class AppLockTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val preferenceFile = "app-lock-test-${UUID.randomUUID()}"
    private lateinit var preferences: SharedPreferences
    private lateinit var controller: AppLockController
    private val biometric = TestBiometricAccess()

    @Before fun setUp() {
        compose.runOnUiThread {
            preferences = compose.activity.getSharedPreferences(preferenceFile, Context.MODE_PRIVATE)
            controller = AppLockController(compose.activity, preferences, biometric)
            controller.onStart()
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread {
            compose.activity.deleteSharedPreferences(preferenceFile)
            if (!compose.activity.getSharedPreferences("app_security", Context.MODE_PRIVATE).getBoolean("biometric_lock", false)) {
                compose.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    @Test fun enablingAndDisablingRequireSuccessfulBiometrics() {
        compose.runOnUiThread {
            controller.requestEnabled(true)
            assertFalse(controller.enabled)
            biometric.failure("Cancelled")
            assertFalse(controller.enabled)
            assertFalse(preferences.getBoolean("biometric_lock", false))
            controller.requestEnabled(true)
            biometric.success()
            assertTrue(controller.enabled)
            assertTrue(preferences.getBoolean("biometric_lock", false))
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            controller.requestEnabled(false)
            biometric.failure("Cancelled")
            assertTrue(controller.enabled)
            controller.requestEnabled(false)
            biometric.success()
            assertFalse(controller.enabled)
        }
    }

    @Test fun backgroundCancelsAuthenticationAndRejectsItsLateCallback() {
        compose.runOnUiThread {
            controller.requestEnabled(true); biometric.success()
            controller.onStop(false)
            assertTrue(controller.locked)
            controller.onStart()
            controller.unlock()
            val staleSuccess = biometric.success
            controller.onStop(false)
            controller.onStart()
            staleSuccess()
            assertTrue(controller.locked)
            controller.unlock(); biometric.failure("Cancelled")
            assertTrue(controller.locked)
            controller.unlock(); biometric.success()
            assertFalse(controller.locked)
            val restarted = AppLockController(compose.activity, preferences, TestBiometricAccess())
            assertTrue(restarted.enabled)
            assertTrue(restarted.locked)
        }
    }

    @Test fun unavailableBiometricsCannotEnableLockOrBypassExistingLock() {
        compose.runOnUiThread {
            biometric.available = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
            controller.requestEnabled(true)
            assertFalse(controller.enabled)
            assertTrue(controller.message!!.contains("device settings"))
            preferences.edit().putBoolean("biometric_lock", true).commit()
            val restarted = AppLockController(compose.activity, preferences, biometric)
            restarted.onStart(); restarted.unlock()
            assertTrue(restarted.locked)
        }
    }

    @Test fun lockingRemovesSensitiveContentAndOpenDialogs() {
        compose.runOnUiThread { compose.activity.setContent {
            CPEN321ApplicationTheme {
                AppLockGate(controller) {
                    Text("Sensitive account information")
                    AlertDialog(onDismissRequest = {}, title = { Text("Private dialog") },
                        confirmButton = { TextButton(onClick = {}) { Text("OK") } })
                }
            }
        } }
        compose.onNodeWithText("Private dialog").assertIsDisplayed()
        compose.runOnUiThread { controller.requestEnabled(true); biometric.success(); controller.onStop(false); controller.onStart() }
        compose.onNodeWithTag("app-lock").assertIsDisplayed()
        compose.onNodeWithText("Sensitive account information").assertDoesNotExist()
        compose.onNodeWithText("Private dialog").assertDoesNotExist()
        compose.onNodeWithText("Unlock").performClick()
        compose.runOnUiThread { biometric.failure("Cancelled") }
        compose.onNodeWithTag("app-lock").assertIsDisplayed()
        compose.onNodeWithText("Unlock").performClick()
        compose.runOnUiThread { biometric.success() }
        compose.onNodeWithText("Private dialog").assertIsDisplayed()
    }
}

private class TestBiometricAccess : BiometricAccess {
    var available = BiometricManager.BIOMETRIC_SUCCESS
    var success: () -> Unit = {}
    var failure: (String) -> Unit = {}
    override fun availability(): Int = available
    override fun authenticate(title: String, onSuccess: () -> Unit, onError: (String) -> Unit) { success = onSuccess; failure = onError }
    override fun cancel() { /* Keep captured callbacks so tests can simulate a late OS result. */ }
}
