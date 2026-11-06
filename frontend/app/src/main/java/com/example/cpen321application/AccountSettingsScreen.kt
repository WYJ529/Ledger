package com.example.cpen321application

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.GetCredentialCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun AccountSettingsScreen(session: Session, onSaved: (Account) -> Unit, onSessionExpired: () -> Unit, onBack: () -> Unit) {
    val account = session.account
    var name by rememberSaveable(account.id) { mutableStateOf(account.displayName) }
    var email by rememberSaveable(account.id) { mutableStateOf(account.email) }
    var phone by rememberSaveable(account.id) { mutableStateOf(account.phoneNumber) }
    // Credentials deliberately stay out of saved instance state and preferences.
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var changePassword by rememberSaveable { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val request = remember(account.id) { RequestState() }
    var confirmDiscard by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val credentials = remember(context) { CredentialManager.create(context) }
    val scope = rememberCoroutineScope()
    val usesPasswordSignIn = account.signInMethods.contains("password")
    val sensitive = normalizeEmail(email) != account.email || changePassword
    val dirty = name != account.displayName || email != account.email || phone != account.phoneNumber ||
        changePassword
    val goBack: () -> Unit = { if (!request.working) { if (dirty) confirmDiscard = true else onBack() } }
    BackHandler(onBack = goBack)

    fun save() {
        focus.clearFocus(force = true)
        attempted = true
        if (request.working) return
        val invalidProfile = listOf(displayNameError(name), email.takeIf { it.isNotBlank() }?.let(::emailError), phoneNumberError(phone)).any { it != null }
        val invalidPassword = changePassword && listOf(passwordError(newPassword), confirmPasswordError(newPassword, confirmation)).any { it != null }
        val missingIdentity = sensitive && usesPasswordSignIn && currentPassword.isEmpty()
        if (invalidProfile || invalidPassword || missingIdentity) { request.error = "Check the highlighted fields before saving."; return }
        scope.launch {
            request.run(onSessionExpired, onFailure = { failure ->
                request.error = when (failure) {
                    is GetCredentialCancellationException -> "Identity confirmation cancelled. Your changes have not been saved."
                    is org.json.JSONException -> "The server returned an unreadable response. Try again."
                    else -> failure.message ?: "Could not save your changes. Try again."
                }
            }) {
                val payload = JSONObject().put("displayName", normalizeDisplayName(name))
                    .put("phoneNumber", phone.trim())
                if (normalizeEmail(email) != account.email) payload.put("email", normalizeEmail(email))
                if (changePassword) payload.put("newPassword", newPassword)
                if (sensitive) {
                    if (usesPasswordSignIn) payload.put("currentPassword", currentPassword)
                    else payload.put("googleIdToken", requestGoogleIdToken(context, credentials))
                }
                val updated = withContext(Dispatchers.IO) { AccountApi.updateAccount(session.token, payload) }
                currentPassword = ""; newPassword = ""; confirmation = ""
                onSaved(updated)
            }
        }
    }

    BackBar("Back", onClick = goBack, enabled = !request.working)
    Text(t("Account settings"), style = MaterialTheme.typography.headlineMedium)
    if (request.working) LinearProgressIndicator(Modifier.fillMaxWidth())
    request.error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
    SettingsField("Full name", name, { name = it }, request.working, if (attempted) displayNameError(name) else null, tag = "account-name")
    Text(t("Username") + ": " + account.username)
    SettingsField("Email address (optional)", email, { email = it }, request.working, if (attempted) email.takeIf { it.isNotBlank() }?.let(::emailError) else null, KeyboardType.Email, "account-email")
    SettingsField("Phone number (optional)", phone, { phone = it }, request.working, if (attempted) phoneNumberError(phone) else null, KeyboardType.Phone, "account-phone")
    PasswordSettingsEditor(usesPasswordSignIn, changePassword, request.working, attempted, PasswordDraft(newPassword, confirmation),
        onToggle = { changePassword = !changePassword; newPassword = ""; confirmation = "" },
        onPasswords = { password, confirm -> newPassword = password; confirmation = confirm })
    if (sensitive) {
        if (usesPasswordSignIn) PasswordSettingField("Current password", currentPassword, { currentPassword = it }, !request.working,
            if (attempted && currentPassword.isEmpty()) "Confirm your current password." else null)
        else Text(t("Saving an email or password change will ask you to confirm your linked Google account."), style = MaterialTheme.typography.bodySmall)
        Text(t("Changing your email or password signs out your other Ledger sessions."), style = MaterialTheme.typography.bodySmall)
    }
    PrimaryBarButton(onClick = { save() }, enabled = !request.working && dirty, modifier = Modifier.fillMaxWidth().testTag("save-account")) {
        Text(if (request.working) t("Saving…") else t("Save changes"))
    }
    if (confirmDiscard) DiscardAccountChangesDialog(onBack, onKeepEditing = { confirmDiscard = false })
}

@Composable
private fun DiscardAccountChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(onDismissRequest = onKeepEditing, title = { Text(t("Discard changes?")) },
        text = { Text(t("Your account changes have not been saved.")) },
        confirmButton = { BarButton(onClick = onDiscard) { Text(t("Discard")) } },
        dismissButton = { BarButton(onClick = onKeepEditing) { Text(t("Keep editing")) } })
}

@Composable
private fun SettingsField(label: String, value: String, onChange: (String) -> Unit, busy: Boolean, error: String?,
    keyboard: KeyboardType = KeyboardType.Text, tag: String = label) {
    OutlinedTextField(value, onChange, enabled = !busy, label = { Text(t(label)) }, singleLine = true, isError = error != null,
        supportingText = error?.let { { Text(t(it)) } }, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun PasswordSettingField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, error: String?) {
    OutlinedTextField(value, onChange, enabled = enabled, label = { Text(t(label)) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = error != null, supportingText = error?.let { { Text(t(it)) } }, modifier = Modifier.fillMaxWidth())
}

private data class PasswordDraft(val password: String, val confirmation: String)

@Composable
private fun PasswordSettingsEditor(hasPassword: Boolean, changing: Boolean, busy: Boolean, attempted: Boolean,
    draft: PasswordDraft, onToggle: () -> Unit, onPasswords: (String, String) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Password"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(if (hasPassword) "••••••••" else t("You sign in with Google. You can add a Ledger password."))
            BarButton(onClick = onToggle, enabled = !busy) {
                Text(if (changing) t("Cancel password change") else if (hasPassword) t("Change password") else t("Add password"))
            }
            if (changing) {
                PasswordSettingField("New password", draft.password, { onPasswords(it, draft.confirmation) }, !busy, if (attempted) passwordError(draft.password) else null)
                PasswordSettingField("Confirm new password", draft.confirmation, { onPasswords(draft.password, it) }, !busy,
                    if (attempted) confirmPasswordError(draft.password, draft.confirmation) else null)
            }
        }
    }
}
