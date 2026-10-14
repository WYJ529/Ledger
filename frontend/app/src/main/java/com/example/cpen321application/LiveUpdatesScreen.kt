package com.example.cpen321application

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

private const val FRAME_GAP_MS = 3500L
private const val GRID_SIZE = 16

@Composable
internal fun LiveUpdatesScreen() {
    var pixels by remember { mutableStateOf(List(GRID_SIZE * GRID_SIZE) { Color.White }) }
    var status by remember { mutableStateOf("Connecting to your backend") }
    var updates by remember { mutableStateOf(0) }
    var frame by remember { mutableStateOf(0) }
    var retryKey by remember { mutableStateOf(0) }
    val lifecycle = findActivity(LocalContext.current)?.lifecycle

    DisposableEffect(retryKey, lifecycle) {
        val handler = Handler(Looper.getMainLooper())
        var disposed = false
        var lastPixelTime = 0L
        val options = IO.Options().apply {
            transports = arrayOf("websocket")
            reconnection = true
            reconnectionDelay = 1000
            reconnectionDelayMax = 10000
            timeout = 10000
            forceNew = true
        }
        var socket: Socket? = null
        fun updateUi(action: () -> Unit) {
            handler.post { if (!disposed) action() }
        }
        fun clearCanvas() {
            pixels = List(GRID_SIZE * GRID_SIZE) { Color.White }
            updates = 0
            frame = 0
            lastPixelTime = 0
        }
        try {
            val connection = IO.socket(BuildConfig.API_BASE_URL.trimEnd('/'), options)
            socket = connection
            connection.on(Socket.EVENT_CONNECT) {
                updateUi { clearCanvas(); status = "Backend connected; waiting for course stream" }
            }
            connection.on(Socket.EVENT_CONNECT_ERROR) {
                updateUi { status = "Cannot reach backend. Retrying automatically..." }
            }
            connection.on(Socket.EVENT_DISCONNECT) {
                updateUi { status = "Disconnected. Reconnecting while this page is open..." }
            }
            connection.on("stream-status") { args ->
                val message = args.firstOrNull()?.toString() ?: "Waiting for course stream"
                updateUi {
                    status = message
                    if (message == "Connected to course stream") clearCanvas()
                }
            }
            connection.on("pixel") { args ->
                val payload = args.firstOrNull() as? String
                if (payload != null) {
                    val receivedAt = SystemClock.elapsedRealtime()
                    try {
                        val pixel = JSONObject(payload)
                        val xValue = pixel.get("x") as? Number ?: throw IllegalArgumentException("Invalid x")
                        val yValue = pixel.get("y") as? Number ?: throw IllegalArgumentException("Invalid y")
                        val x = xValue.toInt()
                        val y = yValue.toInt()
                        val hex = pixel.getString("color")
                        require(x in 0 until GRID_SIZE && y in 0 until GRID_SIZE)
                        require(xValue.toDouble() == x.toDouble() && yValue.toDouble() == y.toDouble())
                        require(hex.matches(Regex("#[0-9a-fA-F]{6}")))
                        val color = Color(android.graphics.Color.parseColor(hex))
                        updateUi {
                            // The course protocol has no frame ID/reset message. Infer a
                            // new picture from its documented five-second pause.
                            val newFrame = lastPixelTime == 0L || receivedAt - lastPixelTime >= FRAME_GAP_MS
                            val next = if (newFrame) MutableList(GRID_SIZE * GRID_SIZE) { Color.White } else pixels.toMutableList()
                            if (newFrame) { frame++; updates = 0 }
                            next[y * GRID_SIZE + x] = color
                            pixels = next
                            updates++
                            lastPixelTime = receivedAt
                            status = "Live pixel stream"
                        }
                    } catch (_: Exception) {
                        updateUi { status = "Ignored an invalid pixel message; waiting for the next pixel" }
                    }
                }
            }
        } catch (_: Exception) {
            status = "Invalid backend address. Check API_BASE_URL and rebuild."
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> socket?.connect()
                Lifecycle.Event.ON_STOP -> socket?.disconnect()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        if (lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) socket?.connect()
        onDispose {
            disposed = true
            lifecycle?.removeObserver(observer)
            socket?.off()
            socket?.disconnect()
            handler.removeCallbacksAndMessages(null)
        }
    }

    Text("Live Updates", style = MaterialTheme.typography.headlineMedium)
    Text(status, color = MaterialTheme.colorScheme.primary)
    PixelCanvas(pixels)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Picture $frame  /  $updates pixel updates")
            Text("The picture appears one pixel at a time. A short pause separates pictures.", style = MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedButton(onClick = { retryKey++ }) { Text("Reconnect") }
}

private fun findActivity(context: Context): ComponentActivity? {
    var candidate = context
    while (candidate is ContextWrapper) {
        if (candidate is ComponentActivity) return candidate
        candidate = candidate.baseContext
    }
    return candidate as? ComponentActivity
}
