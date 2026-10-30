package com.example.cpen321application

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun GroupSettingsScreen(
    summary: GroupExpenseSummary, selfId: String, busy: Boolean, error: String?,
    onRemoveMember: (String) -> Unit, onLeave: () -> Unit,
) {
    val isOwner = summary.group.ownerId == selfId
    var removingId by rememberSaveable(summary.group.id) { mutableStateOf<String?>(null) }
    var leaving by rememberSaveable(summary.group.id) { mutableStateOf(false) }
    val removing = summary.members.find { it.id == removingId }

    Text(summary.group.name, style = MaterialTheme.typography.titleLarge)
    Text("Members (${summary.members.size})", style = MaterialTheme.typography.titleMedium)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    summary.members.forEach { member ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(member.displayName + if (member.id == selfId) " (you)" else "")
                Text(if (member.id == summary.group.ownerId) "Owner" else "Member",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (isOwner && member.id != selfId) TextButton(
                onClick = { removingId = member.id }, enabled = !busy,
                modifier = Modifier.testTag("remove-member-${member.id}"),
            ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        }
    }
    HorizontalDivider()
    Text(if (isOwner) "Leaving as owner deletes this group for everyone."
        else "Leaving removes your access. Saved expenses and items already claimed remain in the group.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { leaving = true }, enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag("leave-group")) {
        Text("Leave group", color = MaterialTheme.colorScheme.error)
    }

    if (removing != null) AlertDialog(
        onDismissRequest = { if (!busy) removingId = null },
        title = { Text("Remove ${removing.displayName}?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("They will lose access to this group. Saved expenses and items already claimed will be kept.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { onRemoveMember(removing.id) }, enabled = !busy) {
            Text(if (busy) "Removing…" else "Remove member", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { removingId = null }, enabled = !busy) { Text("Cancel") } },
    )
    if (leaving) AlertDialog(
        onDismissRequest = { if (!busy) leaving = false },
        title = { Text(if (isOwner) "Leave and delete group?" else "Leave ${summary.group.name}?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isOwner) "You are the owner. This deletes the group for all members and disables its invitation links. This cannot be undone."
                else "You will no longer see this group or access its expenses. Saved expenses and items already claimed will be kept.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = onLeave, enabled = !busy) {
            Text(if (busy) "Leaving…" else if (isOwner) "Leave and delete group" else "Confirm leave",
                color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { leaving = false }, enabled = !busy) { Text("Cancel") } },
    )
}
