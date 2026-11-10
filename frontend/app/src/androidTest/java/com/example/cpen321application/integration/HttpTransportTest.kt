package com.example.cpen321application.integration

import com.example.cpen321application.*
import com.example.cpen321application.helpers.withHttpsServer
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HttpTransportTest {
    private val accountPolicy = HttpPolicy(10_000, 15_000)

    @Test fun sendsUtf8JsonAndDoesNotCarryAuthenticationToTheNextRequest() = withHttpsServer { server ->
        repeat(2) { server.enqueue(MockResponse().setBody("{}")) }
        val body = JSONObject().put("name", "测试 café")
        val base = server.url("/").toString()
        HttpTransport.request(HttpTransport.open(base, "/profile"), "PATCH", body, "first-token", accountPolicy)
        val first = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("PATCH", first.method)
        assertEquals("/profile", first.path)
        assertNotNull(first.handshake)
        assertEquals("Bearer first-token", first.getHeader("Authorization"))
        assertEquals("application/json", first.getHeader("Accept"))
        assertEquals("application/json; charset=utf-8", first.getHeader("Content-Type"))
        assertEquals(body.toString().toByteArray(Charsets.UTF_8).size.toString(), first.getHeader("Content-Length"))
        assertEquals(body.toString(), first.body.readUtf8())
        HttpTransport.request(HttpTransport.open(base, "/public"), "GET", null, null, accountPolicy)
        assertNull(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"))
    }

    @Test fun doesNotFollowRedirectsWithCredentials() = withHttpsServer { server ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        server.enqueue(MockResponse().setBody("{}"))
        val response = HttpTransport.request(HttpTransport.open(server.url("/").toString(), "/redirect"),
            "GET", null, "private-token", accountPolicy)
        assertEquals(302, response.status)
        assertFalse(response.successful)
        assertEquals(1, server.requestCount)
    }

    @Test fun enforcesExactSuccessLimitsAndTruncatesErrors() = withHttpsServer { server ->
        val policy = HttpPolicy(1000, 1000, successLimitBytes = 8, errorLimitBytes = 4)
        fun fetch() = HttpTransport.request(HttpTransport.open(server.url("/").toString(), "/bounded"), "GET", null, null, policy)
        server.enqueue(MockResponse().setBody("12345678"))
        assertEquals("12345678", fetch().text)
        server.enqueue(MockResponse().setBody("123456789"))
        assertThrows(ResponseTooLargeException::class.java) { fetch() }
        server.enqueue(MockResponse().setResponseCode(500).setBody("proxy error page"))
        val error = fetch()
        assertEquals("prox", error.text)
        assertEquals(0, error.jsonOrEmpty().length())
    }

    @Test fun accountResponsesCanExceedTheLedgerLimitAndKeepRetryMetadata() = withHttpsServer { server ->
        val payload = "x".repeat(2 * 1024 * 1024 + 1)
        server.enqueue(MockResponse().setBody(JSONObject().put("snapshot", payload).toString()))
        fun fetch() = HttpTransport.request(HttpTransport.open(server.url("/").toString(), "/account"), "GET", null, null, accountPolicy)
        assertEquals(payload.length, fetch().jsonOrEmpty().getString("snapshot").length)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Please wait","retryAfterSeconds":17}"""))
        val error = fetch()
        assertEquals(429, error.status)
        assertEquals(17, error.jsonOrEmpty().getInt("retryAfterSeconds"))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(0, fetch().jsonOrEmpty().length())
    }

    @Test fun ledgerRetainsStrictJsonSizeLimitsAndErrorMessages() = withHttpsServer { server ->
        val remote = LedgerHttpRemote(server.url("/").toString())
        fun failure(): String? = assertThrows(LedgerException::class.java) { remote.quote("USD", "CAD", "2025-01-05") }.message
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Provider quota exhausted"}"""))
        assertEquals("Provider quota exhausted", failure())
        server.enqueue(MockResponse().setResponseCode(504).setBody("<html>proxy failure</html>"))
        assertTrue(failure()!!.contains("took too long"))
        server.enqueue(MockResponse().setBody("<html>not JSON</html>"))
        assertTrue(failure()!!.contains("unreadable response"))
        server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)))
        assertEquals("The service response is too large.", failure())
    }

    @Test fun malformedJsonIsOnlyToleratedByTheExplicitLenientReader() {
        val response = HttpResponse(200, "not JSON")
        assertEquals(0, response.jsonOrEmpty().length())
        assertThrows(JSONException::class.java) { JSONObject(response.text) }
    }

    @Test fun appliesTimeoutsAndDisconnectsEvenWhenTheReadFails() {
        val connection = object : HttpURLConnection(URL("https://localhost/")) {
            var disconnected = false
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected = true }
            override fun getResponseCode() = 200
            override fun getInputStream(): java.io.InputStream = throw SocketTimeoutException("fixture timeout")
        }
        assertThrows(SocketTimeoutException::class.java) {
            HttpTransport.request(connection, "GET", null, null, HttpPolicy(15_000, 180_000))
        }
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(180_000, connection.readTimeout)
        assertTrue(connection.disconnected)
    }

    @Test fun rejectsUnsafeOrMalformedBackendAddressesBeforeConnecting() {
        listOf("ftp://localhost", "https://user:password@localhost", "https:///missing-host", "https://bad address").forEach { base ->
            assertThrows(IllegalArgumentException::class.java) { HttpTransport.open(base, "/api") }
            assertThrows(LedgerException::class.java) { LedgerHttpRemote(base).quote("USD", "CAD", "2025-01-05") }
        }
    }
}
