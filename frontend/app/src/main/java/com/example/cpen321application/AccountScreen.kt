package com.example.cpen321application

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class AccountPage { Home, Settings, Notifications, Security, Contact }

/** Account and device settings retain the app's existing Material styling. */
@Composable
internal fun AccountScreen(session: Session, onAccountUpdated: (Account) -> Unit, onSessionExpired: () -> Unit,
    onLoggedOut: () -> Unit, onNavigate: suspend () -> Unit = {}) {
    val context = LocalContext.current
    val credentials = remember(context) { CredentialManager.create(context) }
    val scope = rememberCoroutineScope()
    var page by rememberSaveable(session.account.id) { mutableStateOf(AccountPage.Home) }
    var loggingOut by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showRatingUnavailable by remember { mutableStateOf(false) }
    val preferences = remember(session.account.id) { AccountPreferences(context, session.account.id) }
    LaunchedEffect(page) { onNavigate() }
    BackHandler(page != AccountPage.Home && page != AccountPage.Settings) { page = AccountPage.Home }

    fun logOut() {
        if (loggingOut) return
        loggingOut = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { AccountApi.logout(session.token) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Offline logout still clears this device. */ }
            forgetGoogleAccount(credentials)
            loggingOut = false
            onLoggedOut()
        }
    }

    if (page == AccountPage.Settings) {
        AccountSettingsScreen(session, onSaved = { account ->
            onAccountUpdated(account); notice = "Account changes saved."; page = AccountPage.Home
        }, onSessionExpired = onSessionExpired, onBack = { page = AccountPage.Home })
        return
    }
    if (page != AccountPage.Home) BackBar("Back", onClick = { page = AccountPage.Home })
    when (page) {
        AccountPage.Home -> {
            Text("Me", style = MaterialTheme.typography.headlineMedium)
            if (loggingOut) LinearProgressIndicator(Modifier.fillMaxWidth())
            notice?.let { Text(it, modifier = Modifier.testTag("account-notice")) }
            AccountProfileCard(session, !loggingOut, onEdit = { page = AccountPage.Settings }, onUpdated = onAccountUpdated,
                onSessionExpired = onSessionExpired, onNotice = { notice = it })
            AccountSectionTitle("Preferences")
            AccountMenuRow("Notifications", "Choose which updates you want to receive", !loggingOut) { page = AccountPage.Notifications }
            AccountMenuRow("Security", "Fingerprint or face unlock", !loggingOut) { page = AccountPage.Security }
            AccountSectionTitle("Help & Support")
            AccountMenuRow("Contact Us", "Get help or share feedback", !loggingOut) { page = AccountPage.Contact }
            AccountMenuRow("Rate Ledger", enabled = !loggingOut) {
                if (BuildConfig.LEDGER_STORE_URL.isBlank()) showRatingUnavailable = true
                else openExternalPage(context, BuildConfig.LEDGER_STORE_URL) { notice = it }
            }
            AccountInformation(session)
            PrimaryBarButton(enabled = !loggingOut, onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth().testTag("log-out"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Log out") }
            Text("Ledger ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
        }
        AccountPage.Notifications -> NotificationSettings(preferences)
        AccountPage.Security -> SecuritySettings()
        AccountPage.Contact -> ContactSettings()
        AccountPage.Settings -> Unit
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("Log out?") },
        text = { Text("You can keep using My Expenses. Sign in again to access Group and Me.") },
        confirmButton = { BarButton(onClick = { confirmLogout = false; logOut() }) { Text("Log out") } },
        dismissButton = { BarButton(onClick = { confirmLogout = false }) { Text("Cancel") } })
    if (showRatingUnavailable) AlertDialog(onDismissRequest = { showRatingUnavailable = false }, title = { Text("Rate Ledger") },
        text = { Text("Store ratings are not available for this build yet. You can share feedback through Contact Us.") },
        confirmButton = { BarButton(onClick = { showRatingUnavailable = false; page = AccountPage.Contact }) { Text("Contact Us") } },
        dismissButton = { BarButton(onClick = { showRatingUnavailable = false }) { Text("Close") } })
}

@Composable
private fun AccountInformation(session: Session) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Account information", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val methods = session.account.signInMethods.map { if (it == "google") "Google" else "Email and password" }
            Text("Sign-in method: ${methods.joinToString(", ")}")
            Text("Member since: ${formatAccountDate(session.account.createdAt, session.account.timeZone)}")
            Text("Signed in until: ${formatAccountDate(Instant.ofEpochMilli(session.expiresAt).toString(), session.account.timeZone)}")
        }
    }
}

@Composable
internal fun AccountMenuRow(title: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Panel(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("account-row-$title")) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun AccountSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun NotificationSettings(preferences: AccountPreferences) {
    var enabled by remember { mutableStateOf(preferences.notificationsEnabled) }
    Text("Notifications", style = MaterialTheme.typography.headlineMedium)
    Text("Preview only: these preferences are saved on this device. Ledger will not request push permission or send notifications yet.", style = MaterialTheme.typography.bodyMedium)
    PreferenceSwitch("Allow notifications", "Turn all notification preferences on or off.", enabled, true) {
        enabled = it; preferences.notificationsEnabled = it
    }
    NotificationPreference.entries.forEach { preference ->
        var checked by remember(preferences, preference) { mutableStateOf(preferences.enabled(preference)) }
        PreferenceSwitch(preference.title, preference.description, checked, enabled) {
            checked = it; preferences.setEnabled(preference, it)
        }
    }
}

@Composable
private fun SecuritySettings() {
    val lock = LocalAppLock.current
    Text("Security", style = MaterialTheme.typography.headlineMedium)
    PreferenceSwitch("Require biometric unlock", "Use your fingerprint or face when opening Ledger or returning from the background.",
        lock.enabled, !lock.authenticating, lock::requestEnabled)
    Text("This setting protects Ledger on this device, including My Expenses after you log out. Your biometric data stays with Android.", style = MaterialTheme.typography.bodySmall)
    lock.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    BarButton(onClick = lock::openDeviceSettings) { Text("Device security settings") }
}

@Composable
private fun PreferenceSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag("preference-$title"))
        }
    }
}

@Composable
private fun ContactSettings() {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    Text("Contact Us", style = MaterialTheme.typography.headlineMedium)
    Text("Report an issue, ask for help, or suggest an improvement.")
    Text("The support page opens in your browser. Please do not include passwords, bank statements, or personal financial details in a public report.", style = MaterialTheme.typography.bodySmall)
    PrimaryBarButton(onClick = { openExternalPage(context, BuildConfig.LEDGER_SUPPORT_URL) { error = it } }, modifier = Modifier.fillMaxWidth()) { Text("Open support page") }
    Text("Ledger ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private fun openExternalPage(context: android.content.Context, url: String, onError: (String) -> Unit) {
    try {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank())
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: Exception) { onError("Could not open the page. Check that a browser is installed and try again.") }
}

private fun formatAccountDate(iso: String, zone: String): String = try {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.parse(iso).atZone(ZoneId.of(zone)))
} catch (_: Exception) { "Unknown" }
