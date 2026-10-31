package com.example.cpen321application

import org.junit.Assert.*
import org.junit.Test

class ApiErrorMessagesTest {
    @Test fun missingAccountRoutesExplainAnOutdatedOrWrongBackend() {
        listOf(
            "/api/auth/register", "/api/auth/register/code", "/api/auth/login", "/api/auth/google",
            "/api/auth/google/register", "/api/auth/logout", "/api/users/me",
        ).forEach { path ->
            val message = apiErrorMessage(404, path, "Not Found")
            assertTrue(message.contains("server address"))
            assertTrue(message.contains("updated Ledger backend"))
        }
    }

    @Test fun existingValidationAndCredentialErrorsRemainSpecific() {
        assertEquals("Incorrect email or password.",
            apiErrorMessage(401, "/api/auth/login", "Incorrect email or password."))
        assertEquals("Email already registered.",
            apiErrorMessage(409, "/api/auth/register", "Email already registered."))
        assertEquals("Google sign-in is not configured on the backend.",
            apiErrorMessage(503, "/api/auth/google", "Google sign-in is not configured on the backend."))
    }

    @Test fun missingGroupsAndUnknownPathsKeepTheirOwnErrors() {
        assertEquals("Group not found.", apiErrorMessage(404, "/api/groups/missing", "Group not found."))
        assertEquals("Not Found", apiErrorMessage(404, "/api/auth/unknown", "Not Found"))
        assertEquals("Request failed (HTTP 500).", apiErrorMessage(500, "/api/auth/register", ""))
    }
}
