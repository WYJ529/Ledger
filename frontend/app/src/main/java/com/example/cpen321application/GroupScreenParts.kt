package com.example.cpen321application

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Live updates, periodic list refresh, entry handling and loading for the Group tab. */
@Composable
internal fun GroupScreenEffects(state: GroupScreenState, session: Session, callbacks: GroupScreenCallbacks,
                                entry: GroupScreenEntry, services: GroupScreenServices) {
    val subscribedGroup = state.selectedId
    if (subscribedGroup != null && !state.splitSessionOpen) {
        services.liveConnection(session.token, subscribedGroup, SplitLiveCallbacks(
            subscribed = { state.refresh() },
            disconnected = {},
            expired = callbacks.onSessionExpired,
            update = { state.refresh() },
            groupChanged = { state.refresh() },
            membershipChanged = { change ->
                if (change == GroupMembershipChange.Deleted) state.unavailableGroup() else state.refresh()
            },
        ))
    }
    // Refresh list membership on return and recover missed socket events while visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(session.token, state.selectedId, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (state.selectedId == null) state.refresh()
                delay(5000)
            }
        }
    }
    LaunchedEffect(entry.groupId, entry.startCreating) {
        entry.groupId?.let { state.selectedId = it }
        if (entry.startCreating) state.creating = true
        entry.bill?.let(state::openBill)
        if (entry.groupId != null || entry.startCreating) entry.onHandled()
    }
    LaunchedEffect(state.selectedId, state.editing, state.editingBillId, state.splitSessionOpen, state.creating, state.settingsOpen) {
        callbacks.onNavigate()
    }
    LaunchedEffect(session.token, state.selectedId, state.revision) {
        state.load(session.token, services.reader, callbacks.onSessionExpired)
    }
    val context = LocalContext.current
    LaunchedEffect(state.summary) { state.summary?.let { syncImportedShares(state, it, session, services, context) } }
}

// Finds shares this device saved that the server does not know about, and repeats the mark that failed.
private suspend fun syncImportedShares(state: GroupScreenState, summary: GroupExpenseSummary, session: Session,
                                       services: GroupScreenServices, context: Context) {
    val waiting = summary.expenses.filter { bill ->
        !bill.importedByMe && bill.shares.any { it.userId == session.account.id && it.amountCents > 0 }
    }.map { it.id }
    if (waiting.isEmpty()) return
    val groupId = summary.group.id
    val saved = withContext(Dispatchers.IO) { services.localImports(context, session.account.id, groupId, waiting) }
    state.importedBillIds += saved
    handleRequestFailures({}) {
        withContext(Dispatchers.IO) { saved.forEach { services.markImported(session, groupId, it) } }
    }
}

/** Back button, settings button and title. */
@Composable
internal fun GroupHeader(state: GroupScreenState, onBack: () -> Unit) {
    val current = state.summary
    val showSettingsButton = current != null && !state.creating && !state.editing && !state.settingsOpen
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            state.backLabel?.let { BackBar(it, onClick = onBack, enabled = !state.request.working) }
        }
        if (showSettingsButton) BarIconButton(onClick = { state.settingsOpen = true; state.request.error = null }, enabled = !state.request.working) {
            Icon(painterResource(R.drawable.ic_group_settings), contentDescription = t("Group settings"))
        }
    }
    val title = when {
        state.settingsOpen -> t("Group settings")
        current != null && !state.creating && !state.editing -> "${current.group.name} (${current.members.size})"
        else -> t("Group expenses")
    }
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
}

/** Progress and group-action errors shown above the page content. */
@Composable
internal fun GroupActionStatus(state: GroupScreenState) {
    if (state.request.working) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (!state.creating && !state.settingsOpen) state.request.error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
}

/** Notices, loading progress and load errors with their retry buttons. */
@Composable
internal fun GroupLoadStatus(state: GroupScreenState) {
    state.notice?.let { Text(t(it), color = MaterialTheme.colorScheme.primary) }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let {
        Text(t(it), color = MaterialTheme.colorScheme.error)
        BarButton(onClick = { state.refresh() }) { Text(t("Retry")) }
    }
    if (state.selectedId != null && !state.splitSessionOpen && state.summary != null) state.rosterError?.let {
        Text(t(it), color = MaterialTheme.colorScheme.error)
        BarButton(onClick = { state.refresh() }) { Text(t("Retry member update")) }
    }
}

/** The list of the member's groups, with Create group and Join group. */
@Composable
internal fun GroupList(state: GroupScreenState) {
    PrimaryBarButton(onClick = { state.creating = true; state.request.error = null }, modifier = Modifier.fillMaxWidth()) {
        Text(t("Create group"))
    }
    BarButton(onClick = { state.joinOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(t("Join group")) }
    if (state.groups.isEmpty()) {
        Text(t("No groups yet"), style = MaterialTheme.typography.titleLarge)
        Text(t("Groups you belong to will appear here. Once a group is available, you can add and split its expenses."))
    }
    state.groups.forEach { group ->
        BarButton(onClick = { state.openGroup(group.id) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text(tf("%1\$s · View expenses", group.currency))
            }
        }
    }
}

/** The selected group's settings, expense editor, Let's Go Dutch or overview. */
@Composable
internal fun GroupPage(state: GroupScreenState, actions: GroupActions, current: GroupExpenseSummary, session: Session,
                       callbacks: GroupScreenCallbacks) {
    when {
        state.settingsOpen -> GroupSettingsScreen(current, session.account.id, state.request.working, state.request.error, GroupSettingsActions(
            onRemoveMember = { actions.removeMember(current.group.id, it) },
            onLeave = { actions.leave(current.group.id) },
            onInvite = { actions.invite() },
            onRename = { state.renameText = current.group.name; state.request.error = null; state.renameOpen = true },
        ))
        state.editing -> GroupBillEditorPage(state, current, session, callbacks.onSessionExpired)
        state.splitSessionOpen -> GroupLiveSplitScreen(session, current.group.id, callbacks.onSessionExpired,
            onBack = state::closeSplit, onGroupUnavailable = { state.unavailableGroup() }, entry = SplitEntry(
                bill = if (state.byItem) state.bill else null,
                onStarted = state::splitStarted,
                onAddBill = { state.closeSplit(); state.addBill(current.group, callbacks.onAddBill) },
            ))
        else -> GroupOverview(state, current, session.account.id, callbacks.onAddBill)
    }
}

@Composable
private fun GroupBillEditorPage(state: GroupScreenState, current: GroupExpenseSummary, session: Session, onSessionExpired: () -> Unit) {
    val billId = state.editingBillId
    val billToEdit = current.expenses.find { it.id == billId }
    if (billId != null && billToEdit == null) {
        Text(t("This expense is no longer in the recent list. Go back and refresh the group."), color = MaterialTheme.colorScheme.error)
    } else key(current.group.id, billId) {
        BillEditor(current, session, onSessionExpired, onSaved = {
            state.notice = if (billId == null) t("Expense saved.") else t("Expense updated.")
            state.closeEditor()
            state.refresh()
        }, bill = billToEdit, prefill = state.bill, onByItem = if (billId == null) state::splitByItem else null)
    }
}

@Composable
private fun GroupOverview(state: GroupScreenState, current: GroupExpenseSummary, selfId: String, onAddBill: ((ExpenseGroup) -> Unit)?) {
    GroupSummaryContent(current, selfId)
    if (current.group.description.isNotBlank()) Text(current.group.description)
    PrimaryBarButton(onClick = { state.addBill(current.group, onAddBill) }, modifier = Modifier.fillMaxWidth()) {
        Text(t("+ Add a bill"))
    }
    if (state.openSplits.isNotEmpty()) BarButton(onClick = { state.splitSessionOpen = true; state.notice = null }, enabled = !state.request.working,
        modifier = Modifier.fillMaxWidth()) {
        Text(state.openSplits.singleOrNull()?.let { tf("Claim items · %1\$s", it.description) }
            ?: tf("Claim items · %1\$d open splits", state.openSplits.size))
    }
    if (current.expenses.isEmpty()) Text(t("No expenses yet. Add your first shared bill."))
    else Text(t("Expenses"), style = MaterialTheme.typography.titleLarge)
    if (current.expenseCount > current.expenses.size) {
        Text(tf("Showing the latest %1\$d expenses. Totals include all %2\$d expenses.", current.expenses.size, current.expenseCount))
    }
    current.expenses.forEach { bill ->
        val hasShare = bill.shares.any { it.userId == selfId && it.amountCents > 0 }
        BillCard(bill, current, imported = bill.importedByMe || bill.id in state.importedBillIds,
            onEdit = { state.editingBillId = bill.id; state.editing = true; state.notice = null },
            onImport = if (hasShare) ({ state.importBillId = bill.id }) else null,
            onDelete = { state.deleteBillId = bill.id; state.request.error = null; state.notice = null })
    }
}

/** Import, delete, join, share and rename dialogs. */
@Composable
internal fun GroupDialogs(state: GroupScreenState, actions: GroupActions, session: Session, callbacks: GroupScreenCallbacks) {
    val current = state.summary
    current?.expenses?.find { it.id == state.importBillId }?.let { bill ->
        key(session.token, current.group.id, bill.id) {
            ImportGroupShareDialog(session, current.group, bill, callbacks.onSessionExpired, onImported = {
                state.importedBillIds += bill.id
                callbacks.onLedgerChanged()
            }) { state.importBillId = null }
        }
    }
    current?.expenses?.find { it.id == state.deleteBillId }?.let { bill ->
        DeleteExpenseDialog(state, bill, current.group.currency) { actions.deleteExpense(current.group.id, bill) }
    }
    if (state.joinOpen) JoinGroupDialog(onOpen = { state.joinOpen = false; callbacks.onOpenInvitation(it) }, onDismiss = { state.joinOpen = false })
    if (state.shareOpen) InviteShareDialog(state.shareGroupName, session.account.displayName, state.shareInvitation, state.request.working,
        state.request.error, onRetry = { actions.invite() }, onClose = { state.shareOpen = false; state.request.error = null })
    if (state.renameOpen && current != null) RenameGroupDialog(state) { actions.rename(current.group.id) }
}

@Composable
private fun DeleteExpenseDialog(state: GroupScreenState, bill: GroupBill, currency: String, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!state.request.working) state.deleteBillId = null }, title = { Text(t("Delete expense?")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tf("Delete \"%1\$s\" (%2\$s) for all members? This cannot be undone.", bill.description, Money.display(bill.amountCents, currency)))
            state.request.error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        BarButton(enabled = !state.request.working, onClick = onConfirm) { Text(t("Delete"), color = MaterialTheme.colorScheme.error) }
    }, dismissButton = {
        BarButton(enabled = !state.request.working, onClick = { state.deleteBillId = null; state.request.error = null }) { Text(t("Cancel")) }
    })
}

@Composable
private fun RenameGroupDialog(state: GroupScreenState, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!state.request.working) state.renameOpen = false }, title = { Text(t("Rename group")) }, text = {
        Column {
            OutlinedTextField(state.renameText, { state.renameText = it.take(60) }, label = { Text(t("Group name *")) }, enabled = !state.request.working)
            state.request.error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        BarButton(enabled = !state.request.working && state.renameText.trim().isNotEmpty(), onClick = onConfirm) { Text(t("Save name")) }
    }, dismissButton = {
        BarButton(enabled = !state.request.working, onClick = { state.renameOpen = false }) { Text(t("Cancel")) }
    })
}
