package com.example.cpen321application

import java.math.BigDecimal

/** Personal and group amounts use the same limit and text rules. */
internal object Money {
    const val MAX_MINOR = 1_000_000_000_000L

    fun digits(currency: String): Int = if (currency == "JPY" || currency == "KRW") 0 else 2

    fun parse(text: String, allowZero: Boolean = false, digits: Int = 2): Long? {
        val value = text.trim()
        val pattern = if (digits == 0) Regex("[0-9]+") else Regex("[0-9]+(\\.[0-9]{1,2})?")
        if (value.length > 32 || !pattern.matches(value)) return null
        val minor = try { BigDecimal(value).movePointRight(digits).longValueExact() }
        catch (_: ArithmeticException) { return null }
        return minor.takeIf { it in (if (allowZero) 0L else 1L)..MAX_MINOR }
    }

    fun requireMinor(amount: String, currency: String): Long {
        if (currency !in LedgerDomain.currencies) throw LedgerException("Invalid amount or currency.")
        return parse(amount, digits = digits(currency)) ?: throw LedgerException("Invalid amount or currency.")
    }

    fun optional(text: String, digits: Int): Long? = if (text.isBlank()) 0L else parse(text, true, digits)

    fun format(minor: Long, digits: Int = 2): String =
        BigDecimal.valueOf(minor, digits).setScale(digits).toPlainString()

    fun format(minor: Long, currency: String): String = format(minor, digits(currency))

    fun display(minor: Long, currency: String): String = "$currency ${format(minor, currency)}"
}
