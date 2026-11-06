package com.example.cpen321application

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

internal data class SplitReceiptRow(val name: String = "", val quantity: String = "1", val price: String = "") {
    fun valid(digits: Int): Boolean =
        name.trim().isNotEmpty() && quantity.toIntOrNull()?.let { it in 1..20 } == true && moneyCents(price, digits = digits) != null

    fun units(index: Int, digits: Int): List<SplitItemUnit> = if (!valid(digits)) emptyList() else {
        List(quantity.toInt()) { copy -> SplitItemUnit("${index + 1}-${copy + 1}", name.trim(), moneyCents(price, digits = digits)!!, null) }
    }
}

internal class GroupSplitForm(private val saved: SavedSplitStart? = null) {
    /** Decimal places of the group currency (0 for JPY and KRW); set once the group summary is known. */
    var digits = 2
        private set
    var description by mutableStateOf(saved?.description.orEmpty())
    var receiptTotal by mutableStateOf(saved?.let { decimalAmount(it.units.sumOf { unit -> unit.amountCents } + it.taxCents + it.tipCents) }.orEmpty())
    var tax by mutableStateOf(saved?.taxCents?.takeIf { it > 0 }?.let { decimalAmount(it) }.orEmpty())
    var tip by mutableStateOf(saved?.tipCents?.takeIf { it > 0 }?.let { decimalAmount(it) }.orEmpty())
    var payer by mutableStateOf(saved?.paidBy.orEmpty())
    var items by mutableStateOf(saved?.units?.map { SplitReceiptRow(it.description, "1", decimalAmount(it.amountCents)) }
        ?: listOf(SplitReceiptRow()))
    var reviewed by mutableStateOf(saved != null)
    var startingAnother by mutableStateOf(saved != null)
    val expandedUnits: List<SplitItemUnit> get() = items.flatMapIndexed { index, row -> row.units(index, digits) }
    val rawFieldsValid: Boolean get() = items.all { it.valid(digits) }
    val taxCents: Long? get() = extraCents(tax)
    val tipCents: Long? get() = extraCents(tip)
    private val extrasCents: Long? get() = taxCents?.let { taxAmount -> tipCents?.let { taxAmount + it } }

    private fun extraCents(text: String): Long? = if (text.isBlank()) 0L else moneyCents(text.trim(), allowZero = true, digits = digits)

    /** The items plus tax and tip, when both are readable. */
    val expectedTotal: Long? get() = extrasCents?.let { it + expandedUnits.sumOf { unit -> unit.amountCents } }

    /** Takes over a bill from the shared entry; items it did not list are entered here. */
    fun fill(bill: BillPrefill) {
        description = bill.description.take(100)
        receiptTotal = bill.total
        tax = bill.tax
        tip = bill.tip
        if (bill.paidBy.isNotBlank()) payer = bill.paidBy
        if (bill.items.isNotEmpty()) items = bill.items
        reviewed = false
        startingAnother = true
    }

    /** Applies the group currency; a restored, already-submitted receipt is re-shown in that currency's format. */
    fun useCurrencyDigits(value: Int) {
        if (value == digits) return
        digits = value
        if (saved != null && reviewed) {
            receiptTotal = decimalAmount(saved.units.sumOf { it.amountCents } + saved.taxCents + saved.tipCents, value)
            tax = saved.taxCents.takeIf { it > 0 }?.let { decimalAmount(it, value) }.orEmpty()
            tip = saved.tipCents.takeIf { it > 0 }?.let { decimalAmount(it, value) }.orEmpty()
            items = saved.units.map { SplitReceiptRow(it.description, "1", decimalAmount(it.amountCents, value)) }
        }
    }

    fun valid(members: List<GroupMember>): Boolean {
        if (!reviewed || description.trim().isEmpty() || description.length > 100) return false
        if (!rawFieldsValid || members.none { it.id == payer }) return false
        val extra = extrasCents ?: return false
        return validSplitReceiptTotal(receiptTotal, expandedUnits.map { it.amountCents }, digits, extra)
    }

    fun update(index: Int, row: SplitReceiptRow) {
        items = items.toMutableList().also { it[index] = row }
        reviewed = false
    }

    fun remove(index: Int) {
        items = items.filterIndexed { i, _ -> i != index }
        reviewed = false
    }

    fun add() {
        if (items.size < 30) items = items + SplitReceiptRow()
        reviewed = false
    }

    companion object {
        val StateSaver = listSaver<GroupSplitForm, Any>(
            save = { listOf(it.description, it.receiptTotal, it.payer, it.reviewed, it.startingAnother, it.tax, it.tip) +
                it.items.flatMap { row -> listOf(row.name, row.quantity, row.price) } },
            restore = { values ->
                GroupSplitForm().apply {
                    description = values[0] as String
                    receiptTotal = values[1] as String
                    payer = values[2] as String
                    reviewed = values[3] as Boolean
                    startingAnother = values[4] as Boolean
                    tax = values[5] as String
                    tip = values[6] as String
                    items = values.drop(7).chunked(3).map { SplitReceiptRow(it[0] as String, it[1] as String, it[2] as String) }
                }
            },
        )
    }
}

@Composable
internal fun SplitReceiptForm(
    form: GroupSplitForm,
    state: GroupSplitState,
    pending: GroupSplitPendingState,
    onStart: () -> Unit,
) {
    val editable = !state.working && pending.startPayload.isBlank()
    val members = state.summary?.members.orEmpty()
    val currency = state.summary?.group?.currency.orEmpty()
    if (currency.isNotEmpty()) form.useCurrencyDigits(moneyDigits(currency))
    val canSubmit = state.canInteract && pending.operation == null &&
        (pending.startPayload.isNotBlank() || form.valid(members))
    Text(t("Start a split session"), style = MaterialTheme.typography.titleLarge)
    if (form.startingAnother && state.activeSplit != null && pending.startPayload.isBlank()) {
        BackBar("Back to saved split", onClick = { form.startingAnother = false }, enabled = !state.working)
    }
    OutlinedTextField(form.description, { form.description = it.take(100); form.reviewed = false },
        label = { Text(t("Receipt description")) }, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
    SplitReceiptPayer(form, members, editable)
    OutlinedTextField(form.receiptTotal, { form.receiptTotal = it.take(12); form.reviewed = false },
        label = { Text(tf("Receipt total (%1\$s)", currency)) }, supportingText = { Text(t("Must equal the items plus tax and tip")) },
        enabled = editable, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth())
    SplitReceiptExtras(form, editable)
    val units = form.expandedUnits
    Text(tf("Item units · %1\$s", "$currency ${decimalAmount(units.sumOf { it.amountCents }, form.digits)}"), style = MaterialTheme.typography.titleMedium)
    form.items.forEachIndexed { index, row -> SplitReceiptItem(form, index, row, editable) }
    BarButton(onClick = form::add, enabled = editable && form.items.size < 30) { Text(t("Add receipt item")) }
    SplitReceiptValidation(form, units)
    Row {
        Checkbox(checked = form.reviewed, onCheckedChange = { form.reviewed = it }, enabled = editable)
        Text(t("I reviewed the receipt items and confirmed their total."), modifier = Modifier.padding(top = 12.dp))
    }
    PrimaryBarButton(onClick = onStart, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.working) t("Starting…") else if (pending.startPayload.isNotBlank()) t("Retry same start") else t("Start split session"))
    }
}

@Composable
private fun SplitReceiptExtras(form: GroupSplitForm, enabled: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(form.tax, { form.tax = it.take(12); form.reviewed = false }, label = { Text(t("Tax (optional)")) },
            isError = form.taxCents == null, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        OutlinedTextField(form.tip, { form.tip = it.take(12); form.reviewed = false }, label = { Text(t("Tip (optional)")) },
            isError = form.tipCents == null, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SplitReceiptPayer(form: GroupSplitForm, members: List<GroupMember>, enabled: Boolean) {
    members.forEach { member ->
        val select = { form.payer = member.id; form.reviewed = false }
        Row {
            RadioButton(selected = form.payer == member.id, onClick = select, enabled = enabled)
            BarButton(onClick = select, enabled = enabled) { Text(tf("Paid by %1\$s", member.displayName)) }
        }
    }
}

@Composable
private fun SplitReceiptItem(form: GroupSplitForm, index: Int, row: SplitReceiptRow, enabled: Boolean) {
    Text(tf("Item %1\$d", index + 1), fontWeight = FontWeight.SemiBold)
    OutlinedTextField(row.name, { form.update(index, row.copy(name = it.take(80))) },
        label = { Text(t("Item name")) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(row.quantity, { form.update(index, row.copy(quantity = it.filter(Char::isDigit).take(2))) },
            label = { Text(t("Qty")) }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        OutlinedTextField(row.price, { form.update(index, row.copy(price = it.take(12))) },
            label = { Text(t("Each")) }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(2f))
        if (form.items.size > 1) BarButton(onClick = { form.remove(index) }, enabled = enabled) { Text(t("Remove")) }
    }
}

@Composable
private fun SplitReceiptValidation(form: GroupSplitForm, units: List<SplitItemUnit>) {
    if (form.items.any { it.name.isNotBlank() } && !form.rawFieldsValid) {
        Text(t("Enter each item name, quantity (1–20), and unit price."), color = MaterialTheme.colorScheme.error)
    }
    if (units.size > 200) Text(t("A split session supports up to 200 item units."), color = MaterialTheme.colorScheme.error)
    if (form.receiptTotal.isNotBlank() && moneyCents(form.receiptTotal, digits = form.digits) != form.expectedTotal) {
        Text(t("Item units plus tax and tip must equal the receipt total."), color = MaterialTheme.colorScheme.error)
    }
}
