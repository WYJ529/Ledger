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
    Text(tf("Members (%1\$d)", summary.members.size), style = MaterialTheme.typography.titleMedium)
    error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
    summary.members.forEach { member ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(member.displayName + if (member.id == selfId) t(" (you)") else "")
                Text(if (member.id == summary.group.ownerId) t("Owner") else t("Member"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (isOwner && member.id != selfId) BarButton(
                onClick = { removingId = member.id }, enabled = !busy,
                modifier = Modifier.testTag("remove-member-${member.id}"),
            ) { Text(t("Remove"), color = MaterialTheme.colorScheme.error) }
        }
    }
    HorizontalDivider()
    Text(if (isOwner) t("Leaving as owner deletes this group for everyone.")
        else t("Leaving removes your access. Saved expenses and items already claimed remain in the group."),
        style = MaterialTheme.typography.bodySmall)
    BarButton(onClick = { leaving = true }, enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag("leave-group")) {
        Text(t("Leave group"), color = MaterialTheme.colorScheme.error)
    }

    if (removing != null) AlertDialog(
        onDismissRequest = { if (!busy) removingId = null },
        title = { Text(tf("Remove %1\$s?", removing.displayName)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("They will lose access to this group. Saved expenses and items already claimed will be kept."))
            error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { BarButton(onClick = { onRemoveMember(removing.id) }, enabled = !busy) {
            Text(if (busy) t("Removing…") else t("Remove member"), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { BarButton(onClick = { removingId = null }, enabled = !busy) { Text(t("Cancel")) } },
    )
    if (leaving) AlertDialog(
        onDismissRequest = { if (!busy) leaving = false },
        title = { Text(if (isOwner) t("Leave and delete group?") else tf("Leave %1\$s?", summary.group.name)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isOwner) t("You are the owner. This deletes the group for all members and disables its invitation links. This cannot be undone.")
                else t("You will no longer see this group or access its expenses. Saved expenses and items already claimed will be kept."))
            error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { BarButton(onClick = onLeave, enabled = !busy) {
            Text(if (busy) t("Leaving…") else if (isOwner) t("Leave and delete group") else t("Confirm leave"),
                color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { BarButton(onClick = { leaving = false }, enabled = !busy) { Text(t("Cancel")) } },
    )
}
