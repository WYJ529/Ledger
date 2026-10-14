package com.example.cpen321application

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CPEN321ApplicationTheme { PixelApp() } }
    }
}

@Composable
private fun PixelApp() {
    var page by rememberSaveable { mutableStateOf("home") }
    var minutes by rememberSaveable { mutableStateOf("0") }
    var seconds by rememberSaveable { mutableStateOf("10") }
    var deadline by rememberSaveable { mutableStateOf(0L) }
    var remaining by remember { mutableStateOf(0L) }
    var timerError by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(deadline) {
        if (deadline > 0) {
            while (true) {
                val millis = deadline - SystemClock.elapsedRealtime()
                remaining = ((millis + 999) / 1000).coerceAtLeast(0)
                if (millis <= 0) {
                    deadline = 0
                    page = "surprise"
                    break
                }
                delay(100)
            }
        } else remaining = 0
    }
    BackHandler(page != "home") { page = "home" }
    val scrollState = rememberScrollState()
    LaunchedEffect(page) { scrollState.scrollTo(0) }
    Scaffold { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(scrollState).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (page != "home") TextButton(onClick = { page = "home" }) { Text("Back to home") }
            Text("PIXEL / M1", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when (page) {
                "home" -> {
                    Text("Small buttons.\nBig possibilities.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Explore your server, watch pixels come alive, or make time for a surprise.")
                    MenuButton("01  Login + Server", "Inspect your backend connection") { page = "server" }
                    MenuButton("02  Live Updates", "A 16 x 16 canvas for the live stream") { page = "live" }
                    MenuButton("03  Timer", "A countdown with a recipe surprise") { page = "timer" }
                    if (deadline > 0) Text("Timer running: ${formatDuration(remaining)}")
                }
                "server" -> GoogleServerScreen()
                "live" -> LiveUpdatesScreen()
                "timer" -> {
                    Text("Make time for a surprise", style = MaterialTheme.typography.headlineMedium)
                    Text("When the countdown ends, answer three quick food questions and discover your top three recipe matches.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = minutes, onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) minutes = it },
                            label = { Text("Minutes") }, enabled = deadline == 0L, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = seconds, onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) seconds = it },
                            label = { Text("Seconds") }, enabled = deadline == 0L, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    if (timerError.isNotBlank()) Text(timerError, color = MaterialTheme.colorScheme.error)
                    if (deadline > 0) {
                        Text(formatDuration(remaining), style = MaterialTheme.typography.displayLarge)
                        OutlinedButton(onClick = { deadline = 0 }) { Text("Cancel timer") }
                    } else {
                        Button(onClick = {
                            val m = minutes.toIntOrNull() ?: 0
                            val s = seconds.toIntOrNull() ?: 0
                            if (s !in 0..59 || m * 60 + s == 0) timerError = "Choose a positive duration; seconds must be 0-59."
                            else { timerError = ""; deadline = SystemClock.elapsedRealtime() + (m * 60L + s) * 1000 }
                        }) { Text("Start countdown") }
                    }
                    Text("You can explore other pages while it runs. Keep the app open for the surprise; background alarms are not implemented.", style = MaterialTheme.typography.bodySmall)
                }
                "surprise" -> MealSurpriseScreen(onSetTimer = { page = "timer" })
            }
        }
    }
}

@Composable
private fun MenuButton(title: String, subtitle: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun apiValue(path: String, extract: (JSONObject) -> String): String {
    var connection: HttpURLConnection? = null
    return try {
        connection = URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        val json = JSONObject(body)
        if (code in 200..299) extract(json) else "HTTP $code: ${json.optString("error", "Request failed")}"
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { "Unavailable: ${e.message ?: "Connection failed"}" }
    finally { connection?.disconnect() }
}

internal fun clientIp(): String = try {
    NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress ?: "Unavailable"
} catch (_: Exception) { "Unavailable" }

private fun formatDuration(seconds: Long): String = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

@Composable
internal fun PixelCanvas(pixels: List<Color>, onTap: (() -> Unit)? = null) {
    val tapModifier = if (onTap != null) Modifier.pointerInput(onTap) { detectTapGestures { onTap() } } else Modifier
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFFF1F3F7)).then(tapModifier)) {
        val cell = size.width / 16
        pixels.forEachIndexed { index, color ->
            drawRect(color, Offset((index % 16) * cell, (index / 16) * cell), Size(cell, cell))
        }
        for (i in 0..16) {
            drawLine(Color(0x22000000), Offset(i * cell, 0f), Offset(i * cell, size.height), 1f)
            drawLine(Color(0x22000000), Offset(0f, i * cell), Offset(size.width, i * cell), 1f)
        }
    }
}
