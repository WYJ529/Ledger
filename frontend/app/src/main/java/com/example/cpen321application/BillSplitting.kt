package com.example.cpen321application

import java.util.Locale

internal const val MAX_BILL_CENTS = 100_000_000L
internal data class BillShare(val userId: String, val amountCents: Long, val percentageBasisPoints: Int? = null)

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

/** Percentages have at most two decimals; 10,000 basis points represent 100%. */
internal fun percentageBasisPoints(text: String): Int? {
    if (!Regex("[0-9]{1,3}(\\.[0-9]{1,2})?").matches(text)) return null
    val parts = text.split('.')
    val value = parts[0].toInt() * 100 + parts.getOrElse(1) { "" }.padEnd(2, '0').toInt()
    return value.takeIf { it in 0..10000 }
}

internal fun percentageBillShares(totalCents: Long, percentages: List<Pair<String, Int>>): List<BillShare> {
    require(totalCents in 1..MAX_BILL_CENTS)
    require(percentages.isNotEmpty() && percentages.size <= 100)
    require(percentages.map { it.first }.distinct().size == percentages.size)
    require(percentages.all { it.second in 0..10000 } && percentages.sumOf { it.second } == 10000)
    val sorted = percentages.sortedBy { it.first }
    val shares = sorted.map { (id, basisPoints) -> BillShare(id, totalCents * basisPoints / 10000, basisPoints) }
    val remaining = (totalCents - shares.sumOf { it.amountCents }).toInt()
    // Match the backend: largest remainder first, then ascending member ID for ties.
    val extraCentIds = sorted.sortedWith(compareByDescending<Pair<String, Int>> { totalCents * it.second % 10000 }
        .thenBy { it.first }).take(remaining).map { it.first }.toSet()
    return shares.map { it.copy(amountCents = it.amountCents + if (it.userId in extraCentIds) 1 else 0) }
}

internal fun percentageText(basisPoints: Int): String = decimalAmount(basisPoints.toLong())

internal fun decimalAmount(cents: Long): String = String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100)
internal fun displayMoney(currency: String, cents: Long): String = "$currency ${decimalAmount(cents)}"
