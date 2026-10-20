package com.example.cpen321application

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/** Shows the signed-in account (refreshed from the backend) and handles logout. */
@Composable
internal fun AccountScreen(
    session: Session,
    onAccountUpdated: (Account) -> Unit,
    onSessionExpired: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val context = LocalContext.current
    val credentials = remember(context) { CredentialManager.create(context) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val account = session.account

    LaunchedEffect(session.token) {
        loading = true
        notice = null
        try {
            onAccountUpdated(withContext(Dispatchers.IO) { AccountApi.currentAccount(session.token) })
        } catch (e: CancellationException) { throw e }
        catch (e: ApiException) { if (e.status == 401) onSessionExpired() else notice = e.message }
        catch (e: Exception) { notice = "Showing saved details. ${e.message ?: "Could not refresh."}" }
        finally { loading = false }
    }

    fun logOut() {
        if (loggingOut) return
        loggingOut = true
        scope.launch {
            try {
                // Best effort: if the server is unreachable the session still expires on its own.
                withContext(Dispatchers.IO) { AccountApi.logout(session.token) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            forgetGoogleAccount(credentials)
            loggingOut = false
            onLoggedOut()
        }
    }

    Text("Me", style = MaterialTheme.typography.headlineMedium)
    if (loading || loggingOut) LinearProgressIndicator(Modifier.fillMaxWidth())
    notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val methods = account.signInMethods.map { if (it == "google") "Google" else "Email and password" }
    listOf(
        "Name" to account.displayName,
        "Email" to account.email,
        "Sign-in method" to methods.ifEmpty { listOf("Unknown") }.joinToString(", "),
        "Member since" to formatDate(account.createdAt),
        "Signed in until" to formatDate(Instant.ofEpochMilli(session.expiresAt).toString()),
    ).forEach { (label, value) ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(value)
            }
        }
    }
    Button(
        enabled = !loggingOut, onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) { Text("Log out") }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("You can keep using My Expenses. Sign in again to access Group and Me.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; logOut() }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

private fun formatDate(iso: String): String = try {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
} catch (_: Exception) { "Unknown" }
