package com.example.cpen321application

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** A single receipt item unit. Each unit has exactly one claimant or is unassigned. */
internal data class SplitItemUnit(
    val unitId: String,
    val description: String,
    val amountCents: Long,
    val ownerId: String?,
)

/** Authoritative session state returned by the Feature 3 REST API. */
internal data class GroupSplitSession(
    val id: String,
    val groupId: String,
    val description: String,
    val amountCents: Long,
    val currency: String,
    val paidBy: String,
    val units: List<SplitItemUnit>,
    val status: String,
    val version: Int,
    val sequence: Long,
    val finalizedExpenseId: String?,
)

internal data class GroupSplitSnapshot(
    val groupId: String,
    val splitSessions: List<GroupSplitSession>,
)

internal data class SplitOperation(
    val operationKey: String,
    val sessionId: String,
    val groupId: String,
    val type: String,
    val version: Int,
    val sequence: Long,
    val state: String,
)

/**
 * REST client for Let's Go Dutch. The backend validates the caller and group membership.
 * Mutations remain HTTP requests; authenticated Socket.IO only delivers committed state.
 */
internal object GroupLiveSplitApi {
    fun snapshot(token: String, groupId: String): GroupSplitSnapshot {
        val json = AccountApi.request("GET", "/api/groups/$groupId/snapshot", token = token)
        val sessions = json.optJSONArray("splitSessions") ?: JSONArray()
        return GroupSplitSnapshot(
            groupId = json.getString("groupId"),
            splitSessions = List(sessions.length()) { parseSession(sessions.getJSONObject(it)) },
        )
    }

    fun start(
        token: String,
        groupId: String,
        description: String,
        paidBy: String,
        units: List<SplitItemUnit>,
        requestId: String,
    ): GroupSplitSession {
        require(units.isNotEmpty()) { "Add at least one receipt item unit." }
        val amountCents = units.sumOf { it.amountCents }
        val items = JSONArray()
        units.forEach { unit ->
            items.put(JSONObject()
                .put("unitId", unit.unitId)
                .put("description", unit.description)
                .put("amountCents", unit.amountCents))
        }
        val body = JSONObject()
            .put("reviewed", true)
            .put("requestId", requestId)
            .put("description", description)
            .put("amountCents", amountCents)
            .put("paidBy", paidBy)
            .put("units", items)
        return parseSession(AccountApi.request("POST", "/api/groups/$groupId/split-sessions", body, token)
            .getJSONObject("session"))
    }

    fun claim(
        token: String,
        sessionId: String,
        unitId: String,
        action: String,
        version: Int,
        operationKey: String,
    ): GroupSplitSession {
        val body = JSONObject()
            .put("unitId", unitId)
            .put("action", action)
            .put("version", version)
            .put("operationKey", operationKey)
        return parseSession(AccountApi.request("POST", "/api/split-sessions/$sessionId/claims", body, token)
            .getJSONObject("session"))
    }

    fun finalize(token: String, sessionId: String, version: Int, operationKey: String): GroupSplitSession {
        val body = JSONObject().put("version", version).put("operationKey", operationKey)
        return parseSession(AccountApi.request("POST", "/api/split-sessions/$sessionId/finalize", body, token)
            .getJSONObject("session"))
    }

    fun operation(token: String, operationKey: String): SplitOperation {
        val operation = AccountApi.request("GET", "/api/operations/$operationKey", token = token)
            .getJSONObject("operation")
        return SplitOperation(
            operationKey = operation.getString("operationKey"),
            sessionId = operation.getString("sessionId"),
            groupId = operation.getString("groupId"),
            type = operation.getString("type"),
            version = operation.getInt("version"),
            sequence = operation.getLong("sequence"),
            state = operation.getString("state"),
        )
    }

    fun parseSession(json: JSONObject): GroupSplitSession {
        val units = json.getJSONArray("units")
        return GroupSplitSession(
            id = json.getString("id"),
            groupId = json.getString("groupId"),
            description = json.getString("description"),
            amountCents = json.getLong("amountCents"),
            currency = json.getString("currency"),
            paidBy = json.getString("paidBy"),
            units = List(units.length()) { index ->
                val unit = units.getJSONObject(index)
                SplitItemUnit(
                    unitId = unit.getString("unitId"),
                    description = unit.getString("description"),
                    amountCents = unit.getLong("amountCents"),
                    ownerId = unit.optString("ownerId").takeIf { it.isNotBlank() && it != "null" },
                )
            },
            status = json.getString("status"),
            version = json.getInt("version"),
            sequence = json.getLong("sequence"),
            finalizedExpenseId = json.optString("finalizedExpenseId").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

private data class SavedSplitStart(val description: String, val paidBy: String, val units: List<SplitItemUnit>)

private fun encodeSplitStart(description: String, paidBy: String, units: List<SplitItemUnit>): String {
    val encodedUnits = JSONArray()
    units.forEach { unit ->
        encodedUnits.put(JSONObject().put("unitId", unit.unitId).put("description", unit.description)
            .put("amountCents", unit.amountCents))
    }
    return JSONObject().put("description", description).put("paidBy", paidBy).put("units", encodedUnits).toString()
}

private fun decodeSplitStart(payload: String): SavedSplitStart {
    val json = JSONObject(payload)
    val units = json.getJSONArray("units")
    return SavedSplitStart(json.getString("description"), json.getString("paidBy"), List(units.length()) {
        val unit = units.getJSONObject(it)
        SplitItemUnit(unit.getString("unitId"), unit.getString("description"), unit.getLong("amountCents"), null)
    })
}

/**
 * Modular screen for a group's item-claim session. It can be launched from the selected
 * group's expense view without changing that view's existing navigation or shared files.
 */
@Composable
internal fun GroupLiveSplitScreen(
    session: Session,
    groupId: String,
    onSessionExpired: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val pendingStorage = remember(groupId, session.account.id) { GroupSplitPendingStorage(context, session.account.id, groupId) }
    val restoredPending = remember(groupId, session.account.id) { pendingStorage.load() }
    val restoredStart = remember(groupId, session.account.id) {
        restoredPending.optString("startPayload").takeIf { it.isNotBlank() }?.let { value ->
            try { decodeSplitStart(value) } catch (_: Exception) { null }
        }
    }
    var summary by remember(groupId) { mutableStateOf<GroupExpenseSummary?>(null) }
    var splitSnapshot by remember(groupId) { mutableStateOf<GroupSplitSnapshot?>(null) }
    var activeSplit by remember(groupId) { mutableStateOf<GroupSplitSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var formDescription by rememberSaveable(groupId) { mutableStateOf(restoredStart?.description.orEmpty()) }
    var receiptTotal by rememberSaveable(groupId) { mutableStateOf(restoredStart?.let { decimalAmount(it.units.sumOf { unit -> unit.amountCents }) }.orEmpty()) }
    var payer by rememberSaveable(groupId) { mutableStateOf(restoredStart?.paidBy.orEmpty()) }
    var itemNames by rememberSaveable(groupId) { mutableStateOf(restoredStart?.units?.map { it.description } ?: listOf("")) }
    var itemQuantities by rememberSaveable(groupId) { mutableStateOf(restoredStart?.units?.map { "1" } ?: listOf("1")) }
    var itemPrices by rememberSaveable(groupId) { mutableStateOf(restoredStart?.units?.map { decimalAmount(it.amountCents) } ?: listOf("")) }
    var reviewed by rememberSaveable(groupId) { mutableStateOf(restoredStart != null) }
    var startingAnother by rememberSaveable(groupId) { mutableStateOf(restoredStart != null) }
    // Keep this key stable across an uncertain create response so the backend can replay safely.
    var startRequestId by rememberSaveable(groupId) { mutableStateOf(restoredPending.optString("startRequestId").ifBlank { UUID.randomUUID().toString() }) }
    var pendingStartPayload by rememberSaveable(groupId) { mutableStateOf(restoredPending.optString("startPayload")) }
    var pendingKey by rememberSaveable(groupId) { mutableStateOf(restoredPending.optString("operationKey")) }
    var pendingSessionId by rememberSaveable(groupId) { mutableStateOf(restoredPending.optString("sessionId")) }
    var pendingType by rememberSaveable(groupId) { mutableStateOf(restoredPending.optString("type")) }
    var pendingUnitId by rememberSaveable(groupId) { mutableStateOf(restoredPending.optString("unitId")) }
    var pendingAction by rememberSaveable(groupId) { mutableStateOf(restoredPending.optString("action")) }
    var pendingVersion by rememberSaveable(groupId) { mutableStateOf(restoredPending.optInt("version", -1)) }
    var liveConnected by remember(groupId) { mutableStateOf(false) }
    var recoveringLive by remember(groupId) { mutableStateOf(false) }
    var bufferedLive by remember(groupId) { mutableStateOf<List<GroupSplitSession>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun pendingJson() = JSONObject().put("startRequestId", startRequestId).put("startPayload", pendingStartPayload)
        .put("operationKey", pendingKey).put("sessionId", pendingSessionId).put("type", pendingType)
        .put("unitId", pendingUnitId).put("action", pendingAction).put("version", pendingVersion)

    SideEffect {
        if (pendingKey.isBlank() && pendingStartPayload.isBlank()) pendingStorage.clear()
    }

    fun clearPendingOperation() {
        pendingKey = ""; pendingSessionId = ""; pendingType = ""; pendingUnitId = ""; pendingAction = ""; pendingVersion = -1
    }

    fun discardInaccessibleGroup(e: Exception) {
        if (e is ApiException && e.status in setOf(403, 404)) {
            summary = null
            splitSnapshot = null
            activeSplit = null
            bufferedLive = emptyList()
            pendingStartPayload = ""
            clearPendingOperation()
        }
    }

    fun knownSession(id: String): GroupSplitSession? = listOfNotNull(
        splitSnapshot?.splitSessions?.firstOrNull { it.id == id }, activeSplit?.takeIf { it.id == id },
    ).maxByOrNull { it.sequence }

    fun mergeCanonicalSession(incoming: GroupSplitSession, select: Boolean = false) {
        if (incoming.groupId != groupId) return
        val known = knownSession(incoming.id)
        val newest = if (known != null && !shouldReplaceSplitState(known.sequence, known.status, incoming.sequence, incoming.status)) known else incoming
        val sessions = splitSnapshot?.splitSessions.orEmpty()
        val merged = if (sessions.any { it.id == newest.id }) sessions.map { if (it.id == newest.id) newest else it }
            else listOf(newest) + sessions
        splitSnapshot = GroupSplitSnapshot(groupId, merged)
        if (select || activeSplit?.id == newest.id || activeSplit == null) activeSplit = newest
    }

    fun install(snapshot: GroupSplitSnapshot) {
        if (snapshot.groupId != groupId) return
        // Sequences are scoped to a session, not a group. Keep newer known session state.
        val known = splitSnapshot?.splitSessions.orEmpty().associateBy { it.id }
        val newestSnapshot = snapshot.copy(splitSessions = snapshot.splitSessions.map { candidate ->
            val existing = known[candidate.id]
            if (existing != null && !shouldReplaceSplitState(existing.sequence, existing.status, candidate.sequence, candidate.status)) existing else candidate
        } + known.values.filter { existing -> snapshot.splitSessions.none { it.id == existing.id } })
        splitSnapshot = newestSnapshot
        // A stale poll cannot roll a session back if a just-completed write was newer.
        val next = newestSnapshot.splitSessions
            .filter { it.status == "open" || it.status == "finalizing" }
            .firstOrNull()
        val current = activeSplit
        val currentFromSnapshot = current?.let { existing -> newestSnapshot.splitSessions.firstOrNull { it.id == existing.id } }
        when {
            currentFromSnapshot != null && currentFromSnapshot.version >= (current?.version ?: -1) -> activeSplit = currentFromSnapshot
            next != null && (current == null || current.status != "open" || currentFromSnapshot == null || next.id == current.id && next.version >= current.version) -> activeSplit = next
            current == null -> activeSplit = null
        }
        if (pendingStartPayload.isNotBlank()) {
            val saved = try { decodeSplitStart(pendingStartPayload) } catch (_: Exception) { null }
            val confirmed = newestSnapshot.splitSessions.firstOrNull { candidate ->
                saved != null && candidate.description == saved.description && candidate.paidBy == saved.paidBy &&
                    candidate.units.map { Triple(it.unitId, it.description, it.amountCents) } ==
                    saved.units.map { Triple(it.unitId, it.description, it.amountCents) }
            }
            if (confirmed != null) {
                activeSplit = confirmed
                startingAnother = false
                pendingStartPayload = ""
                startRequestId = UUID.randomUUID().toString()
                notice = "The submitted split session was recovered from the server."
            }
        }
    }

    fun installCommittedUpdate(update: GroupSplitSession): Boolean {
        if (update.groupId != groupId) return true
        val known = knownSession(update.id)
        when (splitUpdateDecision(known?.sequence, update.sequence)) {
            SplitUpdateDecision.Ignore -> return true
            SplitUpdateDecision.Recover -> return false
            SplitUpdateDecision.Apply -> Unit
        }
        mergeCanonicalSession(update)
        return true
    }

    fun drainBufferedEvents(): Boolean {
        val events = bufferedLive
        bufferedLive = emptyList()
        events.sortedBy { it.sequence }.forEach { update ->
            if (!installCommittedUpdate(update)) bufferedLive = bufferedLive + update
        }
        return bufferedLive.isEmpty()
    }

    suspend fun refreshSnapshot() {
        val loadedSummary = withContext(Dispatchers.IO) { GroupExpenseApi.summary(session.token, groupId) }
        summary = loadedSummary
        if (payer.isBlank() || loadedSummary.members.none { it.id == payer }) payer = loadedSummary.group.ownerId.ifBlank { loadedSummary.members.firstOrNull()?.id.orEmpty() }
        val snapshot = withContext(Dispatchers.IO) { GroupLiveSplitApi.snapshot(session.token, groupId) }
        install(snapshot)
        stale = false
    }

    suspend fun recoverLiveState() {
        if (recoveringLive) return
        recoveringLive = true
        stale = true
        try {
            var recovered = false
            for (attempt in 1..3) {
                refreshSnapshot()
                if (drainBufferedEvents()) { recovered = true; break }
            }
            if (!recovered) throw IOException("A live update gap still needs recovery.")
            if (pendingKey.isNotBlank()) {
                try {
                    val result = withContext(Dispatchers.IO) { GroupLiveSplitApi.operation(session.token, pendingKey) }
                    if (result.state == "complete") {
                        clearPendingOperation()
                        // Operation lookup is actor-scoped; reload the accepted canonical state.
                        refreshSnapshot()
                    }
                } catch (e: ApiException) {
                    if (e.status != 404) throw e
                }
            }
            // Events may have arrived while the operation lookup or second snapshot awaited IO.
            if (!drainBufferedEvents()) throw IOException("A live update gap still needs recovery.")
            stale = false
            error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            stale = true
            discardInaccessibleGroup(e)
            if (e is ApiException && e.status == 401) onSessionExpired()
            else error = "Recovering the session. Claims are paused until an authoritative snapshot is available."
        } finally { recoveringLive = false }
    }

    GroupSplitLiveConnection(
        token = session.token,
        groupId = groupId,
        onSubscribed = { liveConnected = true; scope.launch { recoverLiveState() } },
        onDisconnected = { liveConnected = false; stale = true },
        onSessionExpired = onSessionExpired,
        onUpdate = { update ->
            scope.launch {
                if (recoveringLive) bufferedLive = bufferedLive + update
                else if (!installCommittedUpdate(update)) {
                    bufferedLive = bufferedLive + update
                    scope.launch { recoverLiveState() }
                }
            }
        },
    )

    LaunchedEffect(session.token, groupId) {
        loading = true
        try {
            refreshSnapshot()
            error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiException && e.status == 401) onSessionExpired()
            else { discardInaccessibleGroup(e); error = e.message ?: "Unable to load the split session."; stale = true }
        } finally { loading = false }

        var connectedPollTicks = 0
        while (true) {
            delay(SPLIT_POLL_INTERVAL_MS)
            // Socket events normally maintain state. A 15-second snapshot check also catches
            // a lost final event or authorization change with no later event to reveal a gap.
            // While reconnecting, use the faster fallback and resolve pending acknowledgements.
            if (recoveringLive) continue
            if (liveConnected && stale) { recoverLiveState(); continue }
            if (liveConnected && !stale && pendingKey.isBlank()) {
                connectedPollTicks++
                if (connectedPollTicks < SPLIT_LIVE_AUDIT_TICKS) continue
            }
            connectedPollTicks = 0
            refreshing = true
            try {
                val snapshot = withContext(Dispatchers.IO) { GroupLiveSplitApi.snapshot(session.token, groupId) }
                install(snapshot)
                stale = false
                error = null
                if (pendingKey.isNotBlank()) {
                    try {
                        val result = withContext(Dispatchers.IO) { GroupLiveSplitApi.operation(session.token, pendingKey) }
                        if (result.state == "complete") {
                            notice = "The pending action was accepted. State refreshed from the server."
                            clearPendingOperation()
                            refreshSnapshot()
                        }
                    } catch (operationError: ApiException) {
                        if (operationError.status != 404) throw operationError
                        // Preserve the operation key and exact payload for an explicit retry.
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                stale = true
                discardInaccessibleGroup(e)
                error = if (e is ApiException && e.status == 401) {
                    onSessionExpired(); "Your session expired. Sign in again to continue."
                } else "Can't refresh right now. The last server snapshot is shown; actions are paused."
            } finally { refreshing = false }
        }
    }

    val loadedSummary = summary
    val currentSplit = if (startingAnother) null else activeSplit
    val isOwner = loadedSummary?.group?.ownerId == session.account.id ||
        loadedSummary?.members?.any { it.id == session.account.id && it.role == "owner" } == true
    val expandedUnits = buildList {
        itemNames.forEachIndexed { index, rawName ->
            val name = rawName.trim()
            val quantity = itemQuantities.getOrNull(index)?.toIntOrNull() ?: 0
            val unitPrice = moneyCents(itemPrices.getOrNull(index).orEmpty())
            if (name.isNotEmpty() && quantity in 1..20 && unitPrice != null) {
                repeat(quantity) { copy ->
                    add(SplitItemUnit("${index + 1}-${copy + 1}", name, unitPrice, null))
                }
            }
        }
    }
    val totalCents = expandedUnits.sumOf { it.amountCents }
    val rawFieldsValid = itemNames.indices.all { index ->
        itemNames[index].trim().isNotEmpty() && itemQuantities[index].toIntOrNull()?.let { it in 1..20 } == true && moneyCents(itemPrices[index]) != null
    }
    val canStart = isOwner && !working && !stale && liveConnected && !recoveringLive && pendingKey.isBlank() && if (pendingStartPayload.isNotBlank()) true else {
        reviewed && formDescription.trim().isNotEmpty() && formDescription.length <= 100 && rawFieldsValid &&
            validSplitReceiptTotal(receiptTotal, expandedUnits.map { it.amountCents }) &&
            payer in (loadedSummary?.members?.map { it.id } ?: emptyList())
    }

    TextButton(onClick = onBack, enabled = !working) { Text("Back to group expenses") }
    Text("Let’s Go Dutch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Enter reviewed receipt items manually. Each quantity creates claimable units; add tax or tip as a line so the item total matches the receipt.")
    Text(if (liveConnected && !recoveringLive && !stale) "Live · authenticated item updates" else if (stale || recoveringLive) "Reconnecting · restoring server state" else "Read-only until the live connection returns · refresh fallback every 3 seconds",
        style = MaterialTheme.typography.bodySmall)
    if (loading || refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (loading && loadedSummary == null) return
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    if (stale) Text("Reconnecting · claims and finalization are paused until a current snapshot is loaded.", color = MaterialTheme.colorScheme.error)
    if (error != null || stale) OutlinedButton(onClick = {
        scope.launch {
            refreshing = true
            try { refreshSnapshot(); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiException && e.status == 401) onSessionExpired()
                else { discardInaccessibleGroup(e); error = e.message ?: "Unable to refresh the session." }
                stale = true
            } finally { refreshing = false }
        }
    }, enabled = !refreshing) { Text("Refresh snapshot") }

    if (pendingKey.isNotBlank()) {
        Text("An action may still be pending. Its operation key is being checked before another action can be sent.", color = MaterialTheme.colorScheme.tertiary)
        if (!stale && !working && liveConnected && !recoveringLive) OutlinedButton(onClick = {
            val key = pendingKey
            val type = pendingType
            val splitId = pendingSessionId.takeIf { it.isNotBlank() } ?: return@OutlinedButton
            val savedPendingState = pendingJson()
            scope.launch {
                working = true
                try {
                    val result = withContext(Dispatchers.IO) {
                        pendingStorage.saveBeforeRequest(savedPendingState)
                        if (type == "finalize") GroupLiveSplitApi.finalize(session.token, splitId, pendingVersion, key)
                        else GroupLiveSplitApi.claim(session.token, splitId, pendingUnitId, pendingAction, pendingVersion, key)
                    }
                    mergeCanonicalSession(result, select = true)
                    clearPendingOperation()
                    notice = "Action confirmed by the server."
                    refreshSnapshot()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is ApiException && e.status == 401) onSessionExpired()
                    else if (e is ApiException && e.status in 400..499) {
                        clearPendingOperation()
                        try { refreshSnapshot() }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (refreshError: Exception) { discardInaccessibleGroup(refreshError); stale = true }
                        notice = e.message ?: "The session changed. Review the current state before trying again."
                    } else {
                        error = "The action is not confirmed yet. Retry keeps the same operation key."
                        stale = e is IOException
                    }
                } finally { working = false }
            }
        }, enabled = !stale && liveConnected && !recoveringLive) { Text("Retry the same action") }
    }

    val availableSessions = splitSnapshot?.splitSessions.orEmpty()
    if (availableSessions.size > 1) {
        Text("Split sessions", style = MaterialTheme.typography.titleMedium)
        availableSessions.forEach { available ->
            FilterChip(selected = activeSplit?.id == available.id,
                onClick = { activeSplit = available; startingAnother = false }, enabled = !working && pendingKey.isBlank(),
                label = { Text("${available.description} · ${available.status}") })
        }
    }

    if (currentSplit == null) {
        if (!isOwner) {
            Text("No open split session. The group owner can start one from a reviewed receipt.")
        } else {
            Text("Start a split session", style = MaterialTheme.typography.titleLarge)
            if (startingAnother && activeSplit != null && pendingStartPayload.isBlank()) {
                TextButton(onClick = { startingAnother = false }, enabled = !working) { Text("Back to saved split") }
            }
            OutlinedTextField(formDescription, { formDescription = it.take(100); reviewed = false }, label = { Text("Receipt description") },
                enabled = !working && pendingStartPayload.isBlank(), singleLine = true, modifier = Modifier.fillMaxWidth())
            loadedSummary?.members?.forEach { member ->
                Row {
                    RadioButton(selected = payer == member.id, onClick = { payer = member.id; reviewed = false }, enabled = !working && pendingStartPayload.isBlank())
                    TextButton(onClick = { payer = member.id; reviewed = false }, enabled = !working && pendingStartPayload.isBlank()) { Text("Paid by ${member.displayName}") }
                }
            }
            OutlinedTextField(receiptTotal, { receiptTotal = it.take(12); reviewed = false },
                label = { Text("Receipt total (${loadedSummary?.group?.currency.orEmpty()})") },
                supportingText = { Text("Must match all item units exactly") }, enabled = !working && pendingStartPayload.isBlank(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Text("Item units · ${loadedSummary?.group?.currency.orEmpty()} ${decimalAmount(totalCents)}", style = MaterialTheme.typography.titleMedium)
            itemNames.indices.forEach { index ->
                Text("Item ${index + 1}", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(itemNames[index], { value -> itemNames = itemNames.toMutableList().also { it[index] = value.take(80) }; reviewed = false },
                    label = { Text("Item name") }, enabled = !working && pendingStartPayload.isBlank(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(itemQuantities[index], { value -> itemQuantities = itemQuantities.toMutableList().also { it[index] = value.filter(Char::isDigit).take(2) }; reviewed = false },
                        label = { Text("Qty") }, enabled = !working && pendingStartPayload.isBlank(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(itemPrices[index], { value -> itemPrices = itemPrices.toMutableList().also { it[index] = value.take(12) }; reviewed = false },
                        label = { Text("Each") }, enabled = !working && pendingStartPayload.isBlank(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(2f))
                    if (itemNames.size > 1) TextButton(onClick = {
                        itemNames = itemNames.filterIndexed { i, _ -> i != index }
                        itemQuantities = itemQuantities.filterIndexed { i, _ -> i != index }
                        itemPrices = itemPrices.filterIndexed { i, _ -> i != index }
                        reviewed = false
                    }, enabled = !working && pendingStartPayload.isBlank()) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = {
                if (itemNames.size < 30) {
                    itemNames = itemNames + ""; itemQuantities = itemQuantities + "1"; itemPrices = itemPrices + ""
                    reviewed = false
                }
            }, enabled = !working && pendingStartPayload.isBlank() && itemNames.size < 30) { Text("Add receipt item") }
            if (itemNames.any { it.isNotBlank() } && !rawFieldsValid) Text("Enter each item name, quantity (1–20), and unit price.", color = MaterialTheme.colorScheme.error)
            if (expandedUnits.size > 200) Text("A split session supports up to 200 item units.", color = MaterialTheme.colorScheme.error)
            if (receiptTotal.isNotBlank() && moneyCents(receiptTotal) != totalCents) {
                Text("Item units must equal the receipt total. Add an item for tax, tip, or other charges.", color = MaterialTheme.colorScheme.error)
            }
            Row {
                Checkbox(checked = reviewed, onCheckedChange = { reviewed = it }, enabled = !working && pendingStartPayload.isBlank())
                Text("I reviewed the receipt items and confirmed their total.", modifier = Modifier.padding(top = 12.dp))
            }
            Button(onClick = {
                working = true; error = null; notice = null
                if (pendingStartPayload.isBlank()) {
                    val requestUnits = expandedUnits.map { it.copy(unitId = UUID.randomUUID().toString()) }
                    pendingStartPayload = encodeSplitStart(formDescription.trim(), payer, requestUnits)
                }
                val savedPendingState = pendingJson()
                scope.launch {
                    try {
                        val saved = decodeSplitStart(pendingStartPayload)
                        val created = withContext(Dispatchers.IO) {
                            pendingStorage.saveBeforeRequest(savedPendingState)
                            GroupLiveSplitApi.start(session.token, groupId, saved.description, saved.paidBy, saved.units, startRequestId)
                        }
                        mergeCanonicalSession(created, select = true)
                        startingAnother = false
                        pendingStartPayload = ""
                        startRequestId = UUID.randomUUID().toString()
                        notice = "Split session started. Members can claim or release item units."
                        refreshSnapshot()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (e is ApiException && e.status == 401) onSessionExpired()
                        else {
                            error = e.message ?: "Could not start the split session."
                            if (e is ApiException && e.status in 400..499) {
                                pendingStartPayload = ""
                                startRequestId = UUID.randomUUID().toString()
                            }
                            // A timed-out create may have succeeded. Recover before exposing Start again.
                            try { refreshSnapshot() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (refreshError: Exception) { discardInaccessibleGroup(refreshError); stale = true }
                        }
                    } finally { working = false }
                }
            }, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                Text(if (working) "Starting…" else if (pendingStartPayload.isNotBlank()) "Retry same start" else "Start split session")
            }
        }
    } else {
        SplitSessionContent(
            split = currentSplit,
            members = loadedSummary?.members.orEmpty(),
            currentUserId = session.account.id,
            isOwner = isOwner,
            enabled = !working && !stale && liveConnected && !recoveringLive && pendingKey.isBlank(),
            onClaim = { unit, action ->
                val opKey = UUID.randomUUID().toString()
                pendingKey = opKey; pendingType = "claim"; pendingUnitId = unit.unitId; pendingAction = action
                pendingSessionId = currentSplit.id
                pendingVersion = currentSplit.version
                val savedPendingState = pendingJson()
                working = true; error = null; notice = null
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            pendingStorage.saveBeforeRequest(savedPendingState)
                            GroupLiveSplitApi.claim(session.token, currentSplit.id, unit.unitId, action, currentSplit.version, opKey)
                        }
                        mergeCanonicalSession(result, select = true)
                        clearPendingOperation()
                        notice = if (action == "claim") "Item claimed." else "Item released."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (e is ApiException && e.status == 401) onSessionExpired()
                        else if (e is ApiException && e.status in 400..499) {
                            clearPendingOperation()
                            try { refreshSnapshot() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (refreshError: Exception) { discardInaccessibleGroup(refreshError); stale = true }
                            notice = e.message ?: "This item changed on the server. Review the current snapshot."
                        } else {
                            stale = e is IOException
                            error = "The result is uncertain. The operation key is retained for recovery or retry."
                        }
                    } finally { working = false }
                }
            },
            onFinalize = {
                val opKey = UUID.randomUUID().toString()
                pendingKey = opKey; pendingType = "finalize"; pendingVersion = currentSplit.version
                pendingSessionId = currentSplit.id
                val savedPendingState = pendingJson()
                working = true; error = null; notice = null
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            pendingStorage.saveBeforeRequest(savedPendingState)
                            GroupLiveSplitApi.finalize(session.token, currentSplit.id, currentSplit.version, opKey)
                        }
                        mergeCanonicalSession(result, select = true)
                        clearPendingOperation()
                        notice = "Split finalized and added to group expenses."
                        refreshSnapshot()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (e is ApiException && e.status == 401) onSessionExpired()
                        else if (e is ApiException && e.status in 400..499) {
                            clearPendingOperation()
                            try { refreshSnapshot() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (refreshError: Exception) { discardInaccessibleGroup(refreshError); stale = true }
                            notice = e.message ?: "The split changed or is not fully assigned. Review its current state."
                        } else {
                            stale = e is IOException
                            error = "Finalization is not confirmed. The operation key is retained for recovery or retry."
                        }
                    } finally { working = false }
                }
            },
        )
        if (currentSplit.status == "finalized" && isOwner) {
            OutlinedButton(onClick = {
                formDescription = ""
                receiptTotal = ""
                itemNames = listOf("")
                itemQuantities = listOf("1")
                itemPrices = listOf("")
                reviewed = false
                startRequestId = UUID.randomUUID().toString()
                startingAnother = true
            }, enabled = !working && pendingKey.isBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Start another receipt")
            }
        }
    }
}

@Composable
internal fun SplitSessionContent(
    split: GroupSplitSession,
    members: List<GroupMember>,
    currentUserId: String,
    isOwner: Boolean,
    enabled: Boolean,
    onClaim: (SplitItemUnit, String) -> Unit,
    onFinalize: () -> Unit,
) {
    val memberName = members.associate { it.id to it.displayName }
    val claimedCents = split.units.filter { it.ownerId != null }.sumOf { it.amountCents }
    val unclaimedCents = split.units.filter { it.ownerId == null }.sumOf { it.amountCents }
    Text(split.description, style = MaterialTheme.typography.titleLarge)
    Text("Paid by ${memberName[split.paidBy] ?: "group member"} · ${displayMoney(split.currency, split.amountCents)}")
    Text("Assigned ${displayMoney(split.currency, claimedCents)} · Unassigned ${displayMoney(split.currency, unclaimedCents)}")
    Text("${split.units.count { it.ownerId != null }} / ${split.units.size} units claimed · session version ${split.version}", style = MaterialTheme.typography.bodySmall)
    split.units.forEach { unit ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(unit.description, fontWeight = FontWeight.Medium)
                Text(displayMoney(split.currency, unit.amountCents), style = MaterialTheme.typography.bodySmall)
                Text(unit.ownerId?.let { "Claimed by ${memberName[it] ?: "group member"}" } ?: "Unassigned", style = MaterialTheme.typography.bodySmall)
            }
            when {
                split.status != "open" -> Text(split.status.replaceFirstChar(Char::uppercase))
                unit.ownerId == currentUserId -> OutlinedButton(onClick = { onClaim(unit, "unclaim") }, enabled = enabled) { Text("Release") }
                unit.ownerId == null -> Button(onClick = { onClaim(unit, "claim") }, enabled = enabled) { Text("Claim") }
                else -> Text("Taken")
            }
        }
    }
    val shares = split.units.filter { it.ownerId != null }.groupBy { it.ownerId!! }.mapValues { (_, units) -> units.sumOf { it.amountCents } }
    Text("Member allocations", style = MaterialTheme.typography.titleMedium)
    members.forEach { member -> Text("${member.displayName}: ${displayMoney(split.currency, shares[member.id] ?: 0)}") }
    if (split.status == "finalized") {
        Text("Finalized · ${split.finalizedExpenseId?.let { "expense $it" } ?: "saved to group expenses"}", color = MaterialTheme.colorScheme.primary)
    } else if (isOwner) {
        Button(onClick = onFinalize, enabled = enabled && split.status == "open" && unclaimedCents == 0L && claimedCents == split.amountCents,
            modifier = Modifier.fillMaxWidth()) { Text("Finalize split") }
        if (unclaimedCents > 0) Text("Assign every unit before finalizing.", style = MaterialTheme.typography.bodySmall)
    }
}

private const val SPLIT_POLL_INTERVAL_MS = 3_000L
private const val SPLIT_LIVE_AUDIT_TICKS = 5

@Composable
private fun GroupSplitLiveConnection(
    token: String,
    groupId: String,
    onSubscribed: () -> Unit,
    onDisconnected: () -> Unit,
    onSessionExpired: () -> Unit,
    onUpdate: (GroupSplitSession) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = findSplitActivity(context)?.lifecycle
    val subscribeCallback by rememberUpdatedState(onSubscribed)
    val disconnectCallback by rememberUpdatedState(onDisconnected)
    val expiredCallback by rememberUpdatedState(onSessionExpired)
    val updateCallback by rememberUpdatedState(onUpdate)

    DisposableEffect(token, groupId, lifecycle) {
        val handler = Handler(Looper.getMainLooper())
        var disposed = false
        val options = IO.Options().apply {
            transports = arrayOf("websocket")
            reconnection = true
            reconnectionDelay = 1000
            reconnectionDelayMax = 10000
            timeout = 10000
            forceNew = true
            auth = mapOf("token" to token)
        }
        val socket = IO.socket(BuildConfig.API_BASE_URL.trimEnd('/') + "/dutch", options)
        fun onMain(action: () -> Unit) { handler.post { if (!disposed) action() } }
        socket.on(Socket.EVENT_CONNECT) {
            socket.emit("subscribe", JSONObject().put("groupId", groupId), Ack { args ->
                val ack = args.firstOrNull() as? JSONObject
                if (ack?.optBoolean("ok") == true) onMain { subscribeCallback() }
                else onMain { disconnectCallback() }
            })
        }
        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val message = args.firstOrNull()?.toString().orEmpty()
            onMain {
                disconnectCallback()
                if (message.contains("Session expired", ignoreCase = true) || message.contains("Authentication required", ignoreCase = true)) {
                    expiredCallback()
                }
            }
        }
        socket.on(Socket.EVENT_DISCONNECT) { onMain { disconnectCallback() } }
        socket.on("onGroupUpdated") { args ->
            val wrapper = when (val raw = args.firstOrNull()) {
                is JSONObject -> raw
                is String -> try { JSONObject(raw) } catch (_: Exception) { null }
                else -> null
            } ?: return@on
            val event = wrapper.optJSONObject("event") ?: return@on
            if (event.optString("groupId") != groupId) return@on
            val itemized = wrapper.optJSONObject("session") ?: return@on
            try {
                val parsed = GroupLiveSplitApi.parseSession(itemized)
                onMain { updateCallback(parsed) }
            } catch (_: Exception) {
                onMain { disconnectCallback() }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> socket.connect()
                Lifecycle.Event.ON_STOP -> socket.disconnect()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        if (lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) socket.connect()
        onDispose {
            disposed = true
            lifecycle?.removeObserver(observer)
            socket.off()
            socket.disconnect()
            handler.removeCallbacksAndMessages(null)
        }
    }
}

private fun findSplitActivity(context: Context): ComponentActivity? {
    var candidate = context
    while (candidate is ContextWrapper) {
        if (candidate is ComponentActivity) return candidate
        candidate = candidate.baseContext
    }
    return candidate as? ComponentActivity
}
