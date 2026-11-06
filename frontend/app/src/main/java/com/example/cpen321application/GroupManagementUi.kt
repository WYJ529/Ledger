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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreateGroupForm(busy: Boolean, error: String?, onSubmit: (String, String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("CAD") }
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    Text(t("Create group"), style = MaterialTheme.typography.headlineMedium)
    Text(t("You will be the owner. Invite your friends after creating the group."))
    OutlinedTextField(name, { name = it.take(60) }, label = { Text(t("Group name *")) }, enabled = !busy,
        singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(description, { description = it.take(240) }, label = { Text(t("Description (optional)")) },
        enabled = !busy, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
    Text(t("Group currency"), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LedgerDomain.currencies.forEach { item ->
            FilterChip(selected = currency == item, onClick = { currency = item }, label = { Text(item) }, enabled = !busy)
        }
    }
    Text(t("All expenses in this group use the same currency."), style = MaterialTheme.typography.bodySmall)
    error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
    PrimaryBarButton(onClick = { onSubmit(name.trim(), description.trim(), currency, requestId) }, enabled = !busy && name.trim().isNotEmpty(),
        modifier = Modifier.fillMaxWidth()) { Text(if (busy) t("Creating…") else t("Create group")) }
}

@Composable
internal fun InviteShareDialog(groupName: String, inviterName: String, invitation: GroupInvitationLink?, busy: Boolean,
                              error: String?, onRetry: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val link = invitation?.link
    val expiresAt = invitation?.expiresAt
    var copied by remember(link) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(tf("Invite to %1\$s", groupName)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
            if (link != null) {
                Text(t("Share this invitation with the people you want to join."))
                OutlinedTextField(link, {}, readOnly = true, label = { Text(t("Invitation link")) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                val expiry = runCatching { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(L10n.locale).withZone(ZoneId.systemDefault()).format(Instant.parse(expiresAt)) }.getOrNull()
                if (expiry != null) Text(tf("Expires %1\$s", expiry), style = MaterialTheme.typography.bodySmall)
                Text(t("You can also paste this link into Join group in Ledger."), style = MaterialTheme.typography.bodySmall)
                if (copied) Text(t("Link copied"), color = MaterialTheme.colorScheme.primary)
            }
        }
    }, confirmButton = {
        if (link != null) {
            BarButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(t("Ledger invitation"), link))
                copied = true
            }) { Text(t("Copy link")) }
            BarButton(onClick = {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, tf("%1\$s invites you to %2\$s on Ledger.\n%3\$s\nCopy this link into the Ledger app (Group > Join group) to join.", inviterName, groupName, link))
                }
                context.startActivity(Intent.createChooser(share, t("Share invitation")))
            }) { Text(t("Share")) }
        } else if (!busy) BarButton(onClick = onRetry) { Text(t("Retry")) }
    }, dismissButton = { BarButton(onClick = onClose, enabled = !busy) { Text(t("Done")) } })
}

@Composable
internal fun JoinGroupDialog(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("Join group")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("Paste an invitation link to see who invited you before joining."))
            OutlinedTextField(link, { link = it.take(2048); invalid = false }, label = { Text(t("Invitation link")) },
                isError = invalid, modifier = Modifier.fillMaxWidth())
            if (invalid) Text(t("Enter a valid Ledger invitation link."), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { BarButton(enabled = link.isNotBlank(), onClick = {
        if (invitationTokenFromLink(link) == null) invalid = true else onOpen(link.trim())
    }) { Text(t("Continue")) } }, dismissButton = { BarButton(onClick = onDismiss) { Text(t("Cancel")) } })
}

@Composable
internal fun InvitationScreen(session: Session, link: String, onAccepted: (String) -> Unit, onRejected: () -> Unit, onSessionExpired: () -> Unit) {
    val invitationToken = remember(link) { invitationTokenFromLink(link) }
    var preview by remember(link) { mutableStateOf<InvitationPreview?>(null) }
    var loading by remember { mutableStateOf(true) }
    val request = remember(session.token, link) { RequestState() }
    val busy = request.working && !loading
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session.token, link, revision) {
        loading = true
        try {
            request.run(onSessionExpired) {
                if (invitationToken == null) request.error = "Invalid invitation link."
                else preview = withContext(Dispatchers.IO) { GroupManagementApi.preview(session.token, invitationToken) }
            }
        } finally { loading = false }
    }
    BackHandler { if (!busy) onRejected() }
    Text(t("Group invitation"), style = MaterialTheme.typography.headlineMedium)
    if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    request.error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
    val details = preview
    if (details != null && !loading) {
        Text(tf("%1\$s invites you to %2\$s", details.inviterName, details.group.name), style = MaterialTheme.typography.titleLarge)
        if (details.group.description.isNotBlank()) Text(details.group.description)
        Text(tf("%1\$d members · %2\$s", details.group.memberCount, details.group.currency))
        if (details.alreadyMember) Text(t("You are already a member. Accept to open the group."))
        PrimaryBarButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                request.run(onSessionExpired) {
                    onAccepted(withContext(Dispatchers.IO) { GroupManagementApi.accept(session.token, invitationToken!!) }.id)
                }
            }
        }) { Text(t("Accept")) }
    } else if (!loading && invitationToken != null) BarButton(onClick = { revision++ }, enabled = !busy) { Text(t("Retry")) }
    BarButton(onClick = onRejected, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(t("Reject — return home")) }
}
