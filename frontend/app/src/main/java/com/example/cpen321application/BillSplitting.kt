package com.example.cpen321application

import java.util.Locale

internal const val MAX_BILL_CENTS = 100_000_000L
internal data class BillShare(val userId: String, val amountCents: Long)
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

internal fun decimalAmount(cents: Long): String = String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100)
internal fun displayMoney(currency: String, cents: Long): String = "$currency ${decimalAmount(cents)}"
