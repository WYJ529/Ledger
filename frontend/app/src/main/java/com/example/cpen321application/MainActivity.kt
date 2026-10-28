package com.example.cpen321application

import android.os.Bundle
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.ViewModelProvider
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

class MainActivity : FragmentActivity() {
    private lateinit var appLock: AppLockController
    private var invitationLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appLock = AppLockController(this)
        if (intent?.action == Intent.ACTION_VIEW) invitationLink = intent?.dataString
        val ledger = ViewModelProvider(this)[LedgerViewModel::class.java]
        setContent { CPEN321ApplicationTheme { AppLockGate(appLock) { LedgerApp(ledger, invitationLink, onInvitationHandled = {
            invitationLink = null
            intent?.let { setIntent(Intent(it).setData(null)) }
        }) } } }
    }

    override fun onStart() {
        super.onStart()
        appLock.onStart()
    }

    override fun onStop() {
        appLock.onStop(isChangingConfigurations)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) invitationLink = intent.dataString
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
    catch (e: IOException) { "Unavailable: ${e.message ?: "Connection failed"}" }
    catch (e: JSONException) { "Unavailable: ${e.message ?: "Invalid response"}" }
    catch (e: IllegalArgumentException) { "Unavailable: ${e.message ?: "Invalid request"}" }
    catch (e: SecurityException) { "Unavailable: ${e.message ?: "Connection denied"}" }
    finally { connection?.disconnect() }
}

internal fun clientIp(): String = try {
    NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress ?: "Unavailable"
} catch (_: Exception) { "Unavailable" }

// M1 timer: private fun formatDuration(seconds: Long): String = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

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
