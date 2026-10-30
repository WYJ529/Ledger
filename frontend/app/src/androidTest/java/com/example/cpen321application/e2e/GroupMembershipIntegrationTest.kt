package com.example.cpen321application.e2e

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in end-to-end check: emulator UI and real HTTP joiners share an isolated backend. */
class GroupMembershipIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var base: String
    private lateinit var host: String
    private val accounts = mutableListOf<Session>()
    private var groupId: String? = null

    private fun allowedApiPath(method: String, path: String): Boolean = when (method) {
        "GET" -> Regex("/api/groups/[a-fA-F0-9]{24}/summary").matches(path)
        "DELETE" -> Regex("/api/groups/[a-fA-F0-9]{24}").matches(path)
        "POST" -> path in setOf("/api/auth/register", "/api/auth/logout", "/api/groups") ||
            Regex("/api/groups/invitations/[A-Za-z0-9_-]{43}/accept").matches(path)
        else -> false
    }

    private fun api(method: String, path: String, expected: Int, session: Session? = null, body: JSONObject? = null): JSONObject {
        require(allowedApiPath(method, path)) { "Unsupported isolated test API path" }
        val connection = URL("http", host, 3101, path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            session?.let { connection.setRequestProperty("Authorization", "Bearer ${it.token}") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            assertEquals("Unexpected API status", expected, connection.responseCode)
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun account(name: String): Session {
        val json = api("POST", "/api/auth/register", 201, body = JSONObject()
            .put("email", "membership-${UUID.randomUUID()}@example.com")
            .put("displayName", name).put("password", UUID.randomUUID().toString()))
        return Session(json.getString("token"), json.getLong("expiresAt"), parseAccount(json.getJSONObject("user"))).also { accounts += it }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun showGroup(owner: Session, id: String) {
        val reader = object : GroupExpenseReader {
            override fun groups(token: String) = emptyList<ExpenseGroup>()
            override fun summary(token: String, groupId: String) =
                GroupExpenseApi.parseSummary(api("GET", "/api/groups/$groupId/summary", 200, owner))
        }
        val connection: @Composable (String, String, SplitLiveCallbacks) -> Unit = { token, group, callbacks ->
            GroupSplitLiveConnection(token, group, callbacks, base)
        }
        compose.setContent { MaterialTheme { Scaffold { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                GroupExpensesScreen(owner, {}, {}, reader = reader, initialGroupId = id, liveConnection = connection)
            }
        } } }
    }

    @Test fun realInvitationJoinUpdatesOpenDialogAndAnUnfinishedPercentageBill() {
        val target = InstrumentationRegistry.getArguments().getString("membershipServer")
        assumeTrue("Start the isolated membership test backend and pass membershipServer", target != null)
        when (target) {
            "http://10.0.2.2:3101" -> { base = "http://10.0.2.2:3101"; host = "10.0.2.2" }
            "http://127.0.0.1:3101" -> { base = "http://127.0.0.1:3101"; host = "127.0.0.1" }
            else -> error("Use the isolated test server on localhost port 3101")
        }
        try {
            val owner = account("Live owner")
            val member = account("First joiner")
            val newcomer = account("Second joiner")
            val created = api("POST", "/api/groups", 201, owner, JSONObject()
                .put("name", "Membership acceptance").put("currency", "CAD").put("requestId", UUID.randomUUID().toString()))
            val id = created.getJSONObject("group").getString("id")
            groupId = id
            val invitation = created.getJSONObject("invitation").getString("deepLink").substringAfterLast('/')
            showGroup(owner, id)
            waitFor("Live · member updates")
            waitFor("Members (1)")
            compose.onNodeWithText("Members (1)").performScrollTo().performClick()
            api("POST", "/api/groups/invitations/$invitation/accept", 200, member)
            waitFor("First joiner")
            compose.onNodeWithText("Done").performClick()
            waitFor("Members (2)")
            compose.onNodeWithText("Add expense").performScrollTo().performClick()
            compose.onNodeWithText("Description").performTextInput("Live draft preserved")
            compose.onNodeWithText("Final total (CAD)").performTextInput("18.00")
            compose.onNodeWithText("Percentage", substring = false).performClick()
            compose.onAllNodesWithText("Share (%)")[0].performScrollTo().performTextInput("40")
            compose.onAllNodesWithText("Share (%)")[1].performScrollTo().performTextInput("60")
            compose.onNodeWithContentDescription("Paid by First joiner").performScrollTo().performClick()
            api("POST", "/api/groups/invitations/$invitation/accept", 200, newcomer)
            waitFor("Second joiner")
            compose.onNodeWithContentDescription("Include Second joiner in split").assertIsOff()
            compose.onNodeWithContentDescription("Paid by First joiner").assertIsSelected()
            compose.onNodeWithText("Description").performScrollTo().assertTextContains("Live draft preserved")
            compose.onNodeWithText("Final total (CAD)").performScrollTo().assertTextContains("18.00")
            compose.onAllNodesWithText("Share (%)")[0].performScrollTo().assertTextContains("40")
            compose.onAllNodesWithText("Share (%)")[1].performScrollTo().assertTextContains("60")
            compose.onNodeWithText("Save expense").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("Back to group expenses").performScrollTo().performClick()
            compose.onNodeWithText("Members (3)").assertExists()
        } finally {
            groupId?.let { id -> runCatching { api("DELETE", "/api/groups/$id", 204, accounts.first()) } }
            accounts.forEach { session -> runCatching { api("POST", "/api/auth/logout", 204, session) } }
        }
    }
}
