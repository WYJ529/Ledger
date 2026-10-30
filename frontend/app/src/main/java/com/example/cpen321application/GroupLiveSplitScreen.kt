package com.example.cpen321application

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun GroupLiveSplitScreen(session: Session, groupId: String, onSessionExpired: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val accountId = session.account.id
    val storage = remember(groupId, accountId) { GroupSplitPendingStorage(context, accountId, groupId) }
    val pending = rememberSaveable(groupId, accountId, saver = GroupSplitPendingState.StateSaver) {
        GroupSplitPendingState.fromJson(storage.load())
    }
    val form = rememberSaveable(groupId, accountId, saver = GroupSplitForm.StateSaver) {
        GroupSplitForm(restoredSplitStart(pending.startPayload))
    }
    val state = remember(groupId, accountId) { GroupSplitState(groupId) }
    val expired by rememberUpdatedState(onSessionExpired)
    val coordinator = remember(session.token, state, form, pending) {
        GroupSplitCoordinator(session, state, form, pending) { expired() }
    }
    val mutations = remember(coordinator, storage) { GroupSplitMutations(session, coordinator, form, pending, storage) }
    val scope = rememberCoroutineScope()
    SideEffect { if (pending.isEmpty) storage.clear() }
    GroupSplitLiveConnection(session.token, groupId, SplitLiveCallbacks(
        subscribed = { state.liveConnected = true; scope.launch { coordinator.recoverLiveState() } },
        disconnected = state::disconnect,
        expired = { expired() },
        update = { scope.launch { coordinator.receive(it) } },
    ))
    LaunchedEffect(coordinator) { coordinator.loadAndPoll() }
    SplitScreenHeader(state, onBack)
    if (state.loading && state.summary == null) return
    SplitRecoveryControls(state, pending, { scope.launch { coordinator.refresh() } }, { scope.launch { mutations.retry() } })
    SplitSessionPicker(state, form, pending)
    SplitScreenBody(session.account.id, state, form, pending, mutations, scope)
}

@Composable
private fun SplitScreenHeader(state: GroupSplitState, onBack: () -> Unit) {
    BackBar("Back to group expenses", onClick = onBack, enabled = !state.working)
    Text(t("Let’s Go Dutch"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(t("Enter reviewed receipt items manually. Each quantity creates claimable units; add tax or tip as a line so the item total matches the receipt."))
    val status = when {
        state.liveReady -> "Live · authenticated item updates"
        state.stale || state.recoveringLive -> "Reconnecting · restoring server state"
        else -> "Read-only until the live connection returns · refresh fallback every 3 seconds"
    }
    Text(t(status), style = MaterialTheme.typography.bodySmall)
    if (state.loading || state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun SplitRecoveryControls(state: GroupSplitState, pending: GroupSplitPendingState, refresh: () -> Unit, retry: () -> Unit) {
    state.error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
    state.notice?.let { Text(t(it), color = MaterialTheme.colorScheme.primary) }
    if (state.stale) Text(t("Reconnecting · claims and finalization are paused until a current snapshot is loaded."), color = MaterialTheme.colorScheme.error)
    if (state.error != null || state.stale) {
        BarButton(onClick = refresh, enabled = !state.refreshing) { Text(t("Refresh snapshot")) }
    }
    if (pending.operation != null) {
        Text(t("An action may still be pending. Its operation key is being checked before another action can be sent."), color = MaterialTheme.colorScheme.tertiary)
        if (state.canInteract) BarButton(onClick = retry) { Text(t("Retry the same action")) }
    }
}

@Composable
private fun SplitSessionPicker(state: GroupSplitState, form: GroupSplitForm, pending: GroupSplitPendingState) {
    val sessions = state.snapshot?.splitSessions.orEmpty()
    if (sessions.size > 1) {
        Text(t("Split sessions"), style = MaterialTheme.typography.titleMedium)
        sessions.forEach { available ->
            FilterChip(selected = state.activeSplit?.id == available.id,
                onClick = { state.activeSplit = available; form.startingAnother = false },
                enabled = !state.working && pending.isEmpty,
                label = { Text("${available.description} · ${available.status}") })
        }
    }
}

@Composable
private fun SplitScreenBody(
    userId: String,
    state: GroupSplitState,
    form: GroupSplitForm,
    pending: GroupSplitPendingState,
    mutations: GroupSplitMutations,
    scope: CoroutineScope,
) {
    val current = if (form.startingAnother) null else state.activeSplit
    val isOwner = state.isOwner(userId)
    if (current == null) {
        if (isOwner) SplitReceiptForm(form, state, pending) { scope.launch { mutations.start() } }
        else Text(t("No open split session. The group owner can start one from a reviewed receipt."))
    } else {
        SplitSessionContent(current, state.summary?.members.orEmpty(), userId, isOwner,
            enabled = state.canInteract && pending.isEmpty,
            onClaim = { unit, action -> scope.launch { mutations.claim(current, unit, action) } },
            onFinalize = { scope.launch { mutations.finalize(current) } },
        )
        if (current.status == "finalized" && isOwner) {
            BarButton(onClick = { form.reset(); pending.clearStart() },
                enabled = !state.working && pending.isEmpty, modifier = Modifier.fillMaxWidth()) { Text(t("Start another receipt")) }
        }
    }
}
