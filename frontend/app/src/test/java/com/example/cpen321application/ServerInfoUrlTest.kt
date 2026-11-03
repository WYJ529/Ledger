package com.example.cpen321application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerInfoUrlTest {
    @Test fun endpointsKeepTheConfiguredHostAndPort() {
        for (endpoint in ServerInfoEndpoint.entries) {
            val url = serverInfoUrl("https://ledger.example:8443/", endpoint)
            assertEquals("https", url.protocol)
            assertEquals("ledger.example", url.host)
            assertEquals(8443, url.port)
            assertEquals(endpoint.path, url.path)
        }
        assertEquals("http://10.0.2.2:3000/api/server/ip",
            serverInfoUrl("http://10.0.2.2:3000", ServerInfoEndpoint.IP).toString())
    }

    @Test fun rejectsCredentialsAndNonOriginConfiguration() {
        for (base in listOf("file:///tmp/ledger", "https://user@ledger.example", "https://ledger.example/api",
            "https://ledger.example/?next=evil", "https://ledger.example/#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { serverInfoUrl(base, ServerInfoEndpoint.IP) }
        }
    }
}
