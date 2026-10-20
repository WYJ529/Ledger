package com.example.cpen321application

import java.util.Locale

// Mirrors backend/src/validation.ts so most mistakes are caught before a request is sent.
internal const val PASSWORD_MIN_LENGTH = 8
internal const val PASSWORD_MAX_LENGTH = 128
internal const val DISPLAY_NAME_MAX_LENGTH = 60

private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

internal fun normalizeEmail(input: String): String = input.trim().lowercase(Locale.ROOT)

internal fun normalizeDisplayName(input: String): String = input.trim().replace(Regex("\\s+"), " ")

internal fun emailError(input: String): String? {
    val email = normalizeEmail(input)
    return when {
        email.isEmpty() -> "Enter your email."
        email.length > 254 || !EMAIL_PATTERN.matches(email) -> "Enter a valid email address."
        else -> null
    }
}

internal fun passwordError(input: String): String? = when {
    input.length < PASSWORD_MIN_LENGTH -> "Use at least $PASSWORD_MIN_LENGTH characters."
    input.length > PASSWORD_MAX_LENGTH -> "Use at most $PASSWORD_MAX_LENGTH characters."
    else -> null
}

internal fun confirmPasswordError(password: String, confirmation: String): String? =
    if (password != confirmation) "Passwords do not match." else null

internal fun displayNameError(input: String): String? {
    val name = normalizeDisplayName(input)
    return when {
        name.isEmpty() -> "Enter your name."
        name.length > DISPLAY_NAME_MAX_LENGTH -> "Use at most $DISPLAY_NAME_MAX_LENGTH characters."
        name.any { it.isISOControl() } -> "Remove unsupported characters."
        else -> null
    }
}
