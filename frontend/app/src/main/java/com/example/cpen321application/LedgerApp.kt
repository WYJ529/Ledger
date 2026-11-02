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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AppSection(val title: String, val icon: Int) {
    Expenses("My Expenses", R.drawable.ic_expenses),
    Groups("Group", R.drawable.ic_groups),
    Me("Me", R.drawable.ic_person),
}

internal class AccountNavigation(
    private val storage: SessionStorage,
    sectionState: MutableState<AppSection>,
    invitationState: MutableState<String?>,
    groupState: MutableState<String?>,
) {
    var section by sectionState
    var pastedInvitation by invitationState
    var groupToOpen by groupState
    var session by mutableStateOf(storage.load())
    var authNotice by mutableStateOf<String?>(null)
    /** Set by an explicit sign-in (not a restored session) so cloud saves can offer to save or load once. */
    var cloudSaveOfferPending by mutableStateOf(false)

    fun signIn(value: Session) {
        updateSession(value)
        authNotice = null
        cloudSaveOfferPending = true
        section = AppSection.Expenses
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
    }
}

@Composable
private fun rememberAccountNavigation(): AccountNavigation {
    val context = LocalContext.current
    val storage = remember(context) { SessionStorage(context) }
    val section = rememberSaveable { mutableStateOf(AppSection.Expenses) }
    val invitation = rememberSaveable { mutableStateOf<String?>(null) }
    val group = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(storage) { AccountNavigation(storage, section, invitation, group) }
}

@Composable
internal fun LedgerApp(
    ledger: LedgerViewModel, incomingInvitation: String?, onInvitationHandled: () -> Unit,
    navigation: AccountNavigation = rememberAccountNavigation(),
) {
    val pendingInvitation = incomingInvitation ?: navigation.pastedInvitation
    val cloudSaves = remember(ledger) { CloudSaves(ledger.localLedger, LedgerSavesApi, cloudSaveDeviceName()) }
    cloudSaves.onSessionExpired = { navigation.endSession() }
    val scrollState = rememberScrollState()
    var groupHomeRevision by rememberSaveable { mutableIntStateOf(0) }
    val clearInvitation = {
        navigation.pastedInvitation = null
        onInvitationHandled()
    }
    val openExpenses = {
        clearInvitation()
        navigation.section = AppSection.Expenses
    }
    RefreshSavedSession(navigation)
    CloudSaveSession(navigation, cloudSaves, localCount = ledger.records.size)
    BackHandler(pendingInvitation != null || navigation.section != AppSection.Expenses, onBack = openExpenses)
    LaunchedEffect(navigation.section, pendingInvitation) {
        scrollState.scrollTo(0)
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AppNavigationBar(
                selected = if (pendingInvitation != null) AppSection.Groups else navigation.section,
                enabled = !ledger.busy || navigation.section != AppSection.Expenses,
            ) { section ->
                run {
                    clearInvitation()
                    ledger.selection.cancelSelection()
                    if (section == AppSection.Groups) {
                        navigation.groupToOpen = null
                        groupHomeRevision++
                    }
                    if (section == AppSection.Expenses && !ledger.busy) ledger.returnToDashboard()
                    if (section == AppSection.Me) groupHomeRevision++
                    navigation.section = section
                }
            }
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            if (navigation.section == AppSection.Expenses && pendingInvitation == null) {
                LedgerScreen(ledger, navigation.session, onSessionExpired = { navigation.endSession() })
            } else {
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(scrollState).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Reselecting Group starts at the group list, including from a nested form.
                    key(groupHomeRevision) {
                        AccountContent(navigation, pendingInvitation, scrollState, openExpenses, clearInvitation, ledger, cloudSaves)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavigationBar(selected: AppSection, enabled: Boolean, onSelect: (AppSection) -> Unit) {
    NavigationBar {
        AppSection.entries.forEach { section ->
            NavigationBarItem(
                selected = section == selected,
                enabled = enabled,
                onClick = { onSelect(section) },
                icon = { Icon(painterResource(section.icon), contentDescription = null) },
                label = { Text(t(section.title)) },
                modifier = Modifier.testTag("section-${section.name}"),
            )
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
                // A profile save or logout may have completed while this refresh was in flight.
                if (navigation.session == current) navigation.updateSession(current.copy(account = account))
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
private fun CloudSaveSession(navigation: AccountNavigation, cloudSaves: CloudSaves, localCount: Int) {
    val session = navigation.session
    // Another account must never see the previous account's slots or an unfinished flow.
    LaunchedEffect(session?.account?.id) { cloudSaves.reset() }
    // Keyed on the token alone: clearing the flag must not cancel the offer that is being prepared.
    LaunchedEffect(session?.token) {
        if (session != null && navigation.cloudSaveOfferPending) {
            navigation.cloudSaveOfferPending = false
            cloudSaves.offerAfterSignIn(session.token, localCount)
        }
    }
    CloudSaveDialogs(cloudSaves, session, localCount)
}

@Composable
private fun AccountContent(
    navigation: AccountNavigation, pendingInvitation: String?, scrollState: ScrollState,
    openExpenses: () -> Unit, clearInvitation: () -> Unit, ledger: LedgerViewModel, cloudSaves: CloudSaves,
) {
    val signedIn = navigation.session
    when {
        signedIn == null && pendingInvitation == null && navigation.section == AppSection.Me ->
            GuestAccountScreen(notice = navigation.authNotice, onSignedIn = navigation::signIn, onNavigate = { scrollState.scrollTo(0) })
        signedIn == null -> {
            if (pendingInvitation != null) Text(t("Sign in or create an account to view your group invitation."))
            SignInPage(notice = navigation.authNotice, onSignedIn = navigation::signIn, onBack = openExpenses)
        }
        pendingInvitation != null -> {
            key(pendingInvitation) {
                InvitationScreen(signedIn, pendingInvitation,
                    onAccepted = { groupId ->
                        navigation.groupToOpen = groupId
                        navigation.section = AppSection.Groups
                        clearInvitation()
                    },
                    onRejected = openExpenses,
                    onSessionExpired = { navigation.endSession() })
            }
        }
        else -> SignedInPages(navigation, signedIn, scrollState, ledger, cloudSaves)
    }
}

@Composable
private fun SignedInPages(navigation: AccountNavigation, signedIn: Session, scrollState: ScrollState, ledger: LedgerViewModel, cloudSaves: CloudSaves) {
    when (navigation.section) {
        AppSection.Expenses -> Unit // Rendered by LedgerScreen outside the scrollable account content.
        AppSection.Me -> AccountScreen(
            session = signedIn,
            onNavigate = { scrollState.scrollTo(0) },
            onAccountUpdated = { account -> navigation.updateSession(signedIn.copy(account = account)) },
            onSessionExpired = { navigation.endSession() },
            onLoggedOut = {
                navigation.endSession("You have been logged out.")
                navigation.section = AppSection.Expenses
            },
            cloudSaves = cloudSaves,
            localRecordCount = ledger.records.size,
        )
        AppSection.Groups -> GroupExpensesScreen(signedIn,
            onLedgerChanged = { ledger.refresh() },
            onSessionExpired = { navigation.endSession() },
            onExit = { navigation.section = AppSection.Expenses },
            onNavigate = { scrollState.scrollTo(0) },
            initialGroupId = navigation.groupToOpen,
            onEntryHandled = { navigation.groupToOpen = null },
            onOpenInvitation = { navigation.pastedInvitation = it },
        )
    }
}
