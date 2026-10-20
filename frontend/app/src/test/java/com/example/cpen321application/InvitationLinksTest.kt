package com.example.cpen321application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvitationLinksTest {
    private val token = "a".repeat(42) + "_"

    @Test fun readsSupportedInvitationFormats() {
        listOf(token, "ledger://invite/$token", "https://ledger.example/invite/$token",
            "http://10.0.2.2:3000/invite/$token", "  ledger://invite/$token  ").forEach {
            assertEquals(token, invitationTokenFromLink(it))
        }
    }

    @Test fun rejectsMalformedLinksRatherThanOpeningAnotherRoute() {
        listOf("", "a".repeat(42), "ledger://other/$token", "ledger://invite:80/$token",
            "https://example.com/other/$token", "https://example.com/invite/$token/extra",
            "ledger://invite/$token?redirect=evil", "ledger://invite/$token#extra",
            "https://user@example.com/invite/$token", "javascript:alert(1)",
            "ledger://invite/%61${token.drop(1)}", "https:///invite/$token").forEach {
            assertNull(it, invitationTokenFromLink(it))
        }
    }
}
