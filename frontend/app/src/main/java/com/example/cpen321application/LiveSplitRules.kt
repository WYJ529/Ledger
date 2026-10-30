package com.example.cpen321application

/** Delivery decisions use a per-session sequence. A gap needs an authoritative snapshot. */
internal enum class SplitUpdateDecision { Ignore, Apply, Recover }

internal fun splitUpdateDecision(lastAppliedSequence: Long?, incomingSequence: Long): SplitUpdateDecision = when {
    incomingSequence < 1 || lastAppliedSequence == null -> SplitUpdateDecision.Recover
    incomingSequence <= lastAppliedSequence -> SplitUpdateDecision.Ignore
    incomingSequence == lastAppliedSequence + 1 -> SplitUpdateDecision.Apply
    else -> SplitUpdateDecision.Recover
}

/** A delayed REST response must not replace a newer push event or reopen a finalization fence. */
internal fun shouldReplaceSplitState(currentSequence: Long, currentStatus: String, incomingSequence: Long, incomingStatus: String): Boolean {
    if (incomingSequence != currentSequence) return incomingSequence > currentSequence
    val states = listOf("open", "finalizing", "finalized")
    return states.indexOf(incomingStatus) >= states.indexOf(currentStatus)
}

/** A reviewed receipt total must equal positive integer-cent units before it is submitted. */
internal fun validSplitReceiptTotal(receiptTotal: String, unitAmounts: List<Long>, digits: Int = 2): Boolean {
    if (unitAmounts.size !in 1..200 || unitAmounts.any { it !in 1..MAX_BILL_CENTS }) return false
    val expected = moneyCents(receiptTotal, digits = digits) ?: return false
    return unitAmounts.sum() == expected
}
