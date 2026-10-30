package com.example.cpen321application

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Main-thread state transitions shared by REST recovery and committed live events. */
internal class GroupSplitState(val groupId: String) {
    var summary by mutableStateOf<GroupExpenseSummary?>(null)
    var snapshot by mutableStateOf<GroupSplitSnapshot?>(null)
    var activeSplit by mutableStateOf<GroupSplitSession?>(null)
    var loading by mutableStateOf(true)
    var refreshing by mutableStateOf(false)
    var stale by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var working by mutableStateOf(false)
    var unavailable by mutableStateOf(false)
    var liveConnected by mutableStateOf(false)
    var recoveringLive by mutableStateOf(false)
    var bufferedLive by mutableStateOf<List<GroupSplitSession>>(emptyList())
    val liveReady: Boolean get() = liveConnected && !recoveringLive && !stale
    val canInteract: Boolean get() = liveReady && !working

    fun isOwner(userId: String): Boolean = summary?.group?.ownerId == userId ||
        summary?.members?.any { it.id == userId && it.role == "owner" } == true

    fun discardGroup() {
        unavailable = true
        summary = null
        snapshot = null
        activeSplit = null
        bufferedLive = emptyList()
    }

    private fun knownSession(id: String): GroupSplitSession? = listOfNotNull(
        snapshot?.splitSessions?.firstOrNull { it.id == id }, activeSplit?.takeIf { it.id == id },
    ).maxByOrNull { it.sequence }

    private fun newest(known: GroupSplitSession?, incoming: GroupSplitSession): GroupSplitSession =
        if (known != null && !shouldReplaceSplitState(known.sequence, known.status, incoming.sequence, incoming.status)) {
            known
        } else incoming

    fun merge(incoming: GroupSplitSession, select: Boolean = false) {
        if (incoming.groupId != groupId) return
        val latest = newest(knownSession(incoming.id), incoming)
        val sessions = snapshot?.splitSessions.orEmpty()
        val merged = if (sessions.any { it.id == latest.id }) sessions.map { if (it.id == latest.id) latest else it }
            else listOf(latest) + sessions
        snapshot = GroupSplitSnapshot(groupId, merged)
        if (select || activeSplit?.id == latest.id || activeSplit == null) activeSplit = latest
    }

    fun install(incoming: GroupSplitSnapshot) {
        if (incoming.groupId != groupId) return
        val known = snapshot?.splitSessions.orEmpty().associateBy { it.id }
        val merged = incoming.splitSessions.map { newest(known[it.id], it) } +
            known.values.filter { existing -> incoming.splitSessions.none { it.id == existing.id } }
        snapshot = incoming.copy(splitSessions = merged)
        val current = activeSplit
        activeSplit = merged.firstOrNull { it.id == current?.id }
            ?: merged.firstOrNull { it.status == "open" || it.status == "finalizing" }
            ?: current
    }

    fun installUpdate(update: GroupSplitSession): Boolean {
        if (update.groupId != groupId) return true
        when (splitUpdateDecision(knownSession(update.id)?.sequence, update.sequence)) {
            SplitUpdateDecision.Ignore -> return true
            SplitUpdateDecision.Recover -> return false
            SplitUpdateDecision.Apply -> merge(update)
        }
        return true
    }

    fun drainEvents(): Boolean {
        val events = bufferedLive
        bufferedLive = emptyList()
        events.sortedBy { it.sequence }.forEach { update ->
            if (!installUpdate(update)) bufferedLive = bufferedLive + update
        }
        return bufferedLive.isEmpty()
    }

    fun buffer(update: GroupSplitSession) {
        if (update.groupId == groupId) bufferedLive = bufferedLive + update
    }

    fun disconnect() {
        liveConnected = false
        stale = true
    }
}
