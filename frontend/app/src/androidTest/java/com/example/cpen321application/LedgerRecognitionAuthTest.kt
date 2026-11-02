package com.example.cpen321application

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.bouncycastle.jce.provider.BouncyCastleProvider
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

    @Test fun recognitionUsesTheCurrentSessionTokenOnEveryRequest() {
        val certificate = localhostCertificate()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            repeat(2) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"candidates":[],"warnings":["Synthetic test document"]}"""))
            }
            val originalFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
            try {
                // Trust only this generated test certificate; hostname verification stays enabled.
                HttpsURLConnection.setDefaultSSLSocketFactory(clientCertificates.sslSocketFactory())
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
            } finally {
                HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory)
            }
        }
    }

    private fun localhostCertificate(): HeldCertificate {
        // Android's stripped BC provider cannot sign the fixture's certificate.
        // Use the test library's full provider only for generation, then restore it.
        val providers = Security.getProviders()
        val original = Security.getProvider("BC")
        val position = providers.indexOf(original).takeIf { it >= 0 }?.plus(1) ?: providers.size + 1
        try {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), position)
            return HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        } finally {
            Security.removeProvider("BC")
            if (original != null) Security.insertProviderAt(original, position)
        }
    }
}
