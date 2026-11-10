package com.example.cpen321application.helpers

import java.net.InetAddress
import java.security.Security
import javax.net.ssl.HttpsURLConnection
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.bouncycastle.jce.provider.BouncyCastleProvider

internal fun withHttpsServer(block: (MockWebServer) -> Unit) {
    val certificate = localhostCertificate()
    val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build()
    MockWebServer().use { server ->
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val originalFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            // Trust only the fixture certificate; hostname verification stays enabled.
            HttpsURLConnection.setDefaultSSLSocketFactory(clientCertificates.sslSocketFactory())
            block(server)
        } finally { HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory) }
    }
}

private fun localhostCertificate(): HeldCertificate {
    // Android's stripped BC provider cannot sign the fixture's certificate.
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
