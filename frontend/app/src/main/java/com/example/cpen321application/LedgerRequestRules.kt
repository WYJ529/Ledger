package com.example.cpen321application

import java.security.MessageDigest
import java.time.LocalDate

internal object LedgerRequestRules {
    fun validateRate(rate: LedgerRate, requestedDate: String) {
        val actual = try { LocalDate.parse(rate.date) } catch (_: java.time.format.DateTimeParseException) { throw LedgerException("The exchange service returned an invalid rate date.") }
        val requested = LocalDate.parse(requestedDate)
        val validDate = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(rate.date) &&
            actual in requested.minusDays(7)..requested && actual >= LocalDate.of(1999, 1, 4)
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
        if (!Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(requestId)) {
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
