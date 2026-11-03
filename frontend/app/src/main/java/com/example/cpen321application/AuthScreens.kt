package com.example.cpen321application

import android.content.Context
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

private enum class AuthMode { SignIn, SignUp, GoogleSignUp, GoogleLink }

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

private fun signingCertificateSha1(context: Context): String? = try {
    PackageInfoCompat.getSignatures(context.packageManager, context.packageName).firstOrNull()?.let { sig ->
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
    }
} catch (_: PackageManager.NameNotFoundException) { null }

private val GOOGLE_MODES = listOf(AuthMode.GoogleSignUp, AuthMode.GoogleLink)

/** Sign-in and registration form state. Passwords and Google tokens are never saved to instance state. */
@Stable
private class AuthFormState(mode: AuthMode, username: String, notice: String?) {
    var mode by mutableStateOf(mode)
    var username by mutableStateOf(username)
    var password by mutableStateOf("")
    var confirmation by mutableStateOf("")
    var pendingGoogle by mutableStateOf<GoogleSignInResult.NeedsAccount?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf(notice)
    var validate by mutableStateOf(false)

    /** Google registration state does not survive recreation, so the form falls back to sign-in. */
    val screen: AuthMode get() = if (mode in GOOGLE_MODES && pendingGoogle == null) AuthMode.SignIn else mode

    fun switchTo(next: AuthMode) {
        mode = next
        error = null
        validate = false
        password = ""
        confirmation = ""
        if (next !in GOOGLE_MODES) pendingGoogle = null
    }

    companion object {
        fun saver(notice: String?): Saver<AuthFormState, Any> = listSaver(
            save = { listOf(it.mode, it.username) },
            restore = { AuthFormState(it[0] as AuthMode, it[1] as String, notice) },
        )
    }
}

/** Sign-in and registration requests. Only one runs at a time; failures appear as [AuthFormState.error]. */
private class AuthActions(
    private val form: AuthFormState,
    private val context: Context,
    private val scope: CoroutineScope,
    private val credentials: CredentialManager,
    private val authApi: AuthenticationApi,
    private val googleTokenProvider: suspend (Context, CredentialManager) -> String,
    private val onSignedIn: (Session) -> Unit,
) {
    private fun attempt(action: suspend () -> Unit) {
        if (form.busy) return
        scope.launch {
            form.busy = true
            form.error = null
            try {
                try { handleRequestFailures(::fail) { action() } }
                catch (e: GetCredentialException) { fail(e) }
                catch (e: GoogleIdTokenParsingException) { fail(e) }
            } finally { form.busy = false }
        }
    }

    private fun fail(e: Exception) {
        Log.w("AuthFlow", "Sign-in step failed: ${e.javaClass.simpleName}: ${e.message}")
        form.error = userMessage(context, e)
    }

    private fun signIn(session: Session) {
        form.password = ""
        form.confirmation = ""
        form.pendingGoogle = null
        onSignedIn(session)
    }

    fun continueWithGoogle() = attempt {
        val token = googleTokenProvider(context, credentials)
        when (val result = withContext(Dispatchers.IO) { authApi.signInWithGoogle(token) }) {
            is GoogleSignInResult.SignedIn -> signIn(result.session)
            is GoogleSignInResult.NeedsAccount -> {
                form.pendingGoogle = result
                form.username = ""
                form.switchTo(AuthMode.GoogleSignUp)
            }
        }
    }

    fun submitSignIn() {
        form.validate = true
        if (usernameError(form.username) != null || form.password.isEmpty()) return
        attempt { signIn(withContext(Dispatchers.IO) { authApi.login(normalizeUsername(form.username), form.password) }) }
    }

    fun submitAccount() {
        form.validate = true
        val screen = form.screen
        val passwordsValid = if (screen == AuthMode.GoogleLink) form.password.isNotEmpty()
            else passwordError(form.password) == null && confirmPasswordError(form.password, form.confirmation) == null
        if (usernameError(form.username) != null || !passwordsValid) return
        attempt {
            val username = normalizeUsername(form.username)
            val session = withContext(Dispatchers.IO) {
                when (screen) {
                    AuthMode.GoogleLink -> authApi.linkWithGoogle(requireNotNull(form.pendingGoogle).idToken, username, form.password)
                    AuthMode.GoogleSignUp -> authApi.registerWithGoogle(requireNotNull(form.pendingGoogle).idToken, username,
                        form.password, form.confirmation)
                    else -> authApi.register(username, form.password, form.confirmation)
                }
            }
            signIn(session)
        }
    }

    fun cancelGoogle() {
        scope.launch { forgetGoogleAccount(credentials) }
        form.switchTo(AuthMode.SignIn)
    }
}

/** Sign-in, sign-up and Google account creation. Emits its content into the caller's Column. */
@Composable
internal fun AuthFlow(
    notice: String?, authApi: AuthenticationApi = AccountApi,
    googleTokenProvider: suspend (Context, CredentialManager) -> String = ::requestGoogleIdToken,
    startWithSignUp: Boolean = false,
    onSignedIn: (Session) -> Unit,
) {
    val context = LocalContext.current
    val credentials = remember(context) { CredentialManager.create(context) }
    val startMode = if (startWithSignUp) AuthMode.SignUp else AuthMode.SignIn
    val form = rememberSaveable(saver = AuthFormState.saver(notice)) { AuthFormState(startMode, "", notice) }
    val actions = AuthActions(form, context, rememberCoroutineScope(), credentials, authApi, googleTokenProvider, onSignedIn)
    val screen = form.screen
    BackHandler(screen != AuthMode.SignIn && screen != startMode && !form.busy) {
        if (screen in GOOGLE_MODES) actions.cancelGoogle() else form.switchTo(AuthMode.SignIn)
    }
    Text(t("* Required fields"), style = MaterialTheme.typography.bodySmall)
    if (screen == AuthMode.SignIn) SignInForm(form, actions) else AccountForm(form, actions, screen)
}

@Composable
private fun SignInForm(form: AuthFormState, actions: AuthActions) {
    Text(t("Welcome back"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    UsernameField(form.username, { form.username = it }, enabled = !form.busy, error = if (form.validate) usernameError(form.username) else null)
    PasswordField(form.password, { form.password = it }, "Password", enabled = !form.busy,
        error = if (form.validate && form.password.isEmpty()) "Enter your password." else null, imeAction = ImeAction.Done)
    form.error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
    if (form.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    PrimaryBarButton(enabled = !form.busy, modifier = Modifier.fillMaxWidth(), onClick = actions::submitSignIn) { Text(t("Sign in")) }
    OrDivider()
    GoogleButton("Sign in with Google", enabled = !form.busy) { actions.continueWithGoogle() }
    CenteredTextButton("New user? Sign up here!", enabled = !form.busy) { form.switchTo(AuthMode.SignUp) }
}

@Composable
private fun AccountForm(form: AuthFormState, actions: AuthActions, screen: AuthMode) {
    val isGoogle = screen != AuthMode.SignUp
    val linking = screen == AuthMode.GoogleLink
    Text(t(if (linking) "Link an existing account" else if (isGoogle) "Finish creating your account" else "Create your account"),
        style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Text(t(if (linking) "Enter the username and password of your existing Ledger account."
        else "Choose a username and password for your Ledger account."))
    val googleEmail = form.pendingGoogle?.email.orEmpty()
    if (isGoogle && googleEmail.isNotBlank()) Text(googleEmail)
    UsernameField(form.username, { form.username = it }, enabled = !form.busy, error = if (form.validate) usernameError(form.username) else null)
    val passwordProblem = when {
        !form.validate -> null
        linking -> if (form.password.isEmpty()) "Enter your password." else null
        else -> passwordError(form.password)
    }
    PasswordField(form.password, { form.password = it }, "Password", enabled = !form.busy, error = passwordProblem)
    if (!linking) PasswordField(form.confirmation, { form.confirmation = it }, "Confirm password", enabled = !form.busy,
        error = if (form.validate) confirmPasswordError(form.password, form.confirmation) else null, imeAction = ImeAction.Done)
    form.error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
    if (form.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    PrimaryBarButton(enabled = !form.busy, modifier = Modifier.fillMaxWidth(), onClick = actions::submitAccount) {
        Text(t(if (linking) "Link account" else "Create account"))
    }
    if (isGoogle) {
        CenteredTextButton(if (linking) "Create a new account" else "Link an existing account", enabled = !form.busy) {
            form.switchTo(if (linking) AuthMode.GoogleSignUp else AuthMode.GoogleLink)
        }
        CenteredTextButton("Cancel", enabled = !form.busy) { actions.cancelGoogle() }
    } else {
        OrDivider()
        GoogleButton("Sign up with Google", enabled = !form.busy) { actions.continueWithGoogle() }
        CenteredTextButton("Already have an account? Sign in", enabled = !form.busy) { form.switchTo(AuthMode.SignIn) }
    }
}

@Composable
private fun UsernameField(value: String, onChange: (String) -> Unit, enabled: Boolean, error: String?) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(30)) }, label = { Text(t("Username *")) },
        enabled = enabled, singleLine = true, isError = error != null,
        supportingText = error?.let { { Text(t(it)) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(
    value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, error: String?,
    supporting: String? = null, imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(PASSWORD_MAX_LENGTH + 1)) }, label = { Text("${t(label)} *") },
        enabled = enabled, singleLine = true, isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(t(it)) } },
        visualTransformation = PasswordVisualTransformation(),
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
        BarButton(enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(t(label)) }
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

@Composable
internal fun SignInPage(notice: String?, onSignedIn: (Session) -> Unit, onBack: () -> Unit, startWithSignUp: Boolean = false) {
    BackBar("Back", onClick = onBack)
    AuthFlow(notice = notice, onSignedIn = onSignedIn, startWithSignUp = startWithSignUp)
}
