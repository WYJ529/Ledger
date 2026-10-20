package com.example.cpen321application

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Server information for the signed-in user; sign-in itself happens in [AuthFlow]. */
@Composable
internal fun ServerInfoScreen(account: Account) {
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var fields by remember { mutableStateOf(emptyList<Pair<String, String>>()) }

    LaunchedEffect(refresh) {
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

    Text("Server info", style = MaterialTheme.typography.headlineMedium)
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text("Signed in as ${account.displayName}", style = MaterialTheme.typography.titleMedium)
    fields.forEach { (label, value) ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(value)
            }
        }
    }
    Button(enabled = !loading, onClick = { refresh++ }) { Text("Refresh server info") }
}
