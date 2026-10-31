package com.example.cpen321application

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

internal interface BiometricAccess {
    fun availability(): Int
    fun authenticate(title: String, onSuccess: () -> Unit, onError: (String) -> Unit)
    fun cancel()
}

internal class AndroidBiometricAccess(private val activity: FragmentActivity) : BiometricAccess {
    private var prompt: BiometricPrompt? = null
    override fun availability(): Int = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    override fun cancel() { prompt?.cancelAuthentication(); prompt = null }
    override fun authenticate(title: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onError(errString.toString()) }
        })
        prompt?.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(t(title))
            .setSubtitle(t("Use your fingerprint or face to continue."))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText(t("Cancel")).build())
    }
}

internal class AppLockController(private val activity: FragmentActivity,
    private val prefs: SharedPreferences = activity.getSharedPreferences("app_security", Context.MODE_PRIVATE),
    private val biometrics: BiometricAccess = AndroidBiometricAccess(activity)) {
    var enabled by mutableStateOf(prefs.getBoolean("biometric_lock", false))
        private set
    var locked by mutableStateOf(enabled)
        private set
    var authenticating by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var foreground = false
    private var generation = 0

    init { updateSecureWindow() }

    fun availability(): Int = biometrics.availability()

    fun unavailableMessage(): String = when (availability()) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Add a fingerprint or face in your device settings first."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "This device does not support biometric authentication."
        else -> "Biometric authentication is temporarily unavailable. Try again or check device settings."
    }

    fun onStart() { foreground = true }

    fun onStop(changingConfiguration: Boolean) {
        if (changingConfiguration) return
        foreground = false
        generation++
        biometrics.cancel()
        authenticating = false
        if (enabled) locked = true
    }

    fun unlock() { authenticate("Unlock Ledger") { locked = false } }

    fun requestEnabled(value: Boolean) {
        if (value == enabled) return
        authenticate(if (value) "Enable app lock" else "Turn off app lock") {
            // Synchronous write: the lock must survive immediate process termination.
            if (prefs.edit().putBoolean("biometric_lock", value).commit()) {
                enabled = value
                locked = false
                updateSecureWindow()
            } else message = "Could not save the app lock setting. Try again."
        }
    }

    private fun authenticate(title: String, onSuccess: () -> Unit) {
        if (authenticating || !foreground) return
        if (availability() != BiometricManager.BIOMETRIC_SUCCESS) { message = unavailableMessage(); return }
        authenticating = true
        message = null
        val attempt = ++generation
        try {
            biometrics.authenticate(title, onSuccess = {
                if (attempt == generation && foreground) {
                    authenticating = false
                    message = null
                    onSuccess()
                }
            }, onError = { error ->
                if (attempt == generation) { authenticating = false; message = error }
            })
        } catch (_: RuntimeException) {
            authenticating = false
            message = "Could not start biometric authentication. Try again."
        }
    }

    private fun updateSecureWindow() {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun openDeviceSettings() {
        try { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        catch (_: RuntimeException) { message = "Open your device settings to manage fingerprints or face unlock." }
    }
}

internal val LocalAppLock = staticCompositionLocalOf<AppLockController> { error("App lock is not available.") }

@Composable
internal fun AppLockGate(controller: AppLockController, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    val savedContent = rememberSaveableStateHolder()
    LaunchedEffect(controller.locked) { if (controller.locked) focus.clearFocus(force = true) }
    CompositionLocalProvider(LocalAppLock provides controller) {
        Box(Modifier.fillMaxSize()) {
            // Remove content, including dialogs and accessibility nodes, while locked.
            // Saved navigation/drafts and activity-result keys are restored after unlocking.
            if (!controller.locked) savedContent.SaveableStateProvider("ledger-content") { content() }
            if (controller.locked) {
                Surface(Modifier.fillMaxSize().clickable(onClick = { }).testTag("app-lock")) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(t("Ledger is locked"), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(16.dp))
                        Text(t("Unlock with your fingerprint or face to access your accounts and expenses."))
                        controller.message?.let { Text(t(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp)) }
                        PrimaryBarButton(onClick = controller::unlock, enabled = !controller.authenticating, modifier = Modifier.padding(top = 24.dp)) {
                            Text(if (controller.authenticating) t("Authenticating…") else t("Unlock"))
                        }
                        BarButton(onClick = controller::openDeviceSettings) { Text(t("Device security settings")) }
                    }
                }
            }
        }
        BackHandler(controller.locked) { /* Cancel/back never reveals the app. */ }
    }
}
