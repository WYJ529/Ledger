package com.example.cpen321application

import java.util.Locale

internal const val MAX_BILL_CENTS = 100_000_000L
internal data class BillShare(val userId: String, val amountCents: Long, val percentageBasisPoints: Int? = null,
                              val adjustmentCents: Long? = null)
internal data class MealMember(val userId: String, val subtotalCents: Long, val extraTaxRate: String = "0")
internal data class MealCalculation(val tipRate: String, val members: List<MealMember>)
internal data class MealTotals(val subtotal: Long, val tax: Long, val tip: Long, val shares: List<BillShare>) {
    val total get() = subtotal + tax + tip
}
internal fun calculateMeal(input: MealCalculation): MealTotals? {
    if (input.members.isEmpty() || input.members.size > 100 || input.members.map { it.userId }.distinct().size != input.members.size) return null
    var subtotal = 0L; var tax = 0L; var tip = 0L
    val shares = input.members.sortedBy { it.userId }.map { member ->
        val gst = tipCents(member.subtotalCents, "5") ?: return null
        val extra = tipCents(member.subtotalCents, member.extraTaxRate) ?: return null
        val gratuity = tipCents(member.subtotalCents, input.tipRate) ?: return null
        subtotal += member.subtotalCents; tax += gst + extra; tip += gratuity
        BillShare(member.userId, member.subtotalCents + gst + extra + gratuity)
    }
    return MealTotals(subtotal, tax, tip, shares).takeIf { it.subtotal > 0 && it.total <= MAX_BILL_CENTS }
}

/** Percentage text to hundredths of a percent; round the resulting tip half up to cents. */
internal fun tipCents(subtotalCents: Long, percent: String): Long? {
    val basisPoints = moneyCents(percent, allowZero = true) ?: return null
    if (basisPoints > 10_000 || subtotalCents !in 0..MAX_BILL_CENTS) return null
    return (subtotalCents * basisPoints + 5_000) / 10_000
}

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

/** Signed adjustment such as "8", "+8" or "-2.50" in cents; blank means none. Null when unreadable. */
internal fun adjustmentCents(text: String): Long? {
    val trimmed = text.trim().replace('−', '-')
    if (trimmed.isEmpty()) return 0L
    if (!Regex("[+-]?[0-9]{1,7}(\\.[0-9]{1,2})?").matches(trimmed)) return null
    val cents = moneyCents(trimmed.trimStart('+', '-'), allowZero = true) ?: return null
    return if (trimmed.startsWith("-")) -cents else cents
}

/** Text for an edit form: blank for no adjustment, "8.00" or "-2.00" otherwise. */
internal fun adjustmentText(cents: Long): String = when {
    cents == 0L -> ""
    cents < 0 -> "-" + decimalAmount(-cents)
    else -> decimalAmount(cents)
}

/**
 * Mirrors the backend "adjusted" split: set the adjustments aside, split the rest equally (extra cents by
 * ascending member ID), then add each adjustment back. Null when the adjustments exceed the total or a
 * deduction would make a share negative.
 */
internal fun adjustedBillShares(totalCents: Long, adjustments: List<Pair<String, Long>>): List<BillShare>? {
    require(totalCents in 1..MAX_BILL_CENTS)
    require(adjustments.isNotEmpty() && adjustments.size <= 100)
    require(adjustments.map { it.first }.distinct().size == adjustments.size)
    val sorted = adjustments.sortedBy { it.first }
    val pool = totalCents - sorted.sumOf { it.second }
    if (pool < 0) return null
    val base = pool / sorted.size
    val remainder = pool % sorted.size
    val shares = sorted.mapIndexed { index, (id, adjustment) ->
        BillShare(id, base + (if (index < remainder) 1 else 0) + adjustment, adjustmentCents = adjustment)
    }
    return shares.takeIf { it.all { share -> share.amountCents >= 0 } }
}

internal fun decimalAmount(cents: Long): String = String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100)
internal fun displayMoney(currency: String, cents: Long): String = "$currency ${decimalAmount(cents)}"
