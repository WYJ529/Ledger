package com.example.cpen321application

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONException
import org.json.JSONObject

/** Malformed or unrelated events cannot replace the canonical REST state. */
internal fun parseSplitUpdate(raw: Any?, groupId: String): GroupSplitSession? = try {
    val wrapper = when (raw) {
        is JSONObject -> raw
        is String -> JSONObject(raw)
        else -> null
    }
    parseSplitEnvelope(wrapper, groupId)
} catch (_: JSONException) { null }

private fun parseSplitEnvelope(wrapper: JSONObject?, groupId: String): GroupSplitSession? {
    val event = wrapper?.optJSONObject("event") ?: return null
    if (event.optString("groupId") != groupId) return null
    val itemized = wrapper.optJSONObject("session") ?: return null
    val parsed = GroupLiveSplitApi.parseSession(itemized)
    if (parsed.groupId != groupId || parsed.id != event.optString("sessionId")) return null
    return parsed.takeIf { it.sequence == event.optLong("sequence", -1) }
}

internal data class SplitLiveCallbacks(
    val subscribed: () -> Unit,
    val disconnected: () -> Unit,
    val expired: () -> Unit,
    val update: (GroupSplitSession) -> Unit,
    val membershipChanged: (GroupMembershipChange) -> Unit = {},
)

internal enum class GroupMembershipChange { Joined, Left, Removed, Deleted }

internal fun parseMembershipChange(raw: Any?, groupId: String): GroupMembershipChange? = try {
    val event = when (raw) {
        is JSONObject -> raw
        is String -> JSONObject(raw)
        else -> null
    }
    if (event?.optString("groupId") != groupId) null else when (event.optString("type")) {
        "joined" -> GroupMembershipChange.Joined
        "left" -> GroupMembershipChange.Left
        "removed" -> GroupMembershipChange.Removed
        "deleted" -> GroupMembershipChange.Deleted
        else -> null
    }
} catch (_: JSONException) { null }

@Composable
internal fun GroupSplitLiveConnection(token: String, groupId: String, callbacks: SplitLiveCallbacks,
    serverUrl: String = BuildConfig.API_BASE_URL) {
    val lifecycle = findSplitActivity(LocalContext.current)?.lifecycle
    val latest by rememberUpdatedState(callbacks)
    DisposableEffect(token, groupId, lifecycle, serverUrl) {
        val connection = SplitLiveConnection(token, groupId, serverUrl) { latest }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> connection.connect()
                Lifecycle.Event.ON_STOP -> connection.disconnect()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        if (lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) connection.connect()
        onDispose {
            lifecycle?.removeObserver(observer)
            connection.close()
        }
    }
}

private class SplitLiveConnection(token: String, private val groupId: String, serverUrl: String,
    private val callbacks: () -> SplitLiveCallbacks) {
    private val handler = Handler(Looper.getMainLooper())
    private var disposed = false
    private val socket = IO.socket(serverUrl.trimEnd('/') + "/dutch", splitSocketOptions(token))

    init {
        socket.on(Socket.EVENT_CONNECT) { subscribe() }
        socket.on(Socket.EVENT_CONNECT_ERROR) { args -> connectionError(args.firstOrNull()?.toString().orEmpty()) }
        socket.on(Socket.EVENT_DISCONNECT) { onMain { callbacks().disconnected() } }
        socket.on("onGroupUpdated") { args ->
            parseSplitUpdate(args.firstOrNull(), groupId)?.let { parsed -> onMain { callbacks().update(parsed) } }
        }
        socket.on("onGroupMembershipChanged") { args ->
            parseMembershipChange(args.firstOrNull(), groupId)?.let { change ->
                onMain { callbacks().membershipChanged(change) }
            }
        }
    }

    private fun onMain(action: () -> Unit) { handler.post { if (!disposed) action() } }

    private fun subscribe() {
        socket.emit("subscribe", JSONObject().put("groupId", groupId), Ack { args ->
            val ack = args.firstOrNull() as? JSONObject
            onMain {
                if (ack?.optBoolean("ok") == true) callbacks().subscribed()
                else {
                    callbacks().disconnected()
                    when (ack?.optString("error")) {
                        "Group not found." -> callbacks().membershipChanged(GroupMembershipChange.Removed)
                        "Session expired." -> callbacks().expired()
                    }
                }
            }
        })
    }

    private fun connectionError(message: String) {
        onMain {
            callbacks().disconnected()
            if (message.contains("Session expired", ignoreCase = true) || message.contains("Authentication required", ignoreCase = true)) {
                callbacks().expired()
            }
        }
    }

    fun connect() { socket.connect() }
    fun disconnect() { socket.disconnect() }
    fun close() {
        disposed = true
        socket.off()
        socket.disconnect()
        handler.removeCallbacksAndMessages(null)
    }
}

private fun splitSocketOptions(token: String) = IO.Options().apply {
    transports = arrayOf("websocket")
    reconnection = true
    reconnectionDelay = 1000
    reconnectionDelayMax = 10000
    timeout = 10000
    forceNew = true
    auth = mapOf("token" to token)
}

private fun findSplitActivity(context: Context): ComponentActivity? {
    var candidate = context
    while (candidate is ContextWrapper) {
        if (candidate is ComponentActivity) return candidate
        candidate = candidate.baseContext
    }
    return candidate as? ComponentActivity
}
