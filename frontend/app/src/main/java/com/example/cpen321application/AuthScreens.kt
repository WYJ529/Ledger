package com.example.cpen321application

import android.content.Context
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

private enum class AuthMode { SignIn, SignUp, GoogleSignUp }

internal val googleSignInConfigured: Boolean
    get() = BuildConfig.GOOGLE_CLIENT_ID.endsWith(".apps.googleusercontent.com")

/** Opens the Google account picker and returns a Google ID token for the backend to verify. */
internal suspend fun requestGoogleIdToken(context: Context, credentials: CredentialManager): String {
    check(googleSignInConfigured) {
        "Google sign-in is not set up in this build. Set GOOGLE_CLIENT_ID in frontend/local.properties to the Web OAuth client ID, then rebuild."
    }
    val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = credentials.getCredential(context = MutableContextWrapper(context), request = request).credential
    require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        "Unsupported sign-in response."
    }
    return GoogleIdTokenCredential.createFrom(credential.data).idToken
}

/** Clears the Google account choice so the picker appears again next time. */
internal suspend fun forgetGoogleAccount(credentials: CredentialManager): Boolean = try {
    credentials.clearCredentialState(ClearCredentialStateRequest())
    true
} catch (e: CancellationException) { throw e } catch (_: Exception) { false }

private fun userMessage(context: Context, error: Exception): String = when {
    error is GetCredentialCancellationException -> "Sign-in cancelled. You can try again."
    error is NoCredentialException -> "No Google account available. Add an account in the device settings and check the OAuth setup."
    // Play services reports 28444 when this package + signing key has no Android OAuth client.
    error is GetCredentialException && error.message.orEmpty().contains("28444") ->
        tf("Google sign-in is not registered for this build's signing key. Add an Android OAuth client for %1\$s with SHA-1 %2\$s in the Google Cloud project, then try again.",
            context.packageName, signingCertificateSha1(context) ?: "(unknown)")
    error is GetCredentialException -> "Google sign-in failed. Check the Google account, Android OAuth package/SHA-1, and Web client ID."
    else -> error.message ?: "Something went wrong. Please try again."
}

/** SHA-1 of the certificate that signed this APK, formatted like the Google Cloud console. */
private fun signingCertificateSha1(context: Context): String? = try {
    val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    }
    signature?.let { sig ->
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
    }
} catch (_: Exception) { null }

/** Sign-in, sign-up and Google account creation. Emits its content into the caller's Column. */
@Composable
internal fun AuthFlow(notice: String?, onSignedIn: (Session) -> Unit) {
    val context = LocalContext.current
    val credentials = remember(context) { CredentialManager.create(context) }
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(AuthMode.SignIn) }
    var email by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    // Passwords and Google tokens stay out of saved instance state.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var pendingGoogle by remember { mutableStateOf<GoogleSignInResult.NeedsAccount?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(notice) }
    var validate by remember { mutableStateOf(false) }
    // The pending Google token does not survive recreation; fall back to sign-in.
    val screen = if (mode == AuthMode.GoogleSignUp && pendingGoogle == null) AuthMode.SignIn else mode

    fun switchTo(next: AuthMode) {
        mode = next
        error = null
        validate = false
        password = ""
        confirmation = ""
        if (next != AuthMode.GoogleSignUp) pendingGoogle = null
    }

    fun attempt(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("AuthFlow", "Sign-in step failed: ${e.javaClass.simpleName}: ${e.message}")
                error = userMessage(context, e)
            }
            finally { busy = false }
        }
    }

    fun signIn(session: Session) {
        password = ""
        confirmation = ""
        onSignedIn(session)
    }

    fun continueWithGoogle() = attempt {
        val token = requestGoogleIdToken(context, credentials)
        when (val result = withContext(Dispatchers.IO) { AccountApi.signInWithGoogle(token) }) {
            is GoogleSignInResult.SignedIn -> signIn(result.session)
            is GoogleSignInResult.NeedsAccount -> {
                pendingGoogle = result
                displayName = result.displayName
                switchTo(AuthMode.GoogleSignUp)
            }
        }
    }

    BackHandler(screen != AuthMode.SignIn && !busy) {
        if (screen == AuthMode.GoogleSignUp) scope.launch { forgetGoogleAccount(credentials) }
        switchTo(AuthMode.SignIn)
    }

    Text(t("* Required fields"), style = MaterialTheme.typography.bodySmall)
    when (screen) {
        AuthMode.SignIn -> {
            Text(t("Welcome back"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(t("Sign in with your Ledger account or Google."))
            EmailField(email, { email = it }, enabled = !busy, error = if (validate) emailError(email) else null)
            PasswordField(password, { password = it }, "Password", enabled = !busy,
                error = if (validate && password.isEmpty()) "Enter your password." else null, imeAction = ImeAction.Done)
            error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            PrimaryBarButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                validate = true
                if (emailError(email) == null && password.isNotEmpty()) attempt {
                    signIn(withContext(Dispatchers.IO) { AccountApi.login(normalizeEmail(email), password) })
                }
            }) { Text(t("Sign in")) }
            OrDivider()
            GoogleButton("Sign in with Google", enabled = !busy) { continueWithGoogle() }
            CenteredTextButton("New user? Sign up here!", enabled = !busy) { switchTo(AuthMode.SignUp) }
        }
        AuthMode.SignUp -> {
            Text(t("Create your account"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(t("Use your email and a password, or sign up with Google."))
            NameField(displayName, { displayName = it }, enabled = !busy, error = if (validate) displayNameError(displayName) else null)
            EmailField(email, { email = it }, enabled = !busy, error = if (validate) emailError(email) else null)
            PasswordField(password, { password = it }, "Password", enabled = !busy,
                error = if (validate) passwordError(password) else null,
                supporting = tf("At least %1\$d characters", PASSWORD_MIN_LENGTH))
            PasswordField(confirmation, { confirmation = it }, "Confirm password", enabled = !busy,
                error = if (validate) confirmPasswordError(password, confirmation) else null, imeAction = ImeAction.Done)
            error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            PrimaryBarButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                validate = true
                val valid = listOf(displayNameError(displayName), emailError(email), passwordError(password),
                    confirmPasswordError(password, confirmation)).all { it == null }
                if (valid) attempt {
                    signIn(withContext(Dispatchers.IO) {
                        AccountApi.register(normalizeEmail(email), password, normalizeDisplayName(displayName))
                    })
                }
            }) { Text(t("Create account")) }
            OrDivider()
            GoogleButton("Sign up with Google", enabled = !busy) { continueWithGoogle() }
            CenteredTextButton("Already have an account? Sign in", enabled = !busy) { switchTo(AuthMode.SignIn) }
        }
        AuthMode.GoogleSignUp -> {
            val google = pendingGoogle ?: return
            Text(t("Finish creating your account"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("There is no Ledger account for ${google.email} yet. Choose the name other members will see.")
            NameField(displayName, { displayName = it }, enabled = !busy,
                error = if (validate) displayNameError(displayName) else null, imeAction = ImeAction.Done)
            OutlinedTextField(value = google.email, onValueChange = {}, readOnly = true, enabled = false,
                label = { Text(t("Google account")) }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            PrimaryBarButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                validate = true
                if (displayNameError(displayName) == null) attempt {
                    signIn(withContext(Dispatchers.IO) {
                        AccountApi.registerWithGoogle(google.idToken, normalizeDisplayName(displayName))
                    })
                }
            }) { Text(t("Create account")) }
            BarButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch { forgetGoogleAccount(credentials) }
                switchTo(AuthMode.SignIn)
            }) { Text(t("Cancel")) }
        }
    }
}

@Composable
private fun EmailField(value: String, onChange: (String) -> Unit, enabled: Boolean, error: String?) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(254)) }, label = { Text(t("Email *")) },
        enabled = enabled, singleLine = true, isError = error != null,
        supportingText = error?.let { { Text(t(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, enabled: Boolean, error: String?, imeAction: ImeAction = ImeAction.Next) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(DISPLAY_NAME_MAX_LENGTH + 20)) }, label = { Text(t("Name *")) },
        enabled = enabled, singleLine = true, isError = error != null,
        supportingText = error?.let { { Text(t(it)) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(
    value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, error: String?,
    supporting: String? = null, imeAction: ImeAction = ImeAction.Next,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(PASSWORD_MAX_LENGTH + 1)) }, label = { Text("${t(label)} *") },
        enabled = enabled, singleLine = true, isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(t(it)) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { BarButton(onClick = { visible = !visible }) { Text(if (visible) t("Hide") else t("Show")) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GoogleButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    // Stays tappable when misconfigured so the user gets an explanation instead of a grey button.
    BarButton(enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(t(label)) }
}

@Composable
private fun CenteredTextButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BarButton(enabled = enabled, onClick = onClick) { Text(t(label)) }
    }
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f))
        Text(t("or"), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.weight(1f))
    }
}
