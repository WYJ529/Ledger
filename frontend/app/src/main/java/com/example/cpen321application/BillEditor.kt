package com.example.cpen321application

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** An expense being created or edited. It survives recreation, and member inputs are keyed by member ID. */
@Stable
internal class BillDraft {
    var description by mutableStateOf("")
    var amount by mutableStateOf("")
    var tax by mutableStateOf("")
    var tip by mutableStateOf("")
    var mode by mutableStateOf("equal")
    var legacy by mutableStateOf(false)
    var tipChoice by mutableStateOf("0")
    var paidBy by mutableStateOf("")
    var selected by mutableStateOf(emptyList<String>())
    var custom by mutableStateOf(emptyMap<String, String>())
    var extraEnabled by mutableStateOf(emptyMap<String, Boolean>())
    var extraRates by mutableStateOf(emptyMap<String, String>())
    var percentages by mutableStateOf(emptyMap<String, String>())
    var adjustments by mutableStateOf(emptyMap<String, String>())

    /** Custom amounts share the receipt's own tax and tip instead of calculating them from rates. */
    var receiptSplit by mutableStateOf(false)

    val usesFinalTotal: Boolean get() = mode != "custom" || legacy || receiptSplit

    /** The revision originally opened, kept even if a rotation reloads a newer summary. */
    var expectedRevision = 0

    /** Kept across rotation and uncertain network results so retrying cannot duplicate a bill. */
    var requestId = UUID.randomUUID().toString()

    companion object {
        fun from(summary: GroupExpenseSummary, bill: GroupBill?, selfId: String, digits: Int, prefill: BillPrefill? = null) = BillDraft().apply {
            val meal = bill?.customCalculation?.members.orEmpty().associateBy { it.userId }
            val shares = bill?.shares.orEmpty().associateBy { it.userId }
            description = bill?.description ?: prefill?.description.orEmpty()
            amount = bill?.let { Money.format(it.amountCents, digits) } ?: prefill?.total.orEmpty()
            tax = bill?.taxCents?.takeIf { it > 0 }?.let { Money.format(it, digits) } ?: prefill?.tax.orEmpty()
            tip = bill?.tipCents?.takeIf { it > 0 }?.let { Money.format(it, digits) } ?: prefill?.tip.orEmpty()
            receiptSplit = prefill?.hasReceiptDetail == true
            mode = bill?.splitMode ?: "equal"
            legacy = bill?.splitMode == "custom" && bill.customCalculation == null
            tipChoice = bill?.customCalculation?.tipRate ?: "0"
            paidBy = bill?.paidBy ?: prefill?.paidBy?.ifBlank { null } ?: selfId
            selected = bill?.shares?.map { it.userId } ?: summary.members.map { it.id }
            custom = summary.members.associate { member ->
                member.id to (meal[member.id]?.let { Money.format(it.subtotalCents, digits) }
                    ?: if (legacy) shares[member.id]?.let { Money.format(it.amountCents, digits) }.orEmpty() else "")
            }
            extraEnabled = summary.members.associate { member ->
                member.id to (meal[member.id]?.let { Money.parse(it.extraTaxRate, true) != 0L } ?: false)
            }
            extraRates = summary.members.associate { member -> member.id to (meal[member.id]?.extraTaxRate ?: "7") }
            percentages = summary.members.associate { member ->
                member.id to shares[member.id]?.percentageBasisPoints?.let { Money.format(it.toLong()) }.orEmpty()
            }
            adjustments = summary.members.associate { member ->
                member.id to shares[member.id]?.adjustmentCents?.let { adjustmentText(it, digits) }.orEmpty()
            }
            expectedRevision = bill?.revision ?: 0
        }

        val Saver: Saver<BillDraft, Any> = listSaver(
            save = { listOf(it.description, it.amount, it.mode, it.legacy, it.tipChoice, it.paidBy, ArrayList(it.selected),
                HashMap(it.custom), HashMap(it.extraEnabled), HashMap(it.extraRates), HashMap(it.percentages),
                HashMap(it.adjustments), it.expectedRevision, it.requestId, it.tax, it.tip, it.receiptSplit) },
            restore = { values -> BillDraft().apply {
                description = values[0] as String
                amount = values[1] as String
                mode = values[2] as String
                legacy = values[3] as Boolean
                tipChoice = values[4] as String
                paidBy = values[5] as String
                selected = values[6] as List<String>
                custom = values[7] as Map<String, String>
                extraEnabled = values[8] as Map<String, Boolean>
                extraRates = values[9] as Map<String, String>
                percentages = values[10] as Map<String, String>
                adjustments = values[11] as Map<String, String>
                expectedRevision = values[12] as Int
                requestId = values[13] as String
                tax = values[14] as String
                tip = values[15] as String
                receiptSplit = values[16] as Boolean
            } },
        )
    }
}

/** Amounts and shares derived from a [BillDraft]; [valid] says whether it can be saved. */
internal data class BillCalculation(
    val total: Long?, val amounts: BillAmounts?, val meal: MealCalculation?, val mealTotals: MealTotals?, val assignedCents: Long,
    val percentageValues: List<Pair<String, Int>>, val percentageComplete: Boolean,
    val adjustmentValues: List<Pair<String, Long>>, val adjustedComplete: Boolean,
    val missingParticipants: List<String>, val shares: List<BillShare>, val valid: Boolean,
)

// The total with its typed tax and tip; null when they are unreadable or leave no subtotal.
private fun BillDraft.typedAmounts(total: Long?, digits: Int): BillAmounts? {
    val taxCents = Money.optional(tax, digits) ?: return null
    val tipCents = Money.optional(tip, digits) ?: return null
    if (total == null || total <= taxCents + tipCents) return null
    return BillAmounts(total, total - taxCents - tipCents, taxCents, tipCents)
}

// Each person's pretax amount plus their share of the receipt's tax and tip.
private fun receiptShares(members: List<MealMember>, amounts: BillAmounts?): List<BillShare> {
    if (amounts == null || members.sumOf { it.subtotalCents } != amounts.subtotalCents) return emptyList()
    return proportionalShares(members.associate { it.userId to it.subtotalCents }, amounts.taxCents + amounts.tipCents)
        .map { (id, cents) -> BillShare(id, cents) }
}

internal fun BillDraft.calculate(summary: GroupExpenseSummary, digits: Int, bill: GroupBill?): BillCalculation {
    val memberIds = summary.members.map { it.id }
    val chosen = memberIds.filter { it in selected }
    val mealMembers = chosen.mapNotNull { id ->
        Money.parse(custom[id].orEmpty(), true, digits)?.let { MealMember(id, it, if (extraEnabled[id] == true) extraRates[id] ?: "7" else "0") }
    }
    val finalTotalMode = usesFinalTotal
    val meal = if (!finalTotalMode && mealMembers.size == selected.size) MealCalculation(tipChoice, mealMembers) else null
    val mealTotals = meal?.let { calculateMeal(it) }
    val total = if (finalTotalMode) Money.parse(amount, digits = digits) else mealTotals?.total
    val amounts = if (finalTotalMode) typedAmounts(total, digits) else mealTotals?.let { BillAmounts(it.total, it.subtotal, it.tax, it.tip) }
    val percentageValues = chosen.mapNotNull { id -> percentageBasisPoints(percentages[id].orEmpty())?.let { id to it } }
    val percentageComplete = percentageValues.size == selected.size && percentageValues.sumOf { it.second } == 10000
    val adjustmentValues = chosen.mapNotNull { id -> adjustmentCents(adjustments[id].orEmpty(), digits)?.let { id to it } }
    val adjustedShares = if (total != null && selected.isNotEmpty() && adjustmentValues.size == selected.size)
        adjustedBillShares(total, adjustmentValues) else null
    val missingParticipants = selected.filter { it !in memberIds }
    val shares = when (mode) {
        "equal" -> if (total != null && selected.isNotEmpty()) equalBillShares(total, selected) else emptyList()
        "percentage" -> if (total != null && percentageComplete && selected.isNotEmpty()) percentageBillShares(total, percentageValues) else emptyList()
        "adjusted" -> adjustedShares.orEmpty()
        "custom" -> when {
            legacy -> mealMembers.map { BillShare(it.userId, it.subtotalCents) }
            receiptSplit -> receiptShares(mealMembers, amounts)
            else -> mealTotals?.shares.orEmpty()
        }
        else -> emptyList()
    }
    val adjustedComplete = mode != "adjusted" || adjustedShares != null
    val modeAllowed = mode in listOf("equal", "custom", "percentage") || (mode == "adjusted" && bill?.splitMode == "adjusted")
    val sharesMatch = total != null && shares.size == selected.size && shares.sumOf { it.amountCents } == total
    val valid = description.trim().isNotEmpty() && selected.isNotEmpty() && missingParticipants.isEmpty() &&
        (mode != "percentage" || percentageComplete) && adjustedComplete && modeAllowed && sharesMatch && paidBy in memberIds && amounts != null
    return BillCalculation(total, amounts, meal, mealTotals, mealMembers.sumOf { it.subtotalCents }, percentageValues, percentageComplete, adjustmentValues,
        adjustedComplete, missingParticipants, shares, valid)
}

@Composable
internal fun BillEditor(summary: GroupExpenseSummary, session: Session, onSessionExpired: () -> Unit,
                        onSaved: () -> Unit, bill: GroupBill? = null, prefill: BillPrefill? = null,
                        onByItem: ((BillPrefill) -> Unit)? = null) {
    val currency = summary.group.currency
    // Amount fields use the group currency's decimals; tip and tax rates always use two.
    val digits = Money.digits(currency)
    val draft = rememberSaveable(saver = BillDraft.Saver) { BillDraft.from(summary, bill, session.account.id, digits, prefill) }
    val request = remember { RequestState() }
    val busy = request.working
    val scope = rememberCoroutineScope()
    val calc = draft.calculate(summary, digits, bill)
    Text("${if (bill == null) t("New expense") else t("Edit expense")} · ${summary.group.name}", style = MaterialTheme.typography.titleLarge)
    prefill?.let { BillPrefillNotes(it, currency) }
    BillModeChips(draft, busy, summary.group.ownerId == session.account.id, onByItem?.let { open ->
        { open((prefill ?: BillPrefill()).copy(description = draft.description, total = draft.amount, tax = draft.tax, tip = draft.tip,
            paidBy = draft.paidBy, warnings = emptyList())) }
    })
    BillAmountFields(draft, calc, currency, busy)
    BillPayerPicker(draft, summary.members, busy)
    BillParticipants(draft, calc, summary, digits, busy)
    BillTotals(draft, calc, currency)
    request.error?.let { Text(t(it), color = MaterialTheme.colorScheme.error) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    PrimaryBarButton(enabled = calc.valid && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
        scope.launch {
            request.run(onSessionExpired, onFailure = {
                request.error = it.message ?: "Unable to save expense. Retry to check this submission."
            }) {
                withContext(Dispatchers.IO) { submitBill(session.token, summary.group.id, bill, draft, calc, digits) }
                onSaved()
            }
        }
    }) { Text(if (busy) t("Saving…") else if (bill == null) t("Save expense") else t("Save changes")) }
}

internal fun submitBill(token: String, groupId: String, bill: GroupBill?, draft: BillDraft, calc: BillCalculation, digits: Int) {
    val input = BillSubmission(draft.description.trim(), draft.paidBy, draft.mode, calc.shares, requireNotNull(calc.amounts), calc.meal, digits)
    if (bill == null) GroupExpenseApi.addBill(token, groupId, input, draft.requestId)
    else GroupExpenseApi.updateBill(token, groupId, bill.id, input, draft.expectedRevision)
}

// onByItem is null when editing a saved expense, which cannot become a Let's Go Dutch split.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BillModeChips(draft: BillDraft, busy: Boolean, canSplitByItem: Boolean, onByItem: (() -> Unit)?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = draft.mode == "equal", onClick = { draft.mode = "equal" }, label = { Text(t("Equal split")) }, enabled = !busy)
        FilterChip(selected = draft.mode == "custom", onClick = { draft.mode = "custom" }, label = { Text(t("Custom amounts")) }, enabled = !busy)
        FilterChip(selected = draft.mode == "percentage", onClick = { draft.mode = "percentage" }, label = { Text(t("Percentage")) }, enabled = !busy)
    }
    if (onByItem != null) LetsGoDutchButton(busy, canSplitByItem, onByItem)
}

@Composable
private fun LetsGoDutchButton(busy: Boolean, canStart: Boolean, onClick: () -> Unit) {
    PrimaryBarButton(onClick = onClick, enabled = !busy && canStart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(t("Let’s Go Dutch"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    Text(if (canStart) t("Each person claims their items. Tax and tip follow what they claim.")
        else t("Only the group owner can start Let’s Go Dutch."), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun BillAmountFields(draft: BillDraft, calc: BillCalculation, currency: String, busy: Boolean) {
    OutlinedTextField(draft.description, { draft.description = it.take(100) }, label = { Text(t("Description")) },
        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (draft.mode == "custom" && !draft.legacy) {
        Row {
            Checkbox(checked = draft.receiptSplit, onCheckedChange = { draft.receiptSplit = it }, enabled = !busy)
            Text(t("Use the tax and tip on the receipt"), modifier = Modifier.padding(top = 12.dp))
        }
    }
    if (draft.usesFinalTotal) {
        OutlinedTextField(draft.amount, { draft.amount = it.take(12) }, label = { Text(tf("Final total (%1\$s)", currency)) },
            supportingText = { Text(tf("Includes all tax and tip · maximum %1\$s", Money.display(Money.MAX_MINOR, currency))) },
            isError = draft.amount.isNotEmpty() && calc.total == null, enabled = !busy, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        BillTaxTipFields(draft, busy)
    }
    if (draft.mode == "custom" && draft.legacy) {
        Text(t("This older bill stores final shares. Its amounts are preserved; switch explicitly to recalculate from pretax amounts."))
        BarButton(enabled = !busy, onClick = { draft.legacy = false; draft.custom = emptyMap(); draft.tipChoice = "0" }) {
            Text(t("Recalculate from pretax amounts"))
        }
    }
    if (draft.mode == "adjusted") Text(t("Legacy adjustment bill. Its saved adjustments are preserved; new expenses use Equal, Custom or Percentage."))
    if (!draft.usesFinalTotal) BillTipFields(draft, busy)
}

@Composable
private fun BillTaxTipFields(draft: BillDraft, busy: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(draft.tax, { draft.tax = it.take(12) }, label = { Text(t("Tax (optional)")) }, enabled = !busy, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        OutlinedTextField(draft.tip, { draft.tip = it.take(12) }, label = { Text(t("Tip (optional)")) }, enabled = !busy, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BillTipFields(draft: BillDraft, busy: Boolean) {
    Text(t("Meal split · GST 5% on each person's pretax amount. Optional extra tax applies to that person's entire amount."))
    Text(t("Tip rate · calculated before tax"))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("0", "10", "15", "20").forEach { choice ->
            FilterChip(selected = Money.parse(draft.tipChoice, true) == Money.parse(choice, true), onClick = { draft.tipChoice = choice },
                enabled = !busy, label = { Text("$choice%") })
        }
    }
    OutlinedTextField(draft.tipChoice, { draft.tipChoice = it.take(6) }, label = { Text(t("Tip percent (0–100, custom allowed)")) },
        isError = tipCents(100, draft.tipChoice) == null, enabled = !busy, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun BillPayerPicker(draft: BillDraft, members: List<GroupMember>, busy: Boolean) {
    Text(t("Who paid?"), style = MaterialTheme.typography.titleMedium)
    members.forEach { member ->
        Row {
            RadioButton(selected = draft.paidBy == member.id, onClick = { draft.paidBy = member.id }, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = tf("Paid by %1\$s", member.displayName) })
            BarButton(onClick = { draft.paidBy = member.id }, enabled = !busy) { Text(member.displayName) }
        }
    }
}

@Composable
private fun BillParticipants(draft: BillDraft, calc: BillCalculation, summary: GroupExpenseSummary, digits: Int, busy: Boolean) {
    Text(t("Split between"), style = MaterialTheme.typography.titleMedium)
    if (calc.missingParticipants.isNotEmpty()) {
        Text(t("A selected member has left. Review the split before saving."), color = MaterialTheme.colorScheme.error)
        BarButton(onClick = { draft.selected = draft.selected.filterNot { it in calc.missingParticipants } }, enabled = !busy) {
            Text(t("Remove former participants from draft"))
        }
    }
    summary.members.forEach { member ->
        Row {
            Checkbox(checked = member.id in draft.selected, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = tf("Include %1\$s in split", member.displayName) },
                onCheckedChange = { checked -> draft.selected = if (checked) draft.selected + member.id else draft.selected - member.id })
            Column(Modifier.weight(1f)) {
                Text(member.displayName, modifier = Modifier.padding(top = 12.dp))
                if (member.id in draft.selected) MemberShareFields(draft, member, calc, summary.group.currency, digits, busy)
            }
        }
    }
    if (draft.selected.isEmpty()) Text(t("Select at least one member."), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun MemberShareFields(draft: BillDraft, member: GroupMember, calc: BillCalculation, currency: String, digits: Int, busy: Boolean) {
    val id = member.id
    when (draft.mode) {
        "custom" -> MemberMealFields(draft, member, currency, digits, busy)
        "percentage" -> OutlinedTextField(draft.percentages[id].orEmpty(), { text -> draft.percentages = draft.percentages + (id to text.take(12)) },
            label = { Text(t("Share (%)")) }, enabled = !busy, singleLine = true,
            isError = draft.percentages[id].orEmpty().isNotEmpty() && percentageBasisPoints(draft.percentages[id].orEmpty()) == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        "adjusted" -> OutlinedTextField(draft.adjustments[id].orEmpty(), { text -> draft.adjustments = draft.adjustments + (id to text.take(12)) },
            label = { Text(tf("Adjustment (%1\$s)", currency)) }, placeholder = { Text("0") },
            supportingText = { Text(t("e.g. 8 for an extra drink, -2 to pay less")) },
            enabled = !busy, singleLine = true, isError = adjustmentCents(draft.adjustments[id].orEmpty(), digits) == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    }
    calc.shares.find { it.userId == id }?.let { Text(tf("To pay: %1\$s", Money.display(it.amountCents, currency))) }
}

@Composable
private fun MemberMealFields(draft: BillDraft, member: GroupMember, currency: String, digits: Int, busy: Boolean) {
    val id = member.id
    val value = draft.custom[id].orEmpty()
    OutlinedTextField(value, { text -> draft.custom = draft.custom + (id to text.take(12)) },
        label = { Text(if (draft.legacy) tf("Saved final share (%1\$s)", currency) else tf("Pretax amount · %1\$s", member.displayName)) },
        enabled = !busy, singleLine = true, isError = value.isNotEmpty() && Money.parse(value, true, digits) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    if (draft.legacy || draft.receiptSplit) return
    Row {
        Checkbox(checked = draft.extraEnabled[id] == true, enabled = !busy,
            modifier = Modifier.semantics { contentDescription = tf("Extra tax for %1\$s", member.displayName) },
            onCheckedChange = { checked -> draft.extraEnabled = draft.extraEnabled + (id to checked) })
        Text(tf("Extra tax · %1\$s", member.displayName), modifier = Modifier.padding(top = 12.dp))
    }
    if (draft.extraEnabled[id] == true) {
        OutlinedTextField(draft.extraRates[id] ?: "7", { text -> draft.extraRates = draft.extraRates + (id to text.take(6)) },
            label = { Text(tf("Extra tax %% · %1\$s", member.displayName)) }, enabled = !busy, singleLine = true,
            isError = tipCents(100, draft.extraRates[id] ?: "7") == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun BillTotals(draft: BillDraft, calc: BillCalculation, currency: String) {
    val mealTotals = calc.mealTotals
    if (calc.total != null && calc.amounts == null) Text(t("Tax and tip must be valid and smaller than the total."), color = MaterialTheme.colorScheme.error)
    if (draft.mode == "custom" && !draft.legacy && draft.receiptSplit) calc.amounts?.let { amounts ->
        Text(tf("Assigned before tax: %1\$s of %2\$s", Money.display(calc.assignedCents, currency), Money.display(amounts.subtotalCents, currency)))
        Text(t("Tax and tip are shared in proportion to each person's amount."), style = MaterialTheme.typography.bodySmall)
    }
    if (draft.mode == "custom" && !draft.legacy && mealTotals != null) {
        Text(tf("Subtotal: %1\$s", Money.display(mealTotals.subtotal, currency)))
        Text(tf("Tax: %1\$s · Tip: %2\$s", Money.display(mealTotals.tax, currency), Money.display(mealTotals.tip, currency)))
    }
    when (draft.mode) {
        "equal" -> Text(t("Extra cents go to members in a fixed order so the split always matches the total."), style = MaterialTheme.typography.bodySmall)
        "percentage" -> {
            Text(tf("Assigned: %1\$s%% / 100.00%%", Money.format((calc.percentageValues.sumOf { it.second }).toLong())))
            Text(t("Enter 0–100% per member, with up to two decimals. Extra cents go to the largest fractional shares; ties use a fixed member order."), style = MaterialTheme.typography.bodySmall)
            if (!calc.percentageComplete) Text(t("Enter every selected member's percentage. The percentages must total 100%."), color = MaterialTheme.colorScheme.error)
        }
        "adjusted" -> AdjustmentNotes(draft, calc)
    }
    calc.total?.let { Text(tf("Total: %1\$s", Money.display(it, currency)), fontWeight = FontWeight.Bold) }
    if (!calc.valid) Text(tf("Enter a description and valid amounts for every selected member. Final total must be positive and at most %1\$s.", Money.display(Money.MAX_MINOR, currency)), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AdjustmentNotes(draft: BillDraft, calc: BillCalculation) {
    Text(t("Adjustments are set aside first; the rest of the bill is split equally, then each member's adjustment is added back."),
        style = MaterialTheme.typography.bodySmall)
    val total = calc.total
    if (total == null || draft.selected.isEmpty() || calc.adjustedComplete) return
    val problem = when {
        calc.adjustmentValues.size != draft.selected.size -> "Enter adjustments as amounts such as 8, -2.50 or leave them blank."
        calc.adjustmentValues.sumOf { it.second } > total -> "The added amounts are larger than the bill total."
        else -> "A deduction is larger than that member's equal share. Reduce it so every share is at least 0."
    }
    Text(t(problem), color = MaterialTheme.colorScheme.error)
}
