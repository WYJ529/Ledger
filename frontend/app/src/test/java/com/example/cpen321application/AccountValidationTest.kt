package com.example.cpen321application

import org.junit.Assert.*
import org.junit.Test

class AccountValidationTest {
    @Test fun verificationCodesRequireExactlySixAsciiDigitsAndKeepLeadingZeros() {
        listOf("000123", "123456", "000000").forEach { assertNull(verificationCodeError(it)) }
        listOf("", "12345", "1234567", "１２３４５６", "123 45", "abcdef").forEach { assertNotNull(verificationCodeError(it)) }
    }
    @Test fun optionalPhoneNumbersAcceptInternationalFormatting() {
        listOf("", "  ", "+1 (604) 555-0123", "+852 6123 4567", "1234567").forEach { assertNull(it, phoneNumberError(it)) }
        listOf("123", "1234567890123456", "call me", "++85261234567", "+1234567ext1", "+１２３４５６７８").forEach { assertNotNull(it, phoneNumberError(it)) }
    }
    @Test fun emailsAreTrimmedAndLowercased() {
        assertEquals("sam@example.com", normalizeEmail("  Sam@Example.COM "))
        assertNull(emailError(" Sam@Example.com "))
    }

    @Test fun malformedEmailsAreRejected() {
        assertEquals("Enter your email.", emailError("   "))
        listOf("no-at-sign", "a@b", "a b@example.com", "${"a".repeat(250)}@b.co").forEach {
            assertEquals(it, "Enter a valid email address.", emailError(it))
        }
    }

    @Test fun passwordLengthMatchesBackendRules() {
        assertNotNull(passwordError("1234567"))
        assertNull(passwordError("12345678"))
        assertNull(passwordError("x".repeat(PASSWORD_MAX_LENGTH)))
        assertNotNull(passwordError("x".repeat(PASSWORD_MAX_LENGTH + 1)))
    }

    @Test fun confirmationMustMatch() {
        assertNull(confirmPasswordError("password1", "password1"))
        assertEquals("Passwords do not match.", confirmPasswordError("password1", "password2"))
    }

    @Test fun displayNamesAreNormalizedAndLimited() {
        assertEquals("Sam Lee", normalizeDisplayName("  Sam   Lee "))
        assertNull(displayNameError(" Sam  Lee "))
        assertEquals("Enter your name.", displayNameError("   "))
        assertNotNull(displayNameError("x".repeat(DISPLAY_NAME_MAX_LENGTH + 1)))
        assertNotNull(displayNameError("Sam\u0000"))
    }
}
