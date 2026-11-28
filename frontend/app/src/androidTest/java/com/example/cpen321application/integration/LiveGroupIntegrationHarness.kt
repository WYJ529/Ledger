package com.example.cpen321application.integration

import com.example.cpen321application.e2e.FullStackUi

import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import com.example.cpen321application.SessionStorage
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/** A test account on the real backend. Only accounts made by [FullStackBackend.register] have a password. */
internal data class BackendAccount(val id: String, val name: String, val password: String, val token: String)

/**
 * Direct calls to the same Node backend the app under test uses (BuildConfig.API_BASE_URL).
 * Start it with `LEDGER_TEST_PORT=3000 node backend/tests/helpers/membership-test-server.cjs`
 * and pass `-e ledgerFullStack true`; otherwise the full-stack tests are skipped.
 */
internal class FullStackBackend {
    private val http = OkHttpClient.Builder().followRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val accounts = mutableListOf<BackendAccount>()

    companion object {
        // Call from @Before so a run without the backend skips instead of failing.
        fun assumeEnabled() {
            val enabled = InstrumentationRegistry.getArguments().getString("ledgerFullStack") == "true"
            assumeTrue("Start the isolated test backend on port 3000 and pass ledgerFullStack=true", enabled)
        }
    }

    // Registers through the public API so the app can sign in with this username and password.
    // Registration is rate-limited per client, so only the account that signs in to the app uses it.
    fun register(): BackendAccount {
        val username = "fs" + UUID.randomUUID().toString().filter(Char::isDigit).take(10).padEnd(10, '7')
        val password = UUID.randomUUID().toString()
        val body = JSONObject().put("username", username).put("password", password).put("confirmPassword", password)
        val json = call("POST", "/api/auth/register", body = body)
        return BackendAccount(json.getJSONObject("user").getString("id"), username, password, json.getString("token"))
            .also { accounts += it }
    }

    // A session-only account from the isolated test server; it acts through the API as another device.
    fun otherDevice(): BackendAccount {
        val json = call("POST", "/test/accounts", body = JSONObject().put("displayName", "First joiner"))
        return BackendAccount(json.getJSONObject("user").getString("id"), "First joiner", "", json.getString("token"))
            .also { accounts += it }
    }

    fun createGroup(owner: BackendAccount, name: String): String = call("POST", "/api/groups", owner,
        JSONObject().put("name", name).put("currency", "CAD").put("requestId", UUID.randomUUID().toString()))
        .getJSONObject("group").getString("id")

    // The owner invites and the member accepts, exactly as a second device would.
    fun join(owner: BackendAccount, member: BackendAccount, groupId: String) {
        val link = call("POST", "/api/groups/$groupId/invitations", owner).getJSONObject("invitation").getString("deepLink")
        call("POST", "/api/groups/invitations/${link.substringAfterLast('/')}/accept", member)
    }

    fun groupNamed(account: BackendAccount, name: String): String {
        val groups = call("GET", "/api/groups", account).getJSONArray("groups")
        return objects(groups).single { it.getString("name") == name }.getString("id")
    }

    fun summary(account: BackendAccount, groupId: String) = call("GET", "/api/groups/$groupId/summary", account)

    fun snapshot(account: BackendAccount, groupId: String) = call("GET", "/api/groups/$groupId/snapshot", account)

    fun claim(account: BackendAccount, sessionId: String, unitId: String, version: Int) = call("POST",
        "/api/split-sessions/$sessionId/claims", account, JSONObject().put("unitId", unitId).put("action", "claim")
            .put("version", version).put("operationKey", "fullstack-claim-" + UUID.randomUUID()))

    fun close() {
        accounts.forEach { runCatching { call("POST", "/api/auth/logout", it) } }
        http.connectionPool().evictAll()
        http.dispatcher().executorService().shutdown()
    }

    private fun call(method: String, path: String, account: BackendAccount? = null, body: JSONObject? = null): JSONObject {
        val payload = if (method == "POST") RequestBody.create(MediaType.parse("application/json"), body?.toString() ?: "{}") else null
        val request = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + path).method(method, payload)
        account?.let { request.header("Authorization", "Bearer ${it.token}") }
        http.newCall(request.build()).execute().use { response ->
            val text = response.body()?.string().orEmpty()
            assertTrue("$method $path returned ${response.code()}", response.isSuccessful)
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}

internal fun objects(array: JSONArray): List<JSONObject> = List(array.length()) { array.getJSONObject(it) }

/** Base for the full-stack tests: the real app, a backend client and the shared UI steps. */
internal abstract class LiveGroupIntegrationTestBase {
    val compose = createAndroidComposeRule<MainActivity>()
    // Reset only authentication before Activity launch; no previous test's session may leak in.
    private val sessionIsolation = object : ExternalResource() {
        override fun before() {
            FullStackBackend.assumeEnabled()
            SessionStorage(InstrumentationRegistry.getInstrumentation().targetContext).clear()
        }
        override fun after() {
            SessionStorage(InstrumentationRegistry.getInstrumentation().targetContext).clear()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(sessionIsolation).around(compose)
    protected lateinit var backend: FullStackBackend
    protected val ui = FullStackUi(compose)

    @Before fun requireBackend() {
        FullStackBackend.assumeEnabled()
        backend = FullStackBackend()
    }

    @After fun closeSessions() {
        if (::backend.isInitialized) backend.close()
    }

    // The member's view of the group's only bill, with each member's share in cents.
    protected fun onlyBill(member: BackendAccount, groupId: String): Pair<JSONObject, Map<String, Long>> {
        val bill = objects(backend.summary(member, groupId).getJSONArray("expenses")).single()
        return bill to objects(bill.getJSONArray("shares")).associate { it.getString("userId") to it.getLong("amountCents") }
    }
}
