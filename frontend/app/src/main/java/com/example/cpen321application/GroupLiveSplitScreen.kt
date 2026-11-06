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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How the screen meets the shared entry: [bill] starts a split from it; new bills are added through [onAddBill]. */
internal class SplitEntry(val bill: BillPrefill? = null, val onStarted: () -> Unit = {}, val onAddBill: () -> Unit = {})

@Composable
internal fun GroupLiveSplitScreen(session: Session, groupId: String, onSessionExpired: () -> Unit, onBack: () -> Unit,
    onGroupUnavailable: () -> Unit = onBack, entry: SplitEntry = SplitEntry()) {
    val context = LocalContext.current
    val accountId = session.account.id
    val storage = remember(groupId, accountId) { GroupSplitPendingStorage(context, accountId, groupId) }
    val pending = rememberSaveable(groupId, accountId, saver = GroupSplitPendingState.StateSaver) {
        GroupSplitPendingState.fromJson(storage.load())
    }
    val form = rememberSaveable(groupId, accountId, saver = GroupSplitForm.StateSaver) {
        GroupSplitForm(restoredSplitStart(pending.startPayload))
    }
    var filled by rememberSaveable(groupId, accountId) { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val bill = entry.bill
        if (bill != null && !filled && pending.startPayload.isBlank()) {
            form.fill(bill)
            filled = true
        }
    }
    LaunchedEffect(form.startingAnother) { if (filled && !form.startingAnother) entry.onStarted() }
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
        membershipChanged = { scope.launch { coordinator.receiveMembershipChange() } },
    ))
    LaunchedEffect(coordinator) { coordinator.loadAndPoll() }
    LaunchedEffect(state.unavailable) { if (state.unavailable) onGroupUnavailable() }
    SplitScreenHeader(state, onBack)
    if (state.loading && state.summary == null) return
    SplitRecoveryControls(state, pending, { scope.launch { coordinator.refresh() } }, { scope.launch { mutations.retry() } })
    SplitSessionPicker(state, form, pending)
    SplitScreenBody(session.account.id, state, form, pending, mutations, SplitBodyActions(entry, scope))
}

@Composable
private fun SplitScreenHeader(state: GroupSplitState, onBack: () -> Unit) {
    BackBar("Back to group expenses", onClick = onBack, enabled = !state.working)
    Text(t("Let’s Go Dutch"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(t("Each quantity creates claimable units. Tax and tip are shared in proportion to the items each person claims."))
    if (state.loading || state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun SplitRecoveryControls(state: GroupSplitState, pending: GroupSplitPendingState, refresh: () -> Unit, retry: () -> Unit) {
    state.error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
    state.notice?.let { Text(t(it), color = MaterialTheme.colorScheme.primary) }
    if (!state.liveReady || state.stale) Text(t("Connection lost. Claims are paused until it reconnects."), color = MaterialTheme.colorScheme.error)
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
                label = { Text("${available.description} · ${t(available.status)}") })
        }
    }
}

private class SplitBodyActions(val entry: SplitEntry, val scope: CoroutineScope)

@Composable
private fun SplitScreenBody(
    userId: String,
    state: GroupSplitState,
    form: GroupSplitForm,
    pending: GroupSplitPendingState,
    mutations: GroupSplitMutations,
    actions: SplitBodyActions,
) {
    val scope = actions.scope
    val entry = actions.entry
    val current = if (form.startingAnother) null else state.activeSplit
    val isOwner = state.isOwner(userId)
    if (current == null) {
        when {
            isOwner && (form.startingAnother || entry.bill != null) -> {
                entry.bill?.let { BillPrefillNotes(it, state.summary?.group?.currency.orEmpty()) }
                SplitReceiptForm(form, state, pending) { scope.launch { mutations.start() } }
            }
            isOwner -> {
                Text(t("No open split session. Add a bill and choose By item to start one."))
                PrimaryBarButton(onClick = entry.onAddBill, enabled = !state.working, modifier = Modifier.fillMaxWidth()) { Text(t("+ Add a bill")) }
            }
            else -> Text(t("No open split session. The group owner can start one from a reviewed receipt."))
        }
    } else {
        SplitSessionContent(current, state.summary?.members.orEmpty(), userId, isOwner,
            enabled = state.canInteract && pending.isEmpty,
            onClaim = { unit, action -> scope.launch { mutations.claim(current, unit, action) } },
            onFinalize = { scope.launch { mutations.finalize(current) } },
        )
        if (current.status == "finalized" && isOwner) {
            BarButton(onClick = entry.onAddBill, enabled = !state.working && pending.isEmpty, modifier = Modifier.fillMaxWidth()) { Text(t("+ Add a bill")) }
        }
    }
}
