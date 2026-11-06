package com.example.cpen321application.integration

import com.example.cpen321application.*
import com.example.cpen321application.helpers.withHttpsServer
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerRecognitionAuthTest {
    @Test fun guestRecognitionRequiresSignInBeforeAnyNetworkRequest() {
        val remote = LedgerHttpRemote("https://localhost:1")
        val error = assertThrows(LedgerException::class.java) {
            remote.extract("receipt.png", "image/png", byteArrayOf(1), "CAD")
        }
        assertEquals("Sign in to use AI document recognition.", error.message)
    }

    @Test fun recognitionUsesTheCurrentSessionTokenOnEveryRequest() = withHttpsServer { server ->
        repeat(2) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"candidates":[],"warnings":["Synthetic test document"]}"""))
        }
        var token = "first-test-session"
        val remote = LedgerHttpRemote(server.url("/").toString()) { token }
        repeat(2) { index ->
            token = if (index == 0) "first-test-session" else "second-test-session"
            assertThrows(LedgerException::class.java) {
                remote.extract("receipt.png", "image/png", byteArrayOf(1), "CAD")
            }
            val received = server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("No HTTPS request received")
            assertNotNull(received.handshake)
            assertEquals("Bearer $token", received.getHeader("Authorization"))
        }
    }
}
