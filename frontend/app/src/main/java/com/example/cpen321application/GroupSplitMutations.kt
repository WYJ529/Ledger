package com.example.cpen321application

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal class GroupSplitMutations(
    private val session: Session,
    private val coordinator: GroupSplitCoordinator,
    private val form: GroupSplitForm,
    private val pending: GroupSplitPendingState,
    private val storage: GroupSplitPendingStorage,
) {
    private val state get() = coordinator.state

    suspend fun claim(split: GroupSplitSession, unit: SplitItemUnit, action: String) {
        if (!canSendNew()) return
        pending.operation = PendingSplitMutation(UUID.randomUUID().toString(), split.id, "claim", split.version, unit.unitId, action)
        retry()
    }

    suspend fun finalize(split: GroupSplitSession) {
        if (!canSendNew()) return
        pending.operation = PendingSplitMutation(UUID.randomUUID().toString(), split.id, "finalize", split.version)
        retry()
    }

    private fun canSendNew(): Boolean = state.canInteract && pending.isEmpty

    suspend fun retry() {
        val operation = pending.operation ?: return
        if (!state.canInteract || operation.sessionId.isBlank()) return
        execute(
            send = { operation.send(session.token) },
            accepted = {
                state.merge(it, select = true)
                pending.operation = null
                state.notice = operation.successMessage
            },
            rejected = { pending.operation = null; state.notice = it.message },
        )
    }

    suspend fun start() {
        if (!state.canInteract || pending.operation != null) return
        if (pending.startPayload.isBlank()) {
            if (!form.valid(state.summary?.members.orEmpty())) return
            val units = form.expandedUnits.map { it.copy(unitId = UUID.randomUUID().toString()) }
            pending.startPayload = encodeSplitStart(form.description.trim(), form.payer, units)
        }
        val saved = decodeSplitStart(pending.startPayload)
        val requestId = pending.startRequestId
        execute(
            send = { GroupLiveSplitApi.start(session.token, state.groupId, saved.description, saved.paidBy, saved.units, requestId) },
            accepted = {
                state.merge(it, select = true)
                form.startingAnother = false
                pending.clearStart()
                state.notice = "Split session started. Members can claim or release item units."
            },
            rejected = { pending.clearStart(); state.notice = it.message },
        )
    }

    private suspend fun execute(
        send: () -> GroupSplitSession,
        accepted: (GroupSplitSession) -> Unit,
        rejected: (ApiException) -> Unit,
    ) {
        state.working = true
        state.error = null
        state.notice = null
        val savedPending = pending.toJson()
        try {
            runSplitMutation(
                send = { withContext(Dispatchers.IO) { storage.saveBeforeRequest(savedPending); send() } },
                accepted = accepted,
                rejected = rejected,
                uncertain = {
                    coordinator.failed(it)
                    state.error = "The result is uncertain. The original request is retained for recovery or retry."
                },
                refresh = { coordinator.refreshSnapshot() },
                refreshFailed = coordinator::failed,
            )
        } finally { state.working = false }
    }
}
