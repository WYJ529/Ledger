package com.example.cpen321application

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
internal fun CreateGroupForm(busy: Boolean, error: String?, onSubmit: (String, String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("CAD") }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    Text("Create group", style = MaterialTheme.typography.headlineMedium)
    Text("You will be the owner. Invite your friends after creating the group.")
    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Group name *") }, enabled = !busy,
        singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(description, { description = it.take(240) }, label = { Text("Description (optional)") },
        enabled = !busy, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
    Text("Group currency", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("CAD", "USD", "EUR", "CNY").forEach { item ->
            FilterChip(selected = currency == item, onClick = { currency = item }, label = { Text(item) }, enabled = !busy)
        }
    }
    Text("All expenses in this group use the same currency.", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = { onSubmit(name.trim(), description.trim(), currency, requestId) }, enabled = !busy && name.trim().isNotEmpty(),
        modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Creating…" else "Create group") }
}

@Composable
internal fun InviteShareDialog(groupName: String, inviterName: String, link: String?, expiresAt: String?, busy: Boolean,
                              error: String?, onRetry: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var copied by remember(link) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("Invite to $groupName") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (link != null) {
                Text("Share this invitation with the people you want to join.")
                OutlinedTextField(link, {}, readOnly = true, label = { Text("Invitation link") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                val expiry = runCatching { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(expiresAt)) }.getOrNull()
                if (expiry != null) Text("Expires $expiry", style = MaterialTheme.typography.bodySmall)
                Text("You can also paste this link into Join group in Ledger.", style = MaterialTheme.typography.bodySmall)
                if (copied) Text("Link copied", color = MaterialTheme.colorScheme.primary)
            }
        }
    }, confirmButton = {
        if (link != null) {
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Ledger invitation", link))
                copied = true
            }) { Text("Copy link") }
            TextButton(onClick = {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "$inviterName invites you to \"$groupName\" on Ledger.\n$link")
                }
                context.startActivity(Intent.createChooser(share, "Share invitation"))
            }) { Text("Share") }
        } else if (!busy) TextButton(onClick = onRetry) { Text("Retry") }
    }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Done") } })
}

@Composable
internal fun JoinGroupDialog(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Join group") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paste an invitation link to see who invited you before joining.")
            OutlinedTextField(link, { link = it.take(2048); invalid = false }, label = { Text("Invitation link") },
                isError = invalid, modifier = Modifier.fillMaxWidth())
            if (invalid) Text("Enter a valid Ledger invitation link.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = link.isNotBlank(), onClick = {
        if (invitationTokenFromLink(link) == null) invalid = true else onOpen(link.trim())
    }) { Text("Continue") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun GroupMembersDialog(members: List<GroupMember>, selfId: String, onInvite: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Members (${members.size})", modifier = Modifier.weight(1f))
            IconButton(onClick = onInvite) { Icon(painterResource(android.R.drawable.ic_input_add), contentDescription = "Invite member") }
        }
    }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            members.forEach { member ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(member.displayName + if (member.id == selfId) " (you)" else "", modifier = Modifier.weight(1f))
                    Text(if (member.role == "owner") "Owner" else "Member", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
internal fun InvitationScreen(session: Session, link: String, onAccepted: (String) -> Unit, onRejected: () -> Unit, onSessionExpired: () -> Unit) {
    val invitationToken = remember(link) { invitationTokenFromLink(link) }
    var preview by remember(link) { mutableStateOf<InvitationPreview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session.token, link, revision) {
        loading = true
        error = null
        try {
            if (invitationToken == null) error = "Invalid invitation link."
            else preview = withContext(Dispatchers.IO) { GroupManagementApi.preview(session.token, invitationToken) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiException && e.status == 401) onSessionExpired() else error = e.message
        } finally { loading = false }
    }
    BackHandler { if (!busy) onRejected() }
    Text("Group invitation", style = MaterialTheme.typography.headlineMedium)
    if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val details = preview
    if (details != null && !loading) {
        Text("${details.inviterName} invites you to “${details.group.name}”", style = MaterialTheme.typography.titleLarge)
        if (details.group.description.isNotBlank()) Text(details.group.description)
        Text("${details.group.memberCount} members · ${details.group.currency}")
        if (details.alreadyMember) Text("You are already a member. Accept to open the group.")
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true
            error = null
            scope.launch {
                try { onAccepted(withContext(Dispatchers.IO) { GroupManagementApi.accept(session.token, invitationToken!!) }.id) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (e is ApiException && e.status == 401) onSessionExpired() else error = e.message
                } finally { busy = false }
            }
        }) { Text("Accept") }
    } else if (!loading && invitationToken != null) OutlinedButton(onClick = { revision++ }, enabled = !busy) { Text("Retry") }
    OutlinedButton(onClick = onRejected, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Reject — return home") }
}
