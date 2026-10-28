package com.example.cpen321application

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun GroupExpensesScreen(
    session: Session,
    onSessionExpired: () -> Unit,
    onExit: () -> Unit,
    onNavigate: suspend () -> Unit = {},
    reader: GroupExpenseReader = GroupExpenseApi,
    initialGroupId: String? = null,
    startCreating: Boolean = false,
    onEntryHandled: () -> Unit = {},
    onOpenInvitation: (String) -> Unit = {},
) {
    var groups by remember { mutableStateOf<List<ExpenseGroup>>(emptyList()) }
    var selectedId by rememberSaveable { mutableStateOf(initialGroupId) }
    var summary by remember { mutableStateOf<GroupExpenseSummary?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingBillId by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var creating by rememberSaveable { mutableStateOf(startCreating) }
    var working by remember { mutableStateOf(false) }
    var managementError by remember { mutableStateOf<String?>(null) }
    var membersOpen by rememberSaveable { mutableStateOf(false) }
    var joinOpen by rememberSaveable { mutableStateOf(false) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    var shareOpen by rememberSaveable { mutableStateOf(false) }
    var shareGroupName by rememberSaveable { mutableStateOf("") }
    var shareLink by rememberSaveable { mutableStateOf<String?>(null) }
    var shareExpiry by rememberSaveable { mutableStateOf<String?>(null) }
    val managementScope = rememberCoroutineScope()

    LaunchedEffect(initialGroupId, startCreating) {
        if (initialGroupId != null) selectedId = initialGroupId
        if (startCreating) creating = true
        if (initialGroupId != null || startCreating) onEntryHandled()
    }
    fun manage(action: suspend () -> Unit) {
        if (working) return
        managementScope.launch {
            working = true
            managementError = null
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiException && e.status == 401) onSessionExpired()
                else managementError = e.message ?: "Could not complete the group action."
            } finally { working = false }
        }
    }
    fun invite() {
        val current = summary ?: return
        membersOpen = false
        shareGroupName = current.group.name
        shareLink = null
        shareExpiry = null
        shareOpen = true
        manage {
            val result = withContext(Dispatchers.IO) { GroupManagementApi.invite(session.token, current.group.id) }
            shareLink = result.link
            shareExpiry = result.expiresAt
        }
    }

    LaunchedEffect(selectedId, editing, editingBillId, creating) { onNavigate() }

    LaunchedEffect(session.token, selectedId, revision) {
        loading = true
        error = null
        // Clear a previous group's data before switching or retrying a failed read.
        summary = null
        try {
            val id = selectedId
            if (id == null) groups = withContext(Dispatchers.IO) { reader.groups(session.token) }
            else summary = withContext(Dispatchers.IO) { reader.summary(session.token, id) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiException && e.status == 401) onSessionExpired()
            else error = e.message ?: "Unable to load group expenses."
        } finally { loading = false }
    }

    fun goBack() {
        if (working) return
        managementError = null
        when {
            creating -> creating = false
            editing -> { editing = false; editingBillId = null }
            selectedId != null -> { selectedId = null; notice = null }
            else -> onExit()
        }
    }
    // The on-screen button and Android back gesture use exactly the same route.
    BackHandler { goBack() }
    TextButton(onClick = { goBack() }, enabled = !working) {
        Text(when {
            creating -> "Back to groups"
            editing -> "Back to group expenses"
            selectedId != null -> "Back to groups"
            else -> "Back to home"
        })
    }
    Text("Group expenses", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (!creating) managementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (creating) CreateGroupForm(working, managementError) { name, description, currency, requestId ->
        manage {
            val result = withContext(Dispatchers.IO) { GroupManagementApi.create(session.token, name, description, currency, requestId) }
            creating = false
            selectedId = result.group.id
            summary = null
            revision++
            shareGroupName = result.group.name
            shareLink = result.invitation.link
            shareExpiry = result.invitation.expiresAt
            shareOpen = true
            notice = "Group created. You are the owner."
        }
    }
    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error != null) {
        Text(error!!, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { revision++ }) { Text("Retry") }
    }
    if (!creating && selectedId == null && !loading && error == null) {
        Button(onClick = { creating = true; managementError = null }, modifier = Modifier.fillMaxWidth()) { Text("Create group") }
        OutlinedButton(onClick = { joinOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Join group") }
        if (groups.isEmpty()) {
            Text("No groups yet", style = MaterialTheme.typography.titleLarge)
            Text("Groups you belong to will appear here. Once a group is available, you can add and split its expenses.")
        }
        groups.forEach { group ->
            FilledTonalButton(onClick = { summary = null; selectedId = group.id; editing = false }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Text("${group.currency} · View expenses")
                }
            }
        }
        OutlinedButton(onClick = { revision++ }, enabled = !loading) { Text("Refresh groups") }
    }
    val current = summary
    if (!creating && current != null && !loading && error == null) {
        if (editing) {
            val billToEdit = current.expenses.find { it.id == editingBillId }
            if (editingBillId != null && billToEdit == null) {
                Text("This expense is no longer in the recent list. Go back and refresh the group.", color = MaterialTheme.colorScheme.error)
            } else key(current.group.id, editingBillId) {
                BillEditor(current, session, onSessionExpired, onSaved = {
                    notice = if (editingBillId == null) "Expense saved." else "Expense updated."
                    editing = false
                    editingBillId = null
                    revision++
                }, bill = billToEdit)
            }
        } else {
            GroupSummaryContent(current, session.account.id)
            if (current.group.description.isNotBlank()) Text(current.group.description)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { membersOpen = true }, enabled = !working) { Text("Members (${current.members.size})") }
                IconButton(onClick = { invite() }, enabled = !working) {
                    Icon(painterResource(android.R.drawable.ic_input_add), contentDescription = "Invite member")
                }
            }
            TextButton(onClick = { renameText = current.group.name; managementError = null; renameOpen = true }, enabled = !working) { Text("Rename group") }
            if (current.group.ownerId == session.account.id) TextButton(onClick = { managementError = null; deleteOpen = true }, enabled = !working) {
                Text("Delete group", color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { editingBillId = null; editing = true; notice = null }, modifier = Modifier.fillMaxWidth()) { Text("Add expense") }
            OutlinedButton(onClick = { revision++ }) { Text("Refresh expenses") }
            if (current.expenses.isEmpty()) Text("No expenses yet. Add your first shared bill.")
            else Text("Expenses", style = MaterialTheme.typography.titleLarge)
            if (current.expenseCount > current.expenses.size) Text("Showing the latest ${current.expenses.size} expenses. Totals include all ${current.expenseCount} expenses.")
            current.expenses.forEach { bill -> BillCard(bill, current, onEdit = {
                editingBillId = bill.id
                editing = true
                notice = null
            }) }
        }
    }
    if (membersOpen && current != null) GroupMembersDialog(current.members, session.account.id, onInvite = { invite() }, onDismiss = { membersOpen = false })
    if (joinOpen) JoinGroupDialog(onOpen = { joinOpen = false; onOpenInvitation(it) }, onDismiss = { joinOpen = false })
    if (shareOpen) InviteShareDialog(shareGroupName, session.account.displayName, shareLink, shareExpiry, working, managementError,
        onRetry = { invite() }, onClose = { shareOpen = false; managementError = null })
    if (renameOpen && current != null) AlertDialog(onDismissRequest = { if (!working) renameOpen = false },
        title = { Text("Rename group") }, text = {
            Column {
                OutlinedTextField(renameText, { renameText = it.take(60) }, label = { Text("Group name *") }, enabled = !working)
                managementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = !working && renameText.trim().isNotEmpty(), onClick = { manage {
            withContext(Dispatchers.IO) { GroupManagementApi.rename(session.token, current.group.id, renameText.trim()) }
            renameOpen = false
            notice = "Group renamed."
            revision++
        } }) { Text("Save name") } }, dismissButton = { TextButton(enabled = !working, onClick = { renameOpen = false }) { Text("Cancel") } })
    if (deleteOpen && current != null) AlertDialog(onDismissRequest = { if (!working) deleteOpen = false }, title = { Text("Delete ${current.group.name}?") },
        text = { Column {
            Text("This group will disappear for all members and its invitation links will stop working.")
            managementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !working, onClick = { manage {
            withContext(Dispatchers.IO) { GroupManagementApi.delete(session.token, current.group.id) }
            deleteOpen = false
            selectedId = null
            summary = null
            notice = "Group deleted."
            revision++
        } }) { Text("Delete group", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !working, onClick = { deleteOpen = false }) { Text("Cancel") } })
}

@Composable
internal fun GroupSummaryContent(summary: GroupExpenseSummary, selfId: String) {
    val currency = summary.group.currency
    Text(summary.group.name, style = MaterialTheme.typography.titleLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Total group spending")
            Text(displayMoney(currency, summary.totalCents), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${summary.expenseCount} expenses · ${summary.members.size} members")
            val mine = summary.memberShares.find { it.userId == selfId }?.amountCents ?: 0L
            Text("Your allocated share: ${displayMoney(currency, mine)}")
        }
    }
    Text("Allocated shares", style = MaterialTheme.typography.titleMedium)
    Text("Each member's portion of recorded expenses. These amounts do not track repayments.", style = MaterialTheme.typography.bodySmall)
    summary.members.forEach { member ->
        val cents = summary.memberShares.find { it.userId == member.id }?.amountCents ?: 0L
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(member.displayName + if (member.id == selfId) " (you)" else "", modifier = Modifier.weight(1f))
            Text(displayMoney(currency, cents))
        }
    }
}

@Composable
internal fun BillCard(bill: GroupBill, summary: GroupExpenseSummary, onEdit: (() -> Unit)? = null) {
    var expanded by rememberSaveable(bill.id) { mutableStateOf(false) }
    fun name(id: String) = summary.members.find { it.id == id }?.displayName ?: "Former member"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(bill.description, style = MaterialTheme.typography.titleMedium)
            Text(displayMoney(summary.group.currency, bill.amountCents), fontWeight = FontWeight.Bold)
            val splitLabel = when (bill.splitMode) {
                "equal" -> "Equal split"
                "custom" -> "Custom split"
                "percentage" -> "Percentage split"
                "adjusted" -> "Equal split + adjustments"
                else -> bill.splitMode
            }
            Text("Paid by ${name(bill.paidBy)} · $splitLabel")
            val date = runCatching {
                DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(bill.createdAt))
            }.getOrDefault(bill.createdAt)
            Text(date, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide split" else "View split") }
            if (bill.canEdit && onEdit != null) TextButton(onClick = onEdit) { Text("Edit expense") }
            if (expanded) bill.shares.forEach { share ->
                val percentage = share.percentageBasisPoints?.let { " · ${percentageText(it)}%" }.orEmpty()
                val adjustment = share.adjustmentCents?.takeIf { it != 0L }
                    ?.let { " · includes ${if (it > 0) "+" else ""}${adjustmentText(it)}" }.orEmpty()
                Text("${name(share.userId)}: ${displayMoney(summary.group.currency, share.amountCents)}$percentage$adjustment")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BillEditor(summary: GroupExpenseSummary, session: Session, onSessionExpired: () -> Unit,
                        onSaved: () -> Unit, bill: GroupBill? = null) {
    var description by rememberSaveable { mutableStateOf(bill?.description.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(bill?.let { decimalAmount(it.amountCents) }.orEmpty()) }
    var mode by rememberSaveable { mutableStateOf(bill?.splitMode ?: "equal") }
    var paidBy by rememberSaveable { mutableStateOf(bill?.paidBy ?: session.account.id) }
    var selected by rememberSaveable { mutableStateOf(bill?.shares?.map { it.userId } ?: summary.members.map { it.id }) }
    var custom by rememberSaveable { mutableStateOf(summary.members.map { member ->
        bill?.shares?.find { it.userId == member.id }?.let { decimalAmount(it.amountCents) }.orEmpty()
    }) }
    var percentages by rememberSaveable { mutableStateOf(summary.members.map { member ->
        bill?.shares?.find { it.userId == member.id }?.percentageBasisPoints?.let(::percentageText).orEmpty()
    }) }
    var adjustments by rememberSaveable { mutableStateOf(summary.members.map { member ->
        bill?.shares?.find { it.userId == member.id }?.adjustmentCents?.let(::adjustmentText).orEmpty()
    }) }
    // Retain the version originally opened, even if a rotation reloads a newer summary.
    val expectedRevision = rememberSaveable { bill?.revision ?: 0 }
    // Kept across rotation and uncertain network results so retrying cannot duplicate a bill.
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val total = moneyCents(amount)
    val percentageValues = summary.members.mapIndexedNotNull { index, member ->
        if (member.id !in selected) null else percentageBasisPoints(percentages[index])?.let { member.id to it }
    }
    val percentageComplete = percentageValues.size == selected.size && percentageValues.sumOf { it.second } == 10000
    val adjustmentValues = summary.members.mapIndexedNotNull { index, member ->
        if (member.id !in selected) null else adjustmentCents(adjustments[index])?.let { member.id to it }
    }
    val adjustedShares = if (total != null && selected.isNotEmpty() && adjustmentValues.size == selected.size)
        adjustedBillShares(total, adjustmentValues) else null
    val adjustedComplete = mode != "adjusted" || adjustedShares != null
    val shares = when (mode) {
        "equal" -> if (total != null && selected.isNotEmpty()) equalBillShares(total, selected) else emptyList()
        "percentage" -> if (total != null && percentageComplete) percentageBillShares(total, percentageValues) else emptyList()
        "adjusted" -> adjustedShares.orEmpty()
        "custom" -> summary.members.mapIndexedNotNull { index, member ->
            if (member.id !in selected) null else moneyCents(custom[index], allowZero = true)?.let { BillShare(member.id, it) }
        }
        else -> emptyList()
    }
    val customComplete = mode != "custom" || (shares.size == selected.size && shares.sumOf { it.amountCents } == total)
    val valid = description.trim().isNotEmpty() && total != null && selected.isNotEmpty() && customComplete &&
        (mode != "percentage" || percentageComplete) && adjustedComplete &&
        mode in listOf("equal", "custom", "percentage", "adjusted") && paidBy in summary.members.map { it.id }
    val showCalculatedShares = mode in listOf("equal", "percentage", "adjusted") && total != null && shares.isNotEmpty()
    Text("${if (bill == null) "New expense" else "Edit expense"} · ${summary.group.name}", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(description, { description = it.take(100) }, label = { Text("Description") },
        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(amount, { amount = it.take(12) }, label = { Text("Total (${summary.group.currency})") },
        supportingText = { Text("0.01 to 1,000,000.00 · up to two decimal places") },
        isError = amount.isNotEmpty() && total == null, enabled = !busy, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    Text("Who paid?", style = MaterialTheme.typography.titleMedium)
    summary.members.forEach { member ->
        Row {
            RadioButton(selected = paidBy == member.id, onClick = { paidBy = member.id }, enabled = !busy)
            TextButton(onClick = { paidBy = member.id }, enabled = !busy) { Text(member.displayName) }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == "equal", onClick = { mode = "equal" }, label = { Text("Equal split") }, enabled = !busy)
        FilterChip(selected = mode == "custom", onClick = { mode = "custom" }, label = { Text("Custom amounts") }, enabled = !busy)
        FilterChip(selected = mode == "percentage", onClick = { mode = "percentage" }, label = { Text("Percentage") }, enabled = !busy)
        FilterChip(selected = mode == "adjusted", onClick = { mode = "adjusted" }, label = { Text("Equal + adjustments") }, enabled = !busy)
    }
    Text("Split between", style = MaterialTheme.typography.titleMedium)
    summary.members.forEachIndexed { index, member ->
        Row {
            Checkbox(checked = member.id in selected, enabled = !busy, onCheckedChange = { checked ->
                selected = if (checked) selected + member.id else selected - member.id
            })
            Column(Modifier.weight(1f)) {
                Text(member.displayName, modifier = Modifier.padding(top = 12.dp))
                if (member.id in selected && mode == "custom") {
                    OutlinedTextField(custom[index], { text -> custom = custom.toMutableList().also { it[index] = text.take(12) } },
                        label = { Text("Share (${summary.group.currency})") }, enabled = !busy, singleLine = true,
                        isError = custom[index].isNotEmpty() && moneyCents(custom[index], true) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                if (member.id in selected && mode == "percentage") {
                    OutlinedTextField(percentages[index], { text -> percentages = percentages.toMutableList().also { it[index] = text.take(12) } },
                        label = { Text("Share (%)") }, enabled = !busy, singleLine = true,
                        isError = percentages[index].isNotEmpty() && percentageBasisPoints(percentages[index]) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                if (member.id in selected && mode == "adjusted") {
                    OutlinedTextField(adjustments[index], { text -> adjustments = adjustments.toMutableList().also { it[index] = text.take(12) } },
                        label = { Text("Adjustment (${summary.group.currency})") }, placeholder = { Text("0") },
                        supportingText = { Text("e.g. 8 for an extra drink, -2 to pay less") },
                        enabled = !busy, singleLine = true, isError = adjustmentCents(adjustments[index]) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                if (member.id in selected && showCalculatedShares) {
                    Text(displayMoney(summary.group.currency, shares.find { it.userId == member.id }?.amountCents ?: 0))
                }
            }
        }
    }
    if (selected.isEmpty()) Text("Select at least one member.", color = MaterialTheme.colorScheme.error)
    if (mode == "equal") Text("Extra cents go to members in a fixed order so the split always matches the total.", style = MaterialTheme.typography.bodySmall)
    else if (mode == "percentage") {
        Text("Assigned: ${percentageText(percentageValues.sumOf { it.second })}% / 100.00%")
        Text("Enter 0–100% per member, with up to two decimals. Extra cents go to the largest fractional shares; ties use a fixed member order.", style = MaterialTheme.typography.bodySmall)
        if (!percentageComplete) Text("Enter every selected member's percentage. The percentages must total 100%.", color = MaterialTheme.colorScheme.error)
    }
    else if (mode == "adjusted") {
        Text("Adjustments are set aside first; the rest of the bill is split equally, then each member's adjustment is added back.",
            style = MaterialTheme.typography.bodySmall)
        if (total != null && selected.isNotEmpty() && !adjustedComplete) {
            val problem = when {
                adjustmentValues.size != selected.size -> "Enter adjustments as amounts such as 8, -2.50 or leave them blank."
                adjustmentValues.sumOf { it.second } > total -> "The added amounts are larger than the bill total."
                else -> "A deduction is larger than that member's equal share. Reduce it so every share is at least 0."
            }
            Text(problem, color = MaterialTheme.colorScheme.error)
        }
    }
    else if (total != null) {
        Text("Assigned: ${displayMoney(summary.group.currency, shares.sumOf { it.amountCents })} / ${displayMoney(summary.group.currency, total)}")
        if (!customComplete) Text("Enter every selected member's share. The amounts must match the total exactly.", color = MaterialTheme.colorScheme.error)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(enabled = valid && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (bill == null) {
                        GroupExpenseApi.addBill(session.token, summary.group.id, description.trim(), total!!, paidBy, mode, shares, requestId)
                    } else {
                        GroupExpenseApi.updateBill(session.token, summary.group.id, bill.id, description.trim(), total!!, paidBy, mode, shares, expectedRevision)
                    }
                }
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiException && e.status == 401) onSessionExpired()
                else error = e.message ?: "Unable to save expense. Retry to check this submission."
            } finally { busy = false }
        }
    }) { Text(if (busy) "Saving…" else if (bill == null) "Save expense" else "Save changes") }
}
