package com.example.cpen321application

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.credentials.exceptions.GetCredentialException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId

@Composable
internal fun AccountSettingsScreen(session: Session, onSaved: (Account) -> Unit, onSessionExpired: () -> Unit, onBack: () -> Unit) {
    val account = session.account
    var name by rememberSaveable(account.id) { mutableStateOf(account.displayName) }
    var email by rememberSaveable(account.id) { mutableStateOf(account.email) }
    var phone by rememberSaveable(account.id) { mutableStateOf(account.phoneNumber) }
    var zone by rememberSaveable(account.id) { mutableStateOf(account.timeZone) }
    var currency by rememberSaveable(account.id) { mutableStateOf(account.defaultCurrency) }
    // Credentials deliberately stay out of saved instance state and preferences.
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var changePassword by rememberSaveable { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showZones by remember { mutableStateOf(false) }
    var showCurrencies by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val credentials = remember(context) { CredentialManager.create(context) }
    val scope = rememberCoroutineScope()
    val usesPasswordSignIn = account.signInMethods.contains("password")
    val sensitive = normalizeEmail(email) != account.email || changePassword
    val dirty = name != account.displayName || email != account.email || phone != account.phoneNumber ||
        zone != account.timeZone || currency != account.defaultCurrency || changePassword
    val goBack: () -> Unit = { if (!busy) { if (dirty) confirmDiscard = true else onBack() } }
    BackHandler(onBack = goBack)

    fun save() {
        focus.clearFocus(force = true)
        attempted = true
        if (busy) return
        val invalidProfile = listOf(displayNameError(name), emailError(email), phoneNumberError(phone)).any { it != null }
        val invalidPassword = changePassword && listOf(passwordError(newPassword), confirmPasswordError(newPassword, confirmation)).any { it != null }
        val missingIdentity = sensitive && usesPasswordSignIn && currentPassword.isEmpty()
        if (invalidProfile || invalidPassword || missingIdentity) { error = "Check the highlighted fields before saving."; return }
        busy = true
        error = null
        scope.launch {
            try {
                val payload = JSONObject().put("displayName", normalizeDisplayName(name)).put("email", normalizeEmail(email))
                    .put("phoneNumber", phone.trim()).put("timeZone", zone).put("defaultCurrency", currency)
                if (changePassword) payload.put("newPassword", newPassword)
                if (sensitive) {
                    if (usesPasswordSignIn) payload.put("currentPassword", currentPassword)
                    else payload.put("googleIdToken", requestGoogleIdToken(context, credentials))
                }
                val updated = withContext(Dispatchers.IO) { AccountApi.updateAccount(session.token, payload) }
                currentPassword = ""; newPassword = ""; confirmation = ""
                onSaved(updated)
            } catch (_: GetCredentialCancellationException) { error = "Identity confirmation cancelled. Your changes have not been saved." }
            catch (e: CancellationException) { throw e }
            catch (e: ApiException) { if (e.status == 401) onSessionExpired() else error = e.message }
            catch (e: GetCredentialException) { error = e.message ?: "Could not confirm your Google account. Try again." }
            catch (e: java.io.IOException) { error = e.message ?: "Could not save your changes. Try again." }
            catch (_: org.json.JSONException) { error = "The server returned an unreadable response. Try again." }
            catch (e: IllegalArgumentException) { error = e.message ?: "Check your account settings." }
            catch (e: IllegalStateException) { error = e.message ?: "Could not confirm your account." }
            finally { busy = false }
        }
    }

    TextButton(onClick = goBack, enabled = !busy) { Text("‹ Back") }
    Text("Account settings", style = MaterialTheme.typography.headlineMedium)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    SettingsField("Full name", name, { name = it }, busy, if (attempted) displayNameError(name) else null, tag = "account-name")
    SettingsField("Email address", email, { email = it }, busy, if (attempted) emailError(email) else null, KeyboardType.Email, "account-email")
    SettingsField("Phone number (optional)", phone, { phone = it }, busy, if (attempted) phoneNumberError(phone) else null, KeyboardType.Phone, "account-phone")
    Text("Include your country code. This is a contact number; phone verification is not available yet.", style = MaterialTheme.typography.bodySmall)
    PasswordSettingsEditor(usesPasswordSignIn, changePassword, busy, attempted, PasswordDraft(newPassword, confirmation),
        onToggle = { changePassword = !changePassword; newPassword = ""; confirmation = "" },
        onPasswords = { password, confirm -> newPassword = password; confirmation = confirm })
    AccountMenuRow("Time zone", zone.replace('_', ' '), !busy) { showZones = true }
    AccountMenuRow("Default currency", currency, !busy) { showCurrencies = true }
    Text("Used for new personal transactions and their default date. Existing transactions and reporting currency stay as they are.", style = MaterialTheme.typography.bodySmall)
    if (sensitive) {
        if (usesPasswordSignIn) PasswordSettingField("Current password", currentPassword, { currentPassword = it }, !busy,
            if (attempted && currentPassword.isEmpty()) "Confirm your current password." else null)
        else Text("Saving an email or password change will ask you to confirm your linked Google account.", style = MaterialTheme.typography.bodySmall)
        Text("Changing your email or password signs out your other Ledger sessions.", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = { save() }, enabled = !busy && dirty, modifier = Modifier.fillMaxWidth().testTag("save-account")) {
        Text(if (busy) "Saving…" else "Save changes")
    }
    if (showZones) {
        val zones = remember { (listOf("UTC") + ZoneId.getAvailableZoneIds().sorted()).distinct() }
        SettingChoiceDialog("Time zone", zones, zone, onSelect = { zone = it; showZones = false }, onDismiss = { showZones = false })
    }
    if (showCurrencies) SettingChoiceDialog("Default currency", LedgerDomain.currencies, currency,
        onSelect = { currency = it; showCurrencies = false }, onDismiss = { showCurrencies = false })
    if (confirmDiscard) DiscardAccountChangesDialog(onBack, onKeepEditing = { confirmDiscard = false })
}

@Composable
private fun DiscardAccountChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(onDismissRequest = onKeepEditing, title = { Text("Discard changes?") },
        text = { Text("Your account changes have not been saved.") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text("Keep editing") } })
}

@Composable
private fun SettingsField(label: String, value: String, onChange: (String) -> Unit, busy: Boolean, error: String?,
    keyboard: KeyboardType = KeyboardType.Text, tag: String = label) {
    OutlinedTextField(value, onChange, enabled = !busy, label = { Text(label) }, singleLine = true, isError = error != null,
        supportingText = error?.let { { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun PasswordSettingField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, error: String?) {
    OutlinedTextField(value, onChange, enabled = enabled, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = error != null, supportingText = error?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SettingChoiceDialog(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            if (options.size > 10) OutlinedTextField(search, { search = it }, label = { Text("Search time zones") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(options.filter { it.replace('_', ' ').contains(search, ignoreCase = true) }, key = { it }) { value ->
                    TextButton(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (value == selected) "✓ " else "") + value.replace('_', ' '))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private data class PasswordDraft(val password: String, val confirmation: String)

@Composable
private fun PasswordSettingsEditor(hasPassword: Boolean, changing: Boolean, busy: Boolean, attempted: Boolean,
    draft: PasswordDraft, onToggle: () -> Unit, onPasswords: (String, String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Password", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(if (hasPassword) "••••••••" else "You sign in with Google. You can add a Ledger password.")
            TextButton(onClick = onToggle, enabled = !busy) {
                Text(if (changing) "Cancel password change" else if (hasPassword) "Change password" else "Add password")
            }
            if (changing) {
                PasswordSettingField("New password", draft.password, { onPasswords(it, draft.confirmation) }, !busy, if (attempted) passwordError(draft.password) else null)
                PasswordSettingField("Confirm new password", draft.confirmation, { onPasswords(draft.password, it) }, !busy,
                    if (attempted) confirmPasswordError(draft.password, draft.confirmation) else null)
            }
        }
    }
}
