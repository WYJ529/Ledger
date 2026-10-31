package com.example.cpen321application

/** Account routes never use 404 for invalid credentials or an unknown account. */
internal fun apiErrorMessage(status: Int, path: String, serverMessage: String): String {
    val accountRoute = path in setOf(
        "/api/auth/register", "/api/auth/register/code", "/api/auth/login", "/api/auth/google",
        "/api/auth/google/register", "/api/auth/logout", "/api/users/me",
    )
    if (status == 404 && accountRoute) {
        return "Account sign-in and registration are unavailable on this server. Check the server address or restart the updated Ledger backend."
    }
    return serverMessage.ifBlank { tf("Request failed (HTTP %1\$d).", status) }
}
