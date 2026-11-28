package com.example.cpen321application.e2e

import android.util.Log
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.MainActivity
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.After
import org.junit.Before

internal data class TestAccount(
    val name: String = "fs" + UUID.randomUUID().toString().take(8),
    val password: String = UUID.randomUUID().toString(),
)

internal abstract class FullStackE2eTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    protected val ui = FullStackUi(compose)
    protected val owner = TestAccount()
    protected val member = TestAccount()

    @Before fun requireFullStack() {
        assumeTrue("Start the isolated backend and enable ledgerFullStack",
            InstrumentationRegistry.getArguments().getString("ledgerFullStack") == "true")
    }

    private var usedAccount = false

    @After fun signOutTestAccount() {
        if (usedAccount) ui.openGuestAccount()
    }

    protected fun createGroupWithMember(group: String) {
        usedAccount = true
        ui.register(owner)
        ui.openGroupsTab()
        ui.button("Create group").performClick()
        ui.waitFor("Group name *")
        compose.onNodeWithText("Group name *").performTextInput(group)
        ui.button("Create group").performClick()
        ui.waitFor("Share this invitation")
        val link = compose.onNode(hasText("Invitation link"))
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        ui.button("Done").performClick()
        ui.waitFor("Group created. You are the owner.")

        ui.register(member)
        ui.openGroupsTab()
        ui.button("Join group").performClick()
        compose.onNodeWithText("Invitation link").performTextInput(link)
        ui.button("Continue").performClick()
        ui.waitFor("invites you to $group")
        ui.button("Accept").performClick()
        ui.waitFor("· 2 members")

        ui.signIn(owner.name, owner.password)
        ui.openGroup(group)
        ui.waitFor("· 2 members")
    }
}

/** UI steps shared by the full-stack tests. All text is the English UI. */
internal class FullStackUi(private val compose: ComposeTestRule) {
    // On timeout the visible tree is logged under the "FullStackE2e" tag to show where the app stopped.
    fun waitFor(text: String, timeoutMs: Long = 20000) {
        try {
            compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::logText)
            throw timeout
        }
    }

    private fun logText(node: SemanticsNode) {
        node.config.getOrNull(SemanticsProperties.Text)?.let { Log.d("FullStackE2e", it.joinToString()) }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { Log.d("FullStackE2e", "[${it.joinToString()}]") }
        node.children.forEach(::logText)
    }

    // The last matching clickable node, scrolled into view when it sits in a scrolling page (dialogs do not scroll).
    fun button(text: String): SemanticsNodeInteraction {
        val node = compose.onAllNodes(hasText(text) and hasClickAction()).onLast()
        try { node.performScrollTo() } catch (_: AssertionError) { node.assertExists() }
        return node
    }

    // Starts from a signed-out app so the test always signs in through the real form.
    fun openGuestAccount() {
        showMeUntil { shows("Log out") || hasTag("guest-sign-in") }
        if (shows("Log out")) {
            button("Log out").performClick()
            waitFor("Log out?")
            compose.onNode(hasText("Log out") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(20000) { !shows("Log out?") }
        }
        showMeUntil { hasTag("guest-sign-in") }
    }

    fun register(account: TestAccount) {
        openGuestAccount()
        compose.onNodeWithTag("guest-sign-up").performClick()
        waitFor("Create your account")
        compose.onNodeWithText("Username *").performTextInput(account.name)
        compose.onNodeWithText("Password *").performTextInput(account.password)
        compose.onNodeWithText("Confirm password *").performTextInput(account.password)
        button("Create account").performClick()
        compose.waitUntil(20_000) { !shows("Create your account") }
        showMeUntil { shows("Log out") }
    }

    fun signIn(name: String, password: String) {
        openGuestAccount()
        compose.onNodeWithTag("guest-sign-in").performClick()
        waitFor("Welcome back")
        compose.onNodeWithText("Username *").performTextInput(name)
        compose.onNodeWithText("Password *").performTextInput(password)
        button("Sign in").performClick()
        // A successful sign-in returns to the main sections; Me then offers Log out.
        compose.waitUntil(20000) { !shows("Welcome back") }
        showMeUntil { shows("Log out") }
    }

    fun openGroupsTab() {
        compose.onNodeWithTag("section-Groups").performClick()
        waitFor("Join group")
    }

    fun openGroup(name: String) {
        openGroupsTab()
        waitFor(name)
        button(name).performClick()
        waitFor("+ Add a bill")
    }

    // Group page -> shared Add a bill entry -> the manual bill form.
    fun openManualBill(description: String, total: String) {
        button("+ Add a bill").performClick()
        waitFor("Enter manually")
        button("Enter manually").performClick()
        waitFor("Final total (CAD)")
        compose.onNodeWithText("Description").performScrollTo().performTextInput(description)
        compose.onNodeWithText("Final total (CAD)").performScrollTo().performTextInput(total)
    }

    // Reopens Me until the expected account state is shown; signing in or out navigates asynchronously.
    private fun showMeUntil(ready: () -> Boolean) {
        repeat(30) {
            if (ready()) return
            compose.onNodeWithTag("section-Me").performClick()
            compose.waitForIdle()
            Thread.sleep(500)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::logText)
        throw AssertionError("Me did not reach the expected sign-in state")
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun hasTag(tag: String) = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
