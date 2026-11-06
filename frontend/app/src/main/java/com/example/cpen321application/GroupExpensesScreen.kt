package com.example.cpen321application

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun GroupExpensesScreen(session: Session, callbacks: GroupScreenCallbacks, entry: GroupScreenEntry = GroupScreenEntry(),
                                 services: GroupScreenServices = GroupScreenServices()) {
    val state = rememberSaveable(saver = GroupScreenState.Saver) { GroupScreenState(entry.groupId, entry.startCreating) }
    val actions = GroupActions(state, session, rememberCoroutineScope(), callbacks.onSessionExpired, services.deleteBill)
    GroupScreenEffects(state, session, callbacks, entry, services)
    BackHandler { state.goBack(callbacks.onExit) }
    if (!state.showingLiveSplit) GroupHeader(state) { state.goBack(callbacks.onExit) }
    GroupActionStatus(state)
    if (state.creating) CreateGroupForm(state.request.working, state.request.error, actions::create)
    GroupLoadStatus(state)
    if (!state.creating && state.selectedId == null && state.ready) GroupList(state)
    val current = state.summary
    if (!state.creating && current != null && state.ready) GroupPage(state, actions, current, session, callbacks)
    GroupDialogs(state, actions, session, callbacks)
}

@Composable
internal fun GroupSummaryContent(summary: GroupExpenseSummary, selfId: String) {
    val currency = summary.group.currency
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Total group spending"))
            Text(Money.display(summary.totalCents, currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(tf("%1\$d expenses · %2\$d members", summary.expenseCount, summary.members.size))
            val mine = summary.memberShares.find { it.userId == selfId }?.amountCents ?: 0L
            Text(tf("Your allocated share: %1\$s", Money.display(mine, currency)))
        }
    }
    Text(t("Allocated shares"), style = MaterialTheme.typography.titleMedium)
    Text(t("Each member's portion of recorded expenses. These amounts do not track repayments."), style = MaterialTheme.typography.bodySmall)
    summary.members.forEach { member ->
        val cents = summary.memberShares.find { it.userId == member.id }?.amountCents ?: 0L
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(member.displayName + if (member.id == selfId) t(" (you)") else "", modifier = Modifier.weight(1f))
            Text(Money.display(cents, currency))
        }
    }
    val formerShares = summary.memberShares.filter { share -> summary.members.none { it.id == share.userId } }
    if (formerShares.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(t("Former members (saved expenses)"), modifier = Modifier.weight(1f))
            Text(Money.display(formerShares.sumOf { it.amountCents }, currency))
        }
    }
}

@Composable
internal fun BillCard(bill: GroupBill, summary: GroupExpenseSummary, onEdit: (() -> Unit)? = null, onImport: (() -> Unit)? = null,
                      onDelete: (() -> Unit)? = null, imported: Boolean = bill.importedByMe) {
    var expanded by rememberSaveable(bill.id) { mutableStateOf(false) }
    fun name(id: String) = summary.members.find { it.id == id }?.displayName ?: t("Former member")
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(bill.description, style = MaterialTheme.typography.titleMedium)
            Text(Money.display(bill.amountCents, summary.group.currency), fontWeight = FontWeight.Bold)
            Text(tf("Subtotal %1\$s · Tax %2\$s · Tip %3\$s", Money.display(bill.subtotalCents, summary.group.currency), Money.display(bill.taxCents, summary.group.currency), Money.display(bill.tipCents, summary.group.currency)))
            val splitLabel = when (bill.splitMode) {
                "equal" -> t("Equal split")
                "custom" -> t("Custom split")
                "percentage" -> t("Percentage split")
                "adjusted" -> t("Equal split + adjustments")
                else -> bill.splitMode
            }
            Text(tf("Paid by %1\$s · %2\$s", name(bill.paidBy), splitLabel))
            val date = runCatching {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(L10n.locale).withZone(ZoneId.systemDefault()).format(Instant.parse(bill.createdAt))
            }.getOrDefault(bill.createdAt)
            Text(date, style = MaterialTheme.typography.bodySmall)
            bill.personalInput?.let { input ->
                Text("${input.date} · ${t(input.category)}", style = MaterialTheme.typography.bodySmall)
                if (expanded && input.notes.isNotBlank()) Text(input.notes)
            }
            BarButton(onClick = { expanded = !expanded }) { Text(if (expanded) t("Hide split") else t("View split")) }
            if (bill.canEdit && onEdit != null) BarButton(onClick = onEdit) { Text(t("Edit expense")) }
            if (bill.canEdit && onDelete != null) BarButton(onClick = onDelete) { Text(t("Delete expense"), color = MaterialTheme.colorScheme.error) }
            if (onImport != null) BarButton(onClick = onImport, enabled = !imported) { Text(if (imported) t("Imported") else t("Import my share")) }
            if (expanded) bill.shares.forEach { share ->
                val percentage = share.percentageBasisPoints?.let { " · ${Money.format((it).toLong())}%" }.orEmpty()
                val adjustment = share.adjustmentCents?.takeIf { it != 0L }
                    ?.let { " · " + tf("includes %1\$s", (if (it > 0) "+" else "") + adjustmentText(it, Money.digits(summary.group.currency))) }.orEmpty()
                Text("${name(share.userId)}: ${Money.display(share.amountCents, summary.group.currency)}$percentage$adjustment")
            }
        }
    }
}
