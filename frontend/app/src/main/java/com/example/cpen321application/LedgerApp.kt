package com.example.cpen321application

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AccountNavigation(
    private val storage: SessionStorage,
    pageState: MutableState<String>,
    invitationState: MutableState<String?>,
    groupState: MutableState<String?>,
    creatingState: MutableState<Boolean>,
) {
    var page by pageState
    var pastedInvitation by invitationState
    var groupToOpen by groupState
    var createGroupRequested by creatingState
    var session by mutableStateOf(storage.load())
    var authNotice by mutableStateOf<String?>(null)

    fun signIn(value: Session) {
        updateSession(value)
        authNotice = null
        page = "home"
    }

    fun updateSession(value: Session) {
        storage.save(value)
        session = value
    }

    fun endSession(notice: String = "Your session has expired. Please sign in again.") {
        storage.clear()
        session = null
        authNotice = notice
        groupToOpen = null
        createGroupRequested = false
        page = "home"
    }

    fun createGroup() {
        createGroupRequested = true
        page = "groups"
    }
}

@Composable
private fun rememberAccountNavigation(): AccountNavigation {
    val context = LocalContext.current
    val storage = remember(context) { SessionStorage(context) }
    val page = rememberSaveable { mutableStateOf("home") }
    val invitation = rememberSaveable { mutableStateOf<String?>(null) }
    val group = rememberSaveable { mutableStateOf<String?>(null) }
    val creating = rememberSaveable { mutableStateOf(false) }
    return remember(storage) { AccountNavigation(storage, page, invitation, group, creating) }
}

@Composable
internal fun LedgerApp(ledger: LedgerViewModel, incomingInvitation: String?, onInvitationHandled: () -> Unit) {
    val navigation = rememberAccountNavigation()
    val pendingInvitation = incomingInvitation ?: navigation.pastedInvitation
    val scrollState = rememberScrollState()
    RefreshSavedSession(navigation)
    BackHandler(navigation.session != null && navigation.page != "home") { navigation.page = "home" }
    LaunchedEffect(navigation.page) {
        if (navigation.page !in setOf("home", "account", "groups", "guest-ledger")) navigation.page = "home"
        scrollState.scrollTo(0)
    }
    BackHandler(navigation.page == "guest-ledger") {
        if (ledger.page != LedgerPage.Dashboard) ledger.requestBack() else navigation.page = "home"
    }
    if (navigation.page == "guest-ledger" && pendingInvitation == null) {
        LedgerScreen(ledger, onOpenAccount = { navigation.page = "home" })
    } else {
        Scaffold { insets ->
            Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(scrollState).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                AccountContent(navigation, pendingInvitation, scrollState) {
                    navigation.pastedInvitation = null
                    onInvitationHandled()
                }
            }
        }
    }
}

@Composable
private fun RefreshSavedSession(navigation: AccountNavigation) {
    LaunchedEffect(navigation.session?.token) {
        val current = navigation.session
        if (current != null) {
            try {
                val account = withContext(Dispatchers.IO) { AccountApi.currentAccount(current.token) }
                navigation.updateSession(current.copy(account = account))
            } catch (e: ApiException) {
                if (e.status == 401) navigation.endSession()
            } catch (_: java.io.IOException) {
                // Keep the stored session while the backend is unreachable.
            } catch (_: org.json.JSONException) {
                // An unreadable response does not establish that the session is expired.
            } catch (_: IllegalArgumentException) {
                // An invalid local backend configuration must not erase the saved session.
            }
        }
    }
}

@Composable
private fun AccountContent(
    navigation: AccountNavigation, pendingInvitation: String?, scrollState: ScrollState, clearInvitation: () -> Unit,
) {
    val signedIn = navigation.session
    when {
        signedIn == null -> {
            if (pendingInvitation != null) Text("Sign in or create an account to view your group invitation.")
            AuthFlow(notice = navigation.authNotice, onSignedIn = navigation::signIn)
            if (pendingInvitation == null) {
                TextButton(onClick = { navigation.page = "guest-ledger" }) { Text("Open local guest ledger") }
                Text("This device’s guest records are separate from accounts and groups.", style = MaterialTheme.typography.bodySmall)
            }
        }
        pendingInvitation != null -> {
            Text("Ledger", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            key(pendingInvitation) {
                InvitationScreen(signedIn, pendingInvitation,
                    onAccepted = { groupId ->
                        navigation.groupToOpen = groupId
                        navigation.createGroupRequested = false
                        navigation.page = "groups"
                        clearInvitation()
                    },
                    onRejected = { navigation.page = "home"; clearInvitation() },
                    onSessionExpired = { navigation.endSession() })
            }
        }
        else -> SignedInPages(navigation, signedIn, scrollState)
    }
}

@Composable
private fun SignedInPages(navigation: AccountNavigation, signedIn: Session, scrollState: ScrollState) {
    if (navigation.page != "home" && navigation.page != "groups") {
        TextButton(onClick = { navigation.page = "home" }) { Text("Back to home") }
    }
    Text("Ledger", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    when (navigation.page) {
        "home" -> {
            Text("Shared expenses.\nMade simple.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Track group spending, split bills, and see everyone's share.")
            MenuButton("Local guest ledger", "Device-only records; not linked to your signed-in account") { navigation.page = "guest-ledger" }
            MenuButton("Account", "Signed in as ${signedIn.account.displayName}. View details or log out") { navigation.page = "account" }
            MenuButton("Group expenses", "Split bills and view your group's spending") { navigation.page = "groups" }
            MenuButton("Create group", "Start a group and invite your friends", navigation::createGroup)
        }
        "account" -> AccountScreen(
            session = signedIn,
            onAccountUpdated = { account -> navigation.updateSession(signedIn.copy(account = account)) },
            onSessionExpired = { navigation.endSession() },
            onLoggedOut = { navigation.endSession("You have been logged out.") },
            onCreateGroup = navigation::createGroup,
        )
        "groups" -> GroupExpensesScreen(signedIn,
            onSessionExpired = { navigation.endSession() },
            onExit = { navigation.page = "home" },
            onNavigate = { scrollState.scrollTo(0) },
            initialGroupId = navigation.groupToOpen,
            startCreating = navigation.createGroupRequested,
            onEntryHandled = { navigation.groupToOpen = null; navigation.createGroupRequested = false },
            onOpenInvitation = { navigation.pastedInvitation = it },
        )
    }
}

@Composable
private fun MenuButton(title: String, subtitle: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
