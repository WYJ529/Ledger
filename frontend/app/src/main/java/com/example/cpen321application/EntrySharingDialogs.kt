package com.example.cpen321application

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class EntrySharingDependencies(
    val gateway: EntrySharingGateway = EntrySharingApi,
    val databaseName: String = "ledger_guest.db"
)

@Composable
internal fun ShareEntriesDialog(session: Session, records: List<LedgerRecord>, onExpired: () -> Unit,
                                gateway: EntrySharingGateway = EntrySharingApi, onDismiss: () -> Unit) {
    var groups by remember { mutableStateOf<List<ExpenseGroup>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var completed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(true) }
    val request = remember { RequestState() }
    val working = request.working
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session.token, revision) {
        loading = true
        try { request.run(onExpired) { groups = withContext(Dispatchers.IO) { gateway.groups(session.token) } } }
        finally { loading = false }
    }
    val targets = groups.filter { it.id in selected && shareProblem(records, it) == null }
    val tasks = targets.flatMap { group -> records.map { group to it } }
    val pending = tasks.filter { (group, record) -> "${group.id}/${record.id}" !in completed }
    AlertDialog(onDismissRequest = { if (!working) onDismiss() }, title = { Text(t("Share to groups")) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Each expense is copied to every selected group, paid by you and split equally among its current members. Date, category and notes are shared too."))
            records.forEach { Text("${it.input.merchant} · ${Money.display(it.amountMinor, it.input.currency)}") }
            if (loading || working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!loading && groups.isEmpty()) Text(t("Create or join a group first."))
            groups.forEach { group ->
                SharingGroupRow(group, records, group.id in selected, completed, !working) { checked ->
                    selected = if (checked) selected + group.id else selected - group.id
                }
            }
            if (completed.isNotEmpty()) Text(tf("%1\$d copies saved or already shared.", completed.size))
            request.error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
            if (!loading && !working) BarButton(onClick = { revision++ }) { Text(t("Refresh groups")) }
        }
    }, confirmButton = {
        BarButton(enabled = !working && !loading && pending.isNotEmpty(), onClick = {
            scope.launch {
                request.run(onExpired) {
                    // Each completed target stays visible if a later target fails. Retry only pending copies.
                    for ((group, record) in pending) {
                        withContext(Dispatchers.IO) { gateway.share(session, group, record) }
                        completed = completed + "${group.id}/${record.id}"
                    }
                }
            }
        }) { Text(t("Share selected")) }
    }, dismissButton = { BarButton(enabled = !working, onClick = onDismiss) { Text(t("Done")) } })
}

@Composable
private fun SharingGroupRow(group: ExpenseGroup, records: List<LedgerRecord>, selected: Boolean,
                            completed: List<String>, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    val problem = shareProblem(records, group)
    Row {
        Checkbox(checked = selected, enabled = enabled && problem == null, onCheckedChange = onSelect)
        Column(Modifier.weight(1f)) {
            Text("${group.name} · ${group.currency}")
            Text(tf("Equal split among %1\$d members", group.memberCount))
            val savedCount = records.count { "${group.id}/${it.id}" in completed }
            if (savedCount > 0) Text(tf("%1\$d of %2\$d saved", savedCount, records.size))
            problem?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun ImportGroupShareDialog(session: Session, group: ExpenseGroup, bill: GroupBill,
                                   onExpired: () -> Unit, onImported: () -> Unit,
                                   dependencies: EntrySharingDependencies = EntrySharingDependencies(), onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { LedgerRepository(context, databaseName = dependencies.databaseName) }
    val gateway = dependencies.gateway
    DisposableEffect(repository) {
        onDispose {
            // An import may still hold the repository lock while resolving a historical rate.
            CoroutineScope(Dispatchers.IO).launch { repository.close() }
        }
    }
    var preview by remember { mutableStateOf<PersonalShare?>(null) }
    var originalRevision by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val request = remember { RequestState() }
    val working = request.working
    var saved by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session.token, group.id, bill.id, revision) {
        error = null
        request.run(onExpired) {
            val result = withContext(Dispatchers.IO) {
                val value = gateway.personalShare(session, group.id, bill.id)
                value to repository.groupImports.original(value.sourcePersonalId)?.revision
            }
            preview = result.first
            originalRevision = result.second
        }
    }
    AlertDialog(onDismissRequest = { if (!working) onDismiss() }, title = { Text(t("Import my share")) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            preview?.let { ImportSharePreview(it, originalRevision != null) }
            (error ?: request.error)?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(t(it)) }
            if (preview == null && !working) BarButton(onClick = { revision++ }) { Text(t("Retry")) }
        }
    }, confirmButton = {
        BarButton(enabled = preview != null && !working && !saved, onClick = {
            preview?.let { value -> scope.launch {
                error = null
                request.run(onExpired) {
                    // Re-read the authenticated server allocation immediately before the local write.
                    val current = withContext(Dispatchers.IO) { gateway.personalShare(session, group.id, bill.id) }
                    if (current != value) {
                        preview = current
                        error = "This expense changed. Review your updated share before importing."
                    } else {
                        val added = withContext(Dispatchers.IO) { repository.groupImports.importShare(session.account.id,
                            group.id, bill.id, current.input, current.sourcePersonalId, current.groupName, originalRevision) }
                        saved = true
                        notice = if (added) "Your share was imported." else "This share was already imported."
                        onImported()
                    }
                }
            } }
        }) { Text(t("Confirm import")) }
    }, dismissButton = { BarButton(enabled = !working, onClick = onDismiss) { Text(t("Done")) } })
}

@Composable
private fun ImportSharePreview(share: PersonalShare, replacesOriginal: Boolean) {
    Text(share.input.merchant)
    Text(Money.display(Money.requireMinor(share.input.amount, share.input.currency), share.input.currency))
    Text(share.input.date)
    Text(t("Only your allocated share is imported. This is a one-time snapshot."))
    if (replacesOriginal) Text(t("Your original personal entry will be updated to your share to avoid counting the same expense twice."))
}
