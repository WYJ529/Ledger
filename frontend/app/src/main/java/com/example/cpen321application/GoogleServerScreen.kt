package com.example.cpen321application

import android.content.MutableContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun GoogleServerScreen() {
    val context = LocalContext.current
    val credentials = remember(context) { CredentialManager.create(context) }
    val scope = rememberCoroutineScope()
    var userName by rememberSaveable { mutableStateOf<String?>(null) }
    var expiresAt by rememberSaveable { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var fields by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    val configured = BuildConfig.GOOGLE_CLIENT_ID.endsWith(".apps.googleusercontent.com")

    suspend fun signIn() {
        if (busy) return
        busy = true
        error = null
        try {
            val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val result = credentials.getCredential(context = MutableContextWrapper(context), request = request)
            val credential = result.credential
            require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                "Unsupported sign-in response."
            }
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val verified = withContext(Dispatchers.IO) { verifyOnBackend(token) }
            val user = verified.getJSONObject("user")
            val fullName = listOf(user.optString("firstName"), user.optString("lastName")).filter { it.isNotBlank() }.joinToString(" ")
            expiresAt = verified.getLong("expiresAt")
            userName = fullName.ifBlank { user.optString("displayName").ifBlank { "Name not provided by Google" } }
        } catch (e: CancellationException) { throw e }
        catch (_: GetCredentialCancellationException) { error = "Sign-in cancelled. You can try again." }
        catch (_: NoCredentialException) { error = "No Google account available. Add an account in the device settings and check the OAuth setup." }
        catch (_: GetCredentialException) { error = "Google sign-in failed. Check the Google account, Android OAuth package/SHA-1, and Web client ID." }
        catch (e: Exception) { error = e.message ?: "Sign-in failed. Please try again." }
        finally { busy = false }
    }

    LaunchedEffect(Unit) { if (configured && userName == null) signIn() }
    LaunchedEffect(userName, refresh) {
        if (userName == null) { fields = emptyList(); return@LaunchedEffect }
        if (System.currentTimeMillis() >= expiresAt) {
            userName = null
            error = "Your sign-in has expired. Please sign in again."
            return@LaunchedEffect
        }
        loading = true
        try {
            fields = withContext(Dispatchers.IO) {
                val ip = apiValue("/api/server/ip") { "${it.getString("ip")} (${it.getString("source")})" }
                val time = apiValue("/api/server/time") { it.getString("time") }
                val name = apiValue("/api/server/name") { "${it.getString("firstName")} ${it.getString("lastName")}" }
                listOf("Server IP" to ip, "Client IP" to clientIp(), "Server local time" to time,
                    "Client local time" to ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss 'GMT'xxx", Locale.US)),
                    "Developer name" to name)
            }
        } finally { loading = false }
    }

    Text("Login + Server", style = MaterialTheme.typography.headlineMedium)
    if (!configured) Text("Google sign-in setup is pending. Add the Web OAuth client ID to frontend/local.properties and backend/.env, then rebuild and restart.")
    if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
    if (busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (userName == null) {
        Text("Sign in to view your name and the server information.")
        Button(enabled = configured && !busy, onClick = { scope.launch { signIn() } }) { Text("Sign in with Google") }
    } else {
        Text("Signed in as $userName", style = MaterialTheme.typography.titleMedium)
        fields.forEach { (label, value) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(value)
                }
            }
        }
        Button(enabled = !loading && !busy, onClick = { refresh++ }) { Text("Refresh server info") }
        OutlinedButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                userName = null
                expiresAt = 0
                fields = emptyList()
                error = null
                try { credentials.clearCredentialState(ClearCredentialStateRequest()) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Signed out locally; Google account selection could not be reset." }
                finally { busy = false }
            }
        }) { Text("Sign out") }
    }
}

private fun verifyOnBackend(token: String): JSONObject {
    val url = URL(BuildConfig.API_BASE_URL.trimEnd('/') + "/api/auth/google")
    require(url.protocol == "https" || (BuildConfig.DEBUG && url.protocol == "http")) {
        "Release builds require an HTTPS backend."
    }
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val body = JSONObject().put("idToken", token).toString().toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        val result = JSONObject(text)
        check(status in 200..299) { result.optString("error", "Backend verification failed (HTTP $status).") }
        return result
    } finally { connection.disconnect() }
}
