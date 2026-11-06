package com.example.cpen321application

import java.security.MessageDigest
import java.time.LocalDate

internal object LedgerRequestRules {
    fun validateRate(rate: LedgerRate, requestedDate: String) {
        val requested = LocalDate.parse(requestedDate)
        val validDate = validLedgerDate(rate.date, requested, requested.minusDays(7))
        val validSource = rate.source.isNotBlank() && rate.source.length <= 160
        if (!validDate || !validSource) {
            throw LedgerException("The exchange service returned invalid rate details.")
        }
    }

    fun requireValid(input: LedgerInput) {
        val errors = LedgerDomain.validate(input)
        if (errors.isNotEmpty()) throw LedgerException(errors.values.joinToString(" "))
    }

    fun validateRequestId(requestId: String) {
        if (!Regex("^[A-Za-z0-9_-]{16,80}$").matches(requestId)) {
            throw LedgerException("Invalid save identifier. Reopen the form and retry.")
        }
    }

    fun validateFileName(fileName: String) {
        val forbiddenCharacter = fileName.any { it.code < 32 || it in "/\\" }
        if (fileName.isBlank() || fileName.length > 180 || forbiddenCharacter) {
            throw LedgerException("Choose a file with a valid name of at most 180 characters.")
        }
    }

    fun nextRevision(revision: Int): Int = try { Math.addExact(revision, 1) }
        catch (_: ArithmeticException) { throw LedgerException("This transaction cannot be edited further.") }

    fun inputFingerprint(input: LedgerInput) = fingerprint(listOf(input.kind, input.amount, input.currency,
        input.date, input.merchant, input.category, input.notes))

    fun fingerprint(parts: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

}

/** Strict ISO dates; business callers choose their upper and optional lower bounds. */
internal fun validLedgerDate(value: String, through: LocalDate, from: LocalDate = LocalDate.of(1999, 1, 4)): Boolean {
    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) return false
    val date = try { LocalDate.parse(value) } catch (_: java.time.DateTimeException) { return false }
    return date >= maxOf(from, LocalDate.of(1999, 1, 4)) && date <= through
}
