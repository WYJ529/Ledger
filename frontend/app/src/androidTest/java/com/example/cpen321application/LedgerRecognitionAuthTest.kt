package com.example.cpen321application

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerRecognitionAuthTest {
    @Test fun guestRecognitionRequiresSignInBeforeAnyNetworkRequest() {
        val remote = LedgerHttpRemote("http://127.0.0.1:1")
        val error = assertThrows(LedgerException::class.java) {
            remote.extract("receipt.png", "image/png", byteArrayOf(1), "CAD")
        }
        assertEquals("Sign in to use AI document recognition.", error.message)
    }

    @Test fun recognitionUsesTheCurrentSessionTokenOnEveryRequest() {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10_000
        val executor = Executors.newSingleThreadExecutor()
        val received = executor.submit<List<String>> {
            (0..1).map {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    repeat(length) { check(reader.read() != -1) }
                    val response = """{"candidates":[],"warnings":["Synthetic test document"]}"""
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${response.length}\r\nConnection: close\r\n\r\n" + response).toByteArray())
                    headers.first { it.startsWith("Authorization:", true) }.substringAfter(':').trim()
                }
            }
        }
        try {
            var token = "first-test-session"
            val remote = LedgerHttpRemote("http://127.0.0.1:${server.localPort}") { token }
            repeat(2) { index ->
                token = if (index == 0) "first-test-session" else "second-test-session"
                assertThrows(LedgerException::class.java) {
                    remote.extract("receipt.png", "image/png", byteArrayOf(1), "CAD")
                }
            }
            assertEquals(listOf("Bearer first-test-session", "Bearer second-test-session"), received.get(15, TimeUnit.SECONDS))
        } finally { server.close(); executor.shutdownNow() }
    }
}
