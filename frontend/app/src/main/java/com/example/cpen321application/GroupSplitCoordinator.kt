package com.example.cpen321application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

private const val SPLIT_POLL_INTERVAL_MS = 3_000L
private const val SPLIT_LIVE_AUDIT_TICKS = 5

internal class GroupSplitCoordinator(
    private val session: Session,
    val state: GroupSplitState,
    private val form: GroupSplitForm,
    private val pending: GroupSplitPendingState,
    private val onSessionExpired: () -> Unit,
) {
    suspend fun refreshSnapshot() {
        val loadedSummary = withContext(Dispatchers.IO) { GroupExpenseApi.summary(session.token, state.groupId) }
        state.summary = loadedSummary
        if (form.payer.isBlank() || loadedSummary.members.none { it.id == form.payer }) {
            form.payer = loadedSummary.group.ownerId.ifBlank { loadedSummary.members.firstOrNull()?.id.orEmpty() }
        }
        val snapshot = withContext(Dispatchers.IO) { GroupLiveSplitApi.snapshot(session.token, state.groupId) }
        state.install(snapshot)
        recoverStart()
    }

    private fun recoverStart() {
        val saved = restoredSplitStart(pending.startPayload) ?: return
        val confirmed = state.snapshot?.splitSessions?.firstOrNull { candidate ->
            candidate.description == saved.description && candidate.paidBy == saved.paidBy &&
                candidate.units.map { Triple(it.unitId, it.description, it.amountCents) } ==
                saved.units.map { Triple(it.unitId, it.description, it.amountCents) }
        } ?: return
        state.activeSplit = confirmed
        form.startingAnother = false
        pending.clearStart()
        state.notice = "The submitted split session was recovered from the server."
    }

    fun failed(error: Exception) {
        state.stale = true
        if (error is ApiException && error.status in setOf(403, 404)) {
            state.discardGroup()
            pending.clear()
        }
        if (error is ApiException && error.status == 401) onSessionExpired()
        else state.error = "Can't refresh right now. The last server snapshot is shown; actions are paused."
    }

    private suspend fun recoverPending() {
        val operation = pending.operation ?: return
        val recovered = recoverPendingSplit(
            lookup = { withContext(Dispatchers.IO) { GroupLiveSplitApi.operation(session.token, operation.key) } },
            refresh = { refreshSnapshot() },
            clear = { if (pending.operation?.key == operation.key) pending.operation = null },
        )
        if (recovered) state.notice = "The pending action was accepted. State refreshed from the server."
    }

    suspend fun recoverLiveState() {
        if (state.recoveringLive) return
        state.recoveringLive = true
        state.stale = true
        try {
            when (val result = splitRequest { recoverCanonicalState() }) {
                is SplitRequestResult.Success -> { state.stale = false; state.error = null }
                is SplitRequestResult.Failure -> failed(result.error)
            }
        } finally { state.recoveringLive = false }
    }

    private suspend fun recoverCanonicalState() {
        var recovered = false
        for (attempt in 1..3) {
            refreshSnapshot()
            if (state.drainEvents()) { recovered = true; break }
        }
        if (!recovered) throw IOException("A live update gap still needs recovery.")
        recoverPending()
        if (!state.drainEvents()) throw IOException("A live update gap still needs recovery.")
    }

    suspend fun refresh() {
        state.refreshing = true
        try { recoverLiveState() } finally { state.refreshing = false }
    }

    suspend fun receive(update: GroupSplitSession) {
        if (state.recoveringLive) state.buffer(update)
        else if (!state.installUpdate(update)) {
            state.buffer(update)
            recoverLiveState()
        }
    }

    suspend fun loadAndPoll() {
        state.loading = true
        try { recoverLiveState() } finally { state.loading = false }
        var connectedPollTicks = 0
        while (true) {
            delay(SPLIT_POLL_INTERVAL_MS)
            if (state.recoveringLive || state.working) continue
            if (state.liveReady && pending.operation == null) {
                connectedPollTicks++
                if (connectedPollTicks < SPLIT_LIVE_AUDIT_TICKS) continue
            }
            connectedPollTicks = 0
            // The audit also catches a lost last event and membership revocation.
            refresh()
        }
    }
}
