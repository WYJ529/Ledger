package com.example.cpen321application

import java.util.Locale

// Mirrors backend/src/validation.ts so most mistakes are caught before a request is sent.
internal const val PASSWORD_MIN_LENGTH = 8
internal const val PASSWORD_MAX_LENGTH = 128
internal const val DISPLAY_NAME_MAX_LENGTH = 60

internal fun verificationCodeError(code: String): String? =
    if (Regex("^[0-9]{6}$").matches(code)) null else "Enter the 6-digit code from your email."

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
    input.length < PASSWORD_MIN_LENGTH -> tf("Use at least %1\$d characters.", PASSWORD_MIN_LENGTH)
    input.length > PASSWORD_MAX_LENGTH -> tf("Use at most %1\$d characters.", PASSWORD_MAX_LENGTH)
    else -> null
}

internal fun confirmPasswordError(password: String, confirmation: String): String? =
    if (password != confirmation) "Passwords do not match." else null

internal fun displayNameError(input: String): String? {
    val name = normalizeDisplayName(input)
    return when {
        name.isEmpty() -> "Enter your name."
        name.length > DISPLAY_NAME_MAX_LENGTH -> tf("Use at most %1\$d characters.", DISPLAY_NAME_MAX_LENGTH)
        name.any { it.isISOControl() } -> "Remove unsupported characters."
        else -> null
    }
}

internal fun phoneNumberError(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) return null
    val digits = value.count { it in '0'..'9' }
    return if (value.length > 32 || !Regex("^\\+?[0-9 ()-]+$").matches(value) || digits !in 7..15)
        "Use 7–15 digits, including the country code." else null
}
