package com.example.cpen321application

import java.math.BigInteger

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
    return MealTotals(subtotal, tax, tip, shares).takeIf { it.subtotal > 0 && it.total <= Money.MAX_MINOR }
}

/** Percentage text to hundredths of a percent; round the resulting tip half up to cents. */
internal fun tipCents(subtotalCents: Long, percent: String): Long? {
    val basisPoints = percentageBasisPoints(percent) ?: return null
    if (subtotalCents !in 0..Money.MAX_MINOR) return null
    return (subtotalCents * basisPoints + 5_000) / 10_000
}

internal fun equalBillShares(totalCents: Long, memberIds: List<String>): List<BillShare> {
    require(totalCents in 1..Money.MAX_MINOR)
    require(memberIds.isNotEmpty() && memberIds.size <= 100 && memberIds.distinct().size == memberIds.size)
    return allocate(totalCents, memberIds.sorted().associateWith { 1L }).map { (id, amount) -> BillShare(id, amount) }
}

/** Percentages have at most two decimals; 10,000 basis points represent 100%. */
internal fun percentageBasisPoints(text: String): Int? =
    Money.parse(text, allowZero = true)?.takeIf { it <= 10000 }?.toInt()

internal fun percentageBillShares(totalCents: Long, percentages: List<Pair<String, Int>>): List<BillShare> {
    require(totalCents in 1..Money.MAX_MINOR)
    require(percentages.isNotEmpty() && percentages.size <= 100)
    require(percentages.map { it.first }.distinct().size == percentages.size)
    require(percentages.all { it.second in 0..10000 } && percentages.sumOf { it.second } == 10000)
    val weights = percentages.sortedBy { it.first }.associate { it.first to it.second.toLong() }
    return allocate(totalCents, weights).map { (id, amount) -> BillShare(id, amount, weights.getValue(id).toInt()) }
}

/**
 * Mirrors the backend: adds tax and tip to each amount in proportion to it. Extra cents go to the largest
 * remainders; ties use ascending keys. A share of nothing leaves the amounts unchanged.
 */
internal fun proportionalShares(amounts: Map<String, Long>, extraCents: Long): Map<String, Long> {
    val extra = allocate(extraCents, amounts)
    return amounts.mapValues { (id, cents) -> cents + extra.getValue(id) }
}

private fun allocate(total: Long, weights: Map<String, Long>): Map<String, Long> {
    // BigInteger keeps products exact even when two maximum-size amounts are multiplied.
    val denominator = weights.values.sum().toBigInteger()
    if (denominator == BigInteger.ZERO) return weights.mapValues { 0L }
    val products = weights.mapValues { (_, weight) -> total.toBigInteger() * weight.toBigInteger() }
    // Each quotient is at most total, already bounded by the amount policy.
    val shares = products.mapValues { (_, product) -> (product / denominator).toLong() }
    val remaining = (total - shares.values.sum()).toInt()
    val extra = products.entries.sortedWith(compareByDescending<Map.Entry<String, BigInteger>> { it.value % denominator }
        .thenBy { it.key }).take(remaining).map { it.key }.toSet()
    return shares.mapValues { (id, amount) -> amount + if (id in extra) 1 else 0 }
}

/** Signed adjustment such as "8", "+8" or "-2.50" in cents; blank means none. Null when unreadable. */
internal fun adjustmentCents(text: String, digits: Int = 2): Long? {
    val trimmed = text.trim().replace('−', '-')
    if (trimmed.isEmpty()) return 0L
    if (!Regex("[+-]?[0-9]+(\\.[0-9]+)?").matches(trimmed)) return null
    val unsigned = if (trimmed.first() in "+-") trimmed.substring(1) else trimmed
    val cents = Money.parse(unsigned, allowZero = true, digits = digits) ?: return null
    return if (trimmed.startsWith("-")) -cents else cents
}

/** Text for an edit form: blank for no adjustment, "8.00" or "-2.00" otherwise. */
internal fun adjustmentText(cents: Long, digits: Int = 2): String =
    if (cents == 0L) "" else Money.format(cents, digits)

/**
 * Mirrors the backend "adjusted" split: set the adjustments aside, split the rest equally (extra cents by
 * ascending member ID), then add each adjustment back. Null when the adjustments exceed the total or a
 * deduction would make a share negative.
 */
internal fun adjustedBillShares(totalCents: Long, adjustments: List<Pair<String, Long>>): List<BillShare>? {
    require(totalCents in 1..Money.MAX_MINOR)
    require(adjustments.isNotEmpty() && adjustments.size <= 100)
    require(adjustments.map { it.first }.distinct().size == adjustments.size)
    val sorted = adjustments.sortedBy { it.first }
    val pool = totalCents - sorted.sumOf { it.second }
    if (pool < 0) return null
    val allocations = allocate(pool, sorted.associate { it.first to 1L })
    val shares = sorted.map { (id, adjustment) ->
        BillShare(id, allocations.getValue(id) + adjustment, adjustmentCents = adjustment)
    }
    return shares.takeIf { it.all { share -> share.amountCents >= 0 } }
}
