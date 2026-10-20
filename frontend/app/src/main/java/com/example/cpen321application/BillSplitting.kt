package com.example.cpen321application

import java.util.Locale

internal const val MAX_BILL_CENTS = 100_000_000L
internal data class BillShare(val userId: String, val amountCents: Long)

/** Decimal text to cents, without converting through floating point. */
internal fun moneyCents(text: String, allowZero: Boolean = false): Long? {
    if (!Regex("[0-9]{1,7}(\\.[0-9]{1,2})?").matches(text)) return null
    val parts = text.split('.')
    val cents = parts[0].toLong() * 100 + parts.getOrElse(1) { "" }.padEnd(2, '0').toLong()
    return cents.takeIf { it in (if (allowZero) 0L else 1L)..MAX_BILL_CENTS }
}

internal fun equalBillShares(totalCents: Long, memberIds: List<String>): List<BillShare> {
    require(totalCents in 1..MAX_BILL_CENTS)
    require(memberIds.isNotEmpty() && memberIds.size <= 100 && memberIds.distinct().size == memberIds.size)
    val base = totalCents / memberIds.size
    val remainder = totalCents % memberIds.size
    return memberIds.sorted().mapIndexed { index, id -> BillShare(id, base + if (index < remainder) 1 else 0) }
}

internal fun decimalAmount(cents: Long): String = String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100)
internal fun displayMoney(currency: String, cents: Long): String = "$currency ${decimalAmount(cents)}"
