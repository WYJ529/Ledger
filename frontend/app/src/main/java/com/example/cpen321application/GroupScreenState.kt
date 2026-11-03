package com.example.cpen321application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Callbacks from the Group tab to the rest of the app. */
internal class GroupScreenCallbacks(
    val onSessionExpired: () -> Unit,
    val onExit: () -> Unit,
    val onNavigate: suspend () -> Unit = {},
    val onOpenInvitation: (String) -> Unit = {},
    val onLedgerChanged: () -> Unit = {},
)

/** Where the Group tab opens: a group to show directly, or the create form. */
internal class GroupScreenEntry(val groupId: String? = null, val startCreating: Boolean = false, val onHandled: () -> Unit = {})

/** Backend access for the Group tab; tests replace these. */
internal class GroupScreenServices(
    val reader: GroupExpenseReader = GroupExpenseApi,
    val liveConnection: @Composable (String, String, SplitLiveCallbacks) -> Unit = { token, id, callbacks ->
        GroupSplitLiveConnection(token, id, callbacks)
    },
    val deleteBill: (token: String, groupId: String, billId: String, revision: Int) -> Unit = GroupExpenseApi::deleteBill,
)

/** Navigation, dialog and loading state of the Group tab. Navigation and dialogs survive recreation. */
@Stable
internal class GroupScreenState(selectedId: String?, creating: Boolean) {
    var groups by mutableStateOf<List<ExpenseGroup>>(emptyList())
    var summary by mutableStateOf<GroupExpenseSummary?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var revision by mutableIntStateOf(0)
    var working by mutableStateOf(false)
    var managementError by mutableStateOf<String?>(null)
    var rosterError by mutableStateOf<String?>(null)

    var selectedId by mutableStateOf(selectedId)
    var creating by mutableStateOf(creating)
    var editing by mutableStateOf(false)
    var editingBillId by mutableStateOf<String?>(null)
    var splitSessionOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var joinOpen by mutableStateOf(false)
    var renameOpen by mutableStateOf(false)
    var renameText by mutableStateOf("")
    var shareOpen by mutableStateOf(false)
    var shareGroupName by mutableStateOf("")
    var shareLink by mutableStateOf<String?>(null)
    var shareExpiry by mutableStateOf<String?>(null)
    var importBillId by mutableStateOf<String?>(null)
    var deleteBillId by mutableStateOf<String?>(null)

    /** Loaded without an error, so content can be shown. */
    val ready: Boolean get() = !loading && error == null
    val showingLiveSplit: Boolean get() = splitSessionOpen && !creating && !editing && summary != null && ready
    val shareInvitation: GroupInvitationLink? get() = shareLink?.let { GroupInvitationLink(it, shareExpiry.orEmpty()) }

    val backLabel: String? get() = when {
        settingsOpen -> "Back to group expenses"
        creating -> "Back to groups"
        editing || splitSessionOpen -> "Back to group expenses"
        selectedId != null -> "Back to groups"
        else -> null
    }

    fun refresh() { revision++ }

    fun openGroup(id: String) {
        summary = null
        selectedId = id
        editing = false
    }

    fun unavailableGroup() {
        summary = null
        selectedId = null
        splitSessionOpen = false
        editing = false
        editingBillId = null
        deleteBillId = null
        settingsOpen = false
        shareOpen = false
        renameOpen = false
        importBillId = null
        notice = "This group is no longer available."
    }

    /** The on-screen back button and Android's back gesture share this route. */
    fun goBack(onExit: () -> Unit) {
        if (working) return
        managementError = null
        when {
            settingsOpen -> settingsOpen = false
            creating -> creating = false
            splitSessionOpen -> { splitSessionOpen = false; refresh() }
            editing -> { editing = false; editingBillId = null }
            selectedId != null -> { selectedId = null; notice = null }
            else -> onExit()
        }
    }

    /** Reads the group list, or the selected group's summary, keeping an open editor composed during the read. */
    suspend fun load(token: String, reader: GroupExpenseReader, onSessionExpired: () -> Unit) {
        loading = if (selectedId == null) groups.isEmpty() else summary?.group?.id != selectedId
        error = null
        rosterError = null
        if (summary?.group?.id != selectedId) summary = null
        try {
            handleRequestFailures({ loadFailed(it, onSessionExpired) }) {
                val id = selectedId
                if (id == null) groups = withContext(Dispatchers.IO) { reader.groups(token) }
                else summary = withContext(Dispatchers.IO) { reader.summary(token, id) }
            }
        } finally { loading = false }
    }

    private fun loadFailed(e: Exception, onSessionExpired: () -> Unit) {
        val status = (e as? ApiException)?.status
        when {
            status == 401 -> onSessionExpired()
            status in setOf(403, 404) && selectedId != null -> unavailableGroup()
            summary != null -> rosterError = e.message ?: "Unable to refresh members. Try again."
            else -> error = e.message ?: "Unable to load group expenses."
        }
    }

    companion object {
        val Saver: Saver<GroupScreenState, Any> = listSaver(
            save = { listOf(it.selectedId, it.creating, it.editing, it.editingBillId, it.splitSessionOpen, it.settingsOpen,
                it.joinOpen, it.renameOpen, it.renameText, it.shareOpen, it.shareGroupName, it.shareLink, it.shareExpiry,
                it.importBillId, it.deleteBillId) },
            restore = { values -> GroupScreenState(values[0] as String?, values[1] as Boolean).apply {
                editing = values[2] as Boolean
                editingBillId = values[3] as String?
                splitSessionOpen = values[4] as Boolean
                settingsOpen = values[5] as Boolean
                joinOpen = values[6] as Boolean
                renameOpen = values[7] as Boolean
                renameText = values[8] as String
                shareOpen = values[9] as Boolean
                shareGroupName = values[10] as String
                shareLink = values[11] as String?
                shareExpiry = values[12] as String?
                importBillId = values[13] as String?
                deleteBillId = values[14] as String?
            } },
        )
    }
}

/** Group management requests. Only one runs at a time; failures appear as [GroupScreenState.managementError]. */
internal class GroupActions(
    private val state: GroupScreenState,
    private val session: Session,
    private val scope: CoroutineScope,
    private val onSessionExpired: () -> Unit,
    private val deleteBill: (String, String, String, Int) -> Unit,
) {
    private fun manage(action: suspend () -> Unit) {
        if (state.working) return
        scope.launch {
            state.working = true
            state.managementError = null
            try { handleRequestFailures(::fail) { action() } } finally { state.working = false }
        }
    }

    private fun fail(e: Exception) {
        if (e is ApiException && e.status == 401) return onSessionExpired()
        state.managementError = e.message ?: "Could not complete the group action."
        // A previous leave, remove or delete may have succeeded despite a lost response.
        if (e is ApiException && e.status == 404) state.refresh()
    }

    fun create(name: String, description: String, currency: String, requestId: String) = manage {
        val result = withContext(Dispatchers.IO) { GroupManagementApi.create(session.token, name, description, currency, requestId) }
        state.creating = false
        state.openGroup(result.group.id)
        state.refresh()
        state.shareGroupName = result.group.name
        state.shareLink = result.invitation.link
        state.shareExpiry = result.invitation.expiresAt
        state.shareOpen = true
        state.notice = "Group created. You are the owner."
    }

    fun invite() {
        val current = state.summary ?: return
        state.shareGroupName = current.group.name
        state.shareLink = null
        state.shareExpiry = null
        state.shareOpen = true
        manage {
            val result = withContext(Dispatchers.IO) { GroupManagementApi.invite(session.token, current.group.id) }
            state.shareLink = result.link
            state.shareExpiry = result.expiresAt
        }
    }

    fun removeMember(groupId: String, memberId: String) = manage {
        withContext(Dispatchers.IO) { GroupManagementApi.removeMember(session.token, groupId, memberId) }
        state.notice = "Member removed."
        state.refresh()
    }

    fun leave(groupId: String) = manage {
        val deleted = withContext(Dispatchers.IO) { GroupManagementApi.leave(session.token, groupId) }
        state.settingsOpen = false
        state.selectedId = null
        state.summary = null
        state.notice = if (deleted) "Group deleted for all members." else "You left the group."
        state.refresh()
    }

    fun rename(groupId: String) = manage {
        withContext(Dispatchers.IO) { GroupManagementApi.rename(session.token, groupId, state.renameText.trim()) }
        state.renameOpen = false
        state.notice = "Group renamed."
        state.refresh()
    }

    fun deleteExpense(groupId: String, bill: GroupBill) = manage {
        try { withContext(Dispatchers.IO) { deleteBill(session.token, groupId, bill.id, bill.revision) } }
        catch (e: ApiException) {
            // The expense changed or is no longer deletable; reload it so the dialog shows its current state.
            if (e.status == 403 || e.status == 409) state.refresh()
            throw e
        }
        state.deleteBillId = null
        state.notice = "Expense deleted."
        state.refresh()
    }
}
