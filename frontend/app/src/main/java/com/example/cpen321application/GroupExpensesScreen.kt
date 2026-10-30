package com.example.cpen321application

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    liveConnection: @Composable (String, String, SplitLiveCallbacks) -> Unit = { token, id, callbacks ->
        GroupSplitLiveConnection(token, id, callbacks)
    },
) {
    var groups by remember { mutableStateOf<List<ExpenseGroup>>(emptyList()) }
    var selectedId by rememberSaveable { mutableStateOf(initialGroupId) }
    var summary by remember { mutableStateOf<GroupExpenseSummary?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var splitSessionOpen by rememberSaveable { mutableStateOf(false) }
    var editingBillId by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var creating by rememberSaveable { mutableStateOf(startCreating) }
    var working by remember { mutableStateOf(false) }
    var managementError by remember { mutableStateOf<String?>(null) }
    var rosterError by remember { mutableStateOf<String?>(null) }
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
    var liveConnected by remember(session.token, selectedId) { mutableStateOf(false) }

    fun unavailableGroup() {
        summary = null
        selectedId = null
        splitSessionOpen = false
        editing = false
        editingBillId = null
        membersOpen = false
        shareOpen = false
        renameOpen = false
        deleteOpen = false
        notice = "This group is no longer available."
    }

    val subscribedGroup = selectedId
    if (subscribedGroup != null && !splitSessionOpen) {
        liveConnection(session.token, subscribedGroup, SplitLiveCallbacks(
            subscribed = { liveConnected = true; revision++ },
            disconnected = { liveConnected = false },
            expired = onSessionExpired,
            update = {},
            membershipChanged = { change ->
                if (change == GroupMembershipChange.Deleted) unavailableGroup() else revision++
            },
        ))
    }

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

    LaunchedEffect(selectedId, editing, editingBillId, splitSessionOpen, creating) { onNavigate() }

    LaunchedEffect(session.token, selectedId, revision) {
        // Keep the editor composed during a roster refresh; cancelling an older read
        // prevents an out-of-order response from replacing a newer roster.
        loading = selectedId == null || summary?.group?.id != selectedId
        error = null
        rosterError = null
        if (summary?.group?.id != selectedId) summary = null
        try {
            val id = selectedId
            if (id == null) groups = withContext(Dispatchers.IO) { reader.groups(session.token) }
            else summary = withContext(Dispatchers.IO) { reader.summary(session.token, id) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiException && e.status == 401) onSessionExpired()
            else if (e is ApiException && e.status in setOf(403, 404) && selectedId != null) unavailableGroup()
            else if (summary != null) rosterError = e.message ?: "Unable to refresh members. Try again."
            else error = e.message ?: "Unable to load group expenses."
        } finally { loading = false }
    }

    fun goBack() {
        if (working) return
        managementError = null
        when {
            creating -> creating = false
            splitSessionOpen -> { splitSessionOpen = false; revision++ }
            editing -> { editing = false; editingBillId = null }
            selectedId != null -> { selectedId = null; notice = null }
            else -> onExit()
        }
    }
    // The on-screen button and Android back gesture use exactly the same route.
    BackHandler { goBack() }
    val showingLiveSplit = splitSessionOpen && !creating && !editing && summary != null && !loading && error == null
    if (!showingLiveSplit) {
        BackBar(when {
            creating -> "Back to groups"
            editing || splitSessionOpen -> "Back to group expenses"
            selectedId != null -> "Back to groups"
            else -> "Back to home"
        }, onClick = { goBack() }, enabled = !working)
        Text("Group expenses", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
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
        BarButton(onClick = { revision++ }) { Text("Retry") }
    }
    if (selectedId != null && !splitSessionOpen && summary != null) {
        Text(if (liveConnected) "Live · member updates" else "Reconnecting · member updates paused",
            style = MaterialTheme.typography.bodySmall)
        rosterError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            BarButton(onClick = { revision++ }) { Text("Retry member update") }
        }
    }
    if (!creating && selectedId == null && !loading && error == null) {
        PrimaryBarButton(onClick = { creating = true; managementError = null }, modifier = Modifier.fillMaxWidth()) { Text("Create group") }
        BarButton(onClick = { joinOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Join group") }
        if (groups.isEmpty()) {
            Text("No groups yet", style = MaterialTheme.typography.titleLarge)
            Text("Groups you belong to will appear here. Once a group is available, you can add and split its expenses.")
        }
        groups.forEach { group ->
            BarButton(onClick = { summary = null; selectedId = group.id; editing = false }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Text("${group.currency} · View expenses")
                }
            }
        }
        BarButton(onClick = { revision++ }, enabled = !loading) { Text("Refresh groups") }
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
        } else if (splitSessionOpen) {
            GroupLiveSplitScreen(session, current.group.id, onSessionExpired, onBack = {
                splitSessionOpen = false
                revision++
            })
        } else {
            GroupSummaryContent(current, session.account.id)
            if (current.group.description.isNotBlank()) Text(current.group.description)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarButton(onClick = { membersOpen = true }, enabled = !working) { Text("Members (${current.members.size})") }
                BarIconButton(onClick = { invite() }, enabled = !working) {
                    Icon(painterResource(android.R.drawable.ic_input_add), contentDescription = "Invite member")
                }
            }
            BarButton(onClick = { renameText = current.group.name; managementError = null; renameOpen = true }, enabled = !working) { Text("Rename group") }
            if (current.group.ownerId == session.account.id) BarButton(onClick = { managementError = null; deleteOpen = true }, enabled = !working) {
                Text("Delete group", color = MaterialTheme.colorScheme.error)
            }
            PrimaryBarButton(onClick = { editingBillId = null; editing = true; notice = null }, modifier = Modifier.fillMaxWidth()) { Text("Add expense") }
            BarButton(onClick = { splitSessionOpen = true; notice = null }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                Text("Let’s Go Dutch")
            }
            BarButton(onClick = { revision++ }) { Text("Refresh expenses") }
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
        }, confirmButton = { BarButton(enabled = !working && renameText.trim().isNotEmpty(), onClick = { manage {
            withContext(Dispatchers.IO) { GroupManagementApi.rename(session.token, current.group.id, renameText.trim()) }
            renameOpen = false
            notice = "Group renamed."
            revision++
        } }) { Text("Save name") } }, dismissButton = { BarButton(enabled = !working, onClick = { renameOpen = false }) { Text("Cancel") } })
    if (deleteOpen && current != null) AlertDialog(onDismissRequest = { if (!working) deleteOpen = false }, title = { Text("Delete ${current.group.name}?") },
        text = { Column {
            Text("This group will disappear for all members and its invitation links will stop working.")
            managementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { BarButton(enabled = !working, onClick = { manage {
            withContext(Dispatchers.IO) { GroupManagementApi.delete(session.token, current.group.id) }
            deleteOpen = false
            selectedId = null
            summary = null
            notice = "Group deleted."
            revision++
        } }) { Text("Delete group", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { BarButton(enabled = !working, onClick = { deleteOpen = false }) { Text("Cancel") } })
}

@Composable
internal fun GroupSummaryContent(summary: GroupExpenseSummary, selfId: String) {
    val currency = summary.group.currency
    Text(summary.group.name, style = MaterialTheme.typography.titleLarge)
    Panel(Modifier.fillMaxWidth()) {
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
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(bill.description, style = MaterialTheme.typography.titleMedium)
            Text(displayMoney(summary.group.currency, bill.amountCents), fontWeight = FontWeight.Bold)
            Text("Subtotal ${displayMoney(summary.group.currency, bill.subtotalCents)} · Tax ${displayMoney(summary.group.currency, bill.taxCents)} · Tip ${displayMoney(summary.group.currency, bill.tipCents)}")
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
            BarButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide split" else "View split") }
            if (bill.canEdit && onEdit != null) BarButton(onClick = onEdit) { Text("Edit expense") }
            if (expanded) bill.shares.forEach { share ->
                val percentage = share.percentageBasisPoints?.let { " · ${percentageText(it)}%" }.orEmpty()
                val adjustment = share.adjustmentCents?.takeIf { it != 0L }
                    ?.let { " · includes ${if (it > 0) "+" else ""}${adjustmentText(it, moneyDigits(summary.group.currency))}" }.orEmpty()
                Text("${name(share.userId)}: ${displayMoney(summary.group.currency, share.amountCents)}$percentage$adjustment")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BillEditor(summary: GroupExpenseSummary, session: Session, onSessionExpired: () -> Unit,
                        onSaved: () -> Unit, bill: GroupBill? = null) {
    val currency = summary.group.currency
    // Amount fields use the group currency's decimals; tip and tax rates always use two.
    val digits = moneyDigits(currency)
    var description by rememberSaveable { mutableStateOf(bill?.description.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(bill?.let { decimalAmount(it.amountCents, digits) }.orEmpty()) }
    var mode by rememberSaveable { mutableStateOf(bill?.splitMode ?: "equal") }
    var legacy by rememberSaveable { mutableStateOf(bill?.splitMode == "custom" && bill.customCalculation == null) }
    var tipChoice by rememberSaveable { mutableStateOf(bill?.customCalculation?.tipRate ?: "0") }
    var paidBy by rememberSaveable { mutableStateOf(bill?.paidBy ?: session.account.id) }
    var selected by rememberSaveable { mutableStateOf(bill?.shares?.map { it.userId } ?: summary.members.map { it.id }) }
    var custom by rememberSaveable { mutableStateOf(summary.members.associate { member ->
        member.id to (bill?.customCalculation?.members?.find { it.userId == member.id }?.let { decimalAmount(it.subtotalCents, digits) }
            ?: if (legacy) bill?.shares?.find { it.userId == member.id }?.let { decimalAmount(it.amountCents, digits) }.orEmpty() else "")
    }) }
    var extraEnabled by rememberSaveable { mutableStateOf(summary.members.associate { member ->
        member.id to (bill?.customCalculation?.members?.find { it.userId == member.id }?.let { moneyCents(it.extraTaxRate, true) != 0L } ?: false)
    }) }
    var extraRates by rememberSaveable { mutableStateOf(summary.members.associate { member ->
        member.id to (bill?.customCalculation?.members?.find { it.userId == member.id }?.extraTaxRate ?: "7")
    }) }
    var percentages by rememberSaveable { mutableStateOf(summary.members.associate { member ->
        member.id to bill?.shares?.find { it.userId == member.id }?.percentageBasisPoints?.let(::percentageText).orEmpty()
    }) }
    var adjustments by rememberSaveable { mutableStateOf(summary.members.associate { member ->
        member.id to bill?.shares?.find { it.userId == member.id }?.adjustmentCents?.let { adjustmentText(it, digits) }.orEmpty()
    }) }
    // Retain the version originally opened, even if a rotation reloads a newer summary.
    val expectedRevision = rememberSaveable { bill?.revision ?: 0 }
    val originalAmounts = rememberSaveable {
        listOf(bill?.amountCents, bill?.subtotalCents, bill?.taxCents, bill?.tipCents)
    }
    // Kept across rotation and uncertain network results so retrying cannot duplicate a bill.
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mealMembers = summary.members.mapNotNull { member ->
        if (member.id !in selected) null else moneyCents(custom[member.id].orEmpty(), true, digits)?.let {
            MealMember(member.id, it, if (extraEnabled[member.id] == true) extraRates[member.id] ?: "7" else "0")
        }
    }
    val meal = if (mode == "custom" && !legacy && mealMembers.size == selected.size) MealCalculation(tipChoice, mealMembers) else null
    val calculated = meal?.let { calculateMeal(it) }
    val total = if (mode != "custom" || legacy) moneyCents(amount, digits = digits) else calculated?.total
    val preserveBreakdown = (mode != "custom" || legacy) && total != null && total == originalAmounts[0]
    val subtotal = if (preserveBreakdown) originalAmounts[1] else if (mode != "custom" || legacy) total else calculated?.subtotal
    val taxAmount = if (preserveBreakdown) originalAmounts[2] ?: 0L else calculated?.tax ?: 0L
    val tipAmount = if (preserveBreakdown) originalAmounts[3] ?: 0L else calculated?.tip ?: 0L
    val percentageValues = summary.members.mapNotNull { member ->
        if (member.id !in selected) null else percentageBasisPoints(percentages[member.id].orEmpty())?.let { member.id to it }
    }
    val percentageComplete = percentageValues.size == selected.size && percentageValues.sumOf { it.second } == 10000
    val adjustmentValues = summary.members.mapNotNull { member ->
        if (member.id !in selected) null else adjustmentCents(adjustments[member.id].orEmpty(), digits)?.let { member.id to it }
    }
    val adjustedShares = if (total != null && selected.isNotEmpty() && adjustmentValues.size == selected.size)
        adjustedBillShares(total, adjustmentValues) else null
    val adjustedComplete = mode != "adjusted" || adjustedShares != null
    val shares = when (mode) {
        "equal" -> if (total != null && selected.isNotEmpty()) equalBillShares(total, selected) else emptyList()
        "percentage" -> if (total != null && percentageComplete && selected.isNotEmpty()) percentageBillShares(total, percentageValues) else emptyList()
        "adjusted" -> adjustedShares.orEmpty()
        "custom" -> if (legacy) mealMembers.map { BillShare(it.userId, it.subtotalCents) } else calculated?.shares.orEmpty()
        else -> emptyList()
    }
    val valid = description.trim().isNotEmpty() && total != null && selected.isNotEmpty() &&
        (mode != "percentage" || percentageComplete) && adjustedComplete &&
        (mode in listOf("equal", "custom", "percentage") || (mode == "adjusted" && bill?.splitMode == "adjusted")) &&
        shares.size == selected.size && shares.sumOf { it.amountCents } == total && paidBy in summary.members.map { it.id }
    Text("${if (bill == null) "New expense" else "Edit expense"} · ${summary.group.name}", style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mode == "equal", onClick = { mode = "equal" }, label = { Text("Equal split") }, enabled = !busy)
        FilterChip(selected = mode == "custom", onClick = { mode = "custom" }, label = { Text("Custom amounts") }, enabled = !busy)
        FilterChip(selected = mode == "percentage", onClick = { mode = "percentage" }, label = { Text("Percentage") }, enabled = !busy)
    }
    OutlinedTextField(description, { description = it.take(100) }, label = { Text("Description") },
        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (mode != "custom" || legacy) {
        OutlinedTextField(amount, { amount = it.take(12) }, label = { Text("Final total (${summary.group.currency})") },
            supportingText = { Text("Includes all tax and tip · maximum ${displayMoney(currency, MAX_BILL_CENTS)}") },
            isError = amount.isNotEmpty() && total == null, enabled = !busy, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    }
    if (mode == "custom" && legacy) {
        Text("This older bill stores final shares. Its amounts are preserved; switch explicitly to recalculate from pretax amounts.")
        BarButton(enabled = !busy, onClick = { legacy = false; custom = emptyMap(); tipChoice = "0" }) {
            Text("Recalculate from pretax amounts")
        }
    }
    if (mode == "adjusted") Text("Legacy adjustment bill. Its saved adjustments are preserved; new expenses use Equal, Custom or Percentage.")
    if (mode == "custom" && !legacy) {
        Text("Meal split · GST 5% on each person's pretax amount. Optional extra tax applies to that person's entire amount.")
        Text("Tip rate · calculated before tax")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("0", "10", "15", "20").forEach { choice ->
                FilterChip(selected = moneyCents(tipChoice, true) == moneyCents(choice, true), onClick = { tipChoice = choice }, enabled = !busy,
                    label = { Text("$choice%") })
            }
        }
        OutlinedTextField(tipChoice, { tipChoice = it.take(6) }, label = { Text("Tip percent (0–100, custom allowed)") },
            isError = tipCents(100, tipChoice) == null, enabled = !busy, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    }
    Text("Who paid?", style = MaterialTheme.typography.titleMedium)
    summary.members.forEach { member ->
        Row {
            RadioButton(selected = paidBy == member.id, onClick = { paidBy = member.id }, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Paid by ${member.displayName}" })
            BarButton(onClick = { paidBy = member.id }, enabled = !busy) { Text(member.displayName) }
        }
    }
    Text("Split between", style = MaterialTheme.typography.titleMedium)
    summary.members.forEach { member ->
        Row {
            Checkbox(checked = member.id in selected, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Include ${member.displayName} in split" }, onCheckedChange = { checked ->
                selected = if (checked) selected + member.id else selected - member.id
            })
            Column(Modifier.weight(1f)) {
                Text(member.displayName, modifier = Modifier.padding(top = 12.dp))
                if (member.id in selected && mode == "custom") {
                    OutlinedTextField(custom[member.id].orEmpty(), { text -> custom = custom + (member.id to text.take(12)) },
                        label = { Text(if (legacy) "Saved final share (${summary.group.currency})" else "Pretax amount · ${member.displayName}") },
                        enabled = !busy, singleLine = true, isError = custom[member.id].orEmpty().isNotEmpty() && moneyCents(custom[member.id].orEmpty(), true, digits) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    if (!legacy) {
                        Row {
                            Checkbox(checked = extraEnabled[member.id] == true, enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = "Extra tax for ${member.displayName}" }, onCheckedChange = { checked ->
                                extraEnabled = extraEnabled + (member.id to checked)
                            })
                            Text("Extra tax · ${member.displayName}", modifier = Modifier.padding(top = 12.dp))
                        }
                        if (extraEnabled[member.id] == true) OutlinedTextField(extraRates[member.id] ?: "7", { value -> extraRates = extraRates + (member.id to value.take(6)) },
                            label = { Text("Extra tax % · ${member.displayName}") }, enabled = !busy, singleLine = true,
                            isError = tipCents(100, extraRates[member.id] ?: "7") == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    }
                }
                if (member.id in selected && mode == "percentage") {
                    OutlinedTextField(percentages[member.id].orEmpty(), { text -> percentages = percentages + (member.id to text.take(12)) },
                        label = { Text("Share (%)") }, enabled = !busy, singleLine = true,
                        isError = percentages[member.id].orEmpty().isNotEmpty() && percentageBasisPoints(percentages[member.id].orEmpty()) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                if (member.id in selected && mode == "adjusted") {
                    OutlinedTextField(adjustments[member.id].orEmpty(), { text -> adjustments = adjustments + (member.id to text.take(12)) },
                        label = { Text("Adjustment (${summary.group.currency})") }, placeholder = { Text("0") },
                        supportingText = { Text("e.g. 8 for an extra drink, -2 to pay less") },
                        enabled = !busy, singleLine = true, isError = adjustmentCents(adjustments[member.id].orEmpty(), digits) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                if (member.id in selected) shares.find { it.userId == member.id }?.let {
                    Text("To pay: ${displayMoney(summary.group.currency, it.amountCents)}")
                }
            }
        }
    }
    if (selected.isEmpty()) Text("Select at least one member.", color = MaterialTheme.colorScheme.error)
    if (mode == "custom" && !legacy && calculated != null) {
        Text("Subtotal: ${displayMoney(summary.group.currency, calculated.subtotal)}")
        Text("Tax: ${displayMoney(summary.group.currency, calculated.tax)} · Tip: ${displayMoney(summary.group.currency, calculated.tip)}")
    }
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
    if (total != null) Text("Total: ${displayMoney(summary.group.currency, total)}", fontWeight = FontWeight.Bold)
    if (!valid) Text("Enter a description and valid amounts for every selected member. Final total must be positive and at most ${displayMoney(currency, MAX_BILL_CENTS)}.", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    PrimaryBarButton(enabled = valid && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = BillSubmission(description.trim(), paidBy, mode, shares,
                        BillAmounts(total!!, subtotal!!, taxAmount, tipAmount), meal, digits)
                    if (bill == null) {
                        GroupExpenseApi.addBill(session.token, summary.group.id, input, requestId)
                    } else {
                        GroupExpenseApi.updateBill(session.token, summary.group.id, bill.id, input, expectedRevision)
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
