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
    val valid: Boolean get() = name.trim().isNotEmpty() && quantity.toIntOrNull()?.let { it in 1..20 } == true && moneyCents(price) != null

    fun units(index: Int): List<SplitItemUnit> = if (!valid) emptyList() else {
        List(quantity.toInt()) { copy -> SplitItemUnit("${index + 1}-${copy + 1}", name.trim(), moneyCents(price)!!, null) }
    }
}

internal class GroupSplitForm(saved: SavedSplitStart? = null) {
    var description by mutableStateOf(saved?.description.orEmpty())
    var receiptTotal by mutableStateOf(saved?.let { decimalAmount(it.units.sumOf { unit -> unit.amountCents }) }.orEmpty())
    var payer by mutableStateOf(saved?.paidBy.orEmpty())
    var items by mutableStateOf(saved?.units?.map { SplitReceiptRow(it.description, "1", decimalAmount(it.amountCents)) }
        ?: listOf(SplitReceiptRow()))
    var reviewed by mutableStateOf(saved != null)
    var startingAnother by mutableStateOf(saved != null)
    val expandedUnits: List<SplitItemUnit> get() = items.flatMapIndexed { index, row -> row.units(index) }
    val rawFieldsValid: Boolean get() = items.all { it.valid }

    fun valid(members: List<GroupMember>): Boolean {
        if (!reviewed || description.trim().isEmpty() || description.length > 100) return false
        if (!rawFieldsValid || members.none { it.id == payer }) return false
        return validSplitReceiptTotal(receiptTotal, expandedUnits.map { it.amountCents })
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

    fun reset() {
        description = ""
        receiptTotal = ""
        items = listOf(SplitReceiptRow())
        reviewed = false
        startingAnother = true
    }

    companion object {
        val StateSaver = listSaver<GroupSplitForm, Any>(
            save = { listOf(it.description, it.receiptTotal, it.payer, it.reviewed, it.startingAnother) +
                it.items.flatMap { row -> listOf(row.name, row.quantity, row.price) } },
            restore = { values ->
                GroupSplitForm().apply {
                    description = values[0] as String
                    receiptTotal = values[1] as String
                    payer = values[2] as String
                    reviewed = values[3] as Boolean
                    startingAnother = values[4] as Boolean
                    items = values.drop(5).chunked(3).map { SplitReceiptRow(it[0] as String, it[1] as String, it[2] as String) }
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
    val canSubmit = state.canInteract && pending.operation == null &&
        (pending.startPayload.isNotBlank() || form.valid(members))
    Text("Start a split session", style = MaterialTheme.typography.titleLarge)
    if (form.startingAnother && state.activeSplit != null && pending.startPayload.isBlank()) {
        BackBar("Back to saved split", onClick = { form.startingAnother = false }, enabled = !state.working)
    }
    OutlinedTextField(form.description, { form.description = it.take(100); form.reviewed = false },
        label = { Text("Receipt description") }, enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
    SplitReceiptPayer(form, members, editable)
    OutlinedTextField(form.receiptTotal, { form.receiptTotal = it.take(12); form.reviewed = false },
        label = { Text("Receipt total ($currency)") }, supportingText = { Text("Must match all item units exactly") },
        enabled = editable, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth())
    val units = form.expandedUnits
    Text("Item units · $currency ${decimalAmount(units.sumOf { it.amountCents })}", style = MaterialTheme.typography.titleMedium)
    form.items.forEachIndexed { index, row -> SplitReceiptItem(form, index, row, editable) }
    BarButton(onClick = form::add, enabled = editable && form.items.size < 30) { Text("Add receipt item") }
    SplitReceiptValidation(form, units)
    Row {
        Checkbox(checked = form.reviewed, onCheckedChange = { form.reviewed = it }, enabled = editable)
        Text("I reviewed the receipt items and confirmed their total.", modifier = Modifier.padding(top = 12.dp))
    }
    PrimaryBarButton(onClick = onStart, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.working) "Starting…" else if (pending.startPayload.isNotBlank()) "Retry same start" else "Start split session")
    }
}

@Composable
private fun SplitReceiptPayer(form: GroupSplitForm, members: List<GroupMember>, enabled: Boolean) {
    members.forEach { member ->
        val select = { form.payer = member.id; form.reviewed = false }
        Row {
            RadioButton(selected = form.payer == member.id, onClick = select, enabled = enabled)
            BarButton(onClick = select, enabled = enabled) { Text("Paid by ${member.displayName}") }
        }
    }
}

@Composable
private fun SplitReceiptItem(form: GroupSplitForm, index: Int, row: SplitReceiptRow, enabled: Boolean) {
    Text("Item ${index + 1}", fontWeight = FontWeight.SemiBold)
    OutlinedTextField(row.name, { form.update(index, row.copy(name = it.take(80))) },
        label = { Text("Item name") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(row.quantity, { form.update(index, row.copy(quantity = it.filter(Char::isDigit).take(2))) },
            label = { Text("Qty") }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        OutlinedTextField(row.price, { form.update(index, row.copy(price = it.take(12))) },
            label = { Text("Each") }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(2f))
        if (form.items.size > 1) BarButton(onClick = { form.remove(index) }, enabled = enabled) { Text("Remove") }
    }
}

@Composable
private fun SplitReceiptValidation(form: GroupSplitForm, units: List<SplitItemUnit>) {
    if (form.items.any { it.name.isNotBlank() } && !form.rawFieldsValid) {
        Text("Enter each item name, quantity (1–20), and unit price.", color = MaterialTheme.colorScheme.error)
    }
    if (units.size > 200) Text("A split session supports up to 200 item units.", color = MaterialTheme.colorScheme.error)
    if (form.receiptTotal.isNotBlank() && moneyCents(form.receiptTotal) != units.sumOf { it.amountCents }) {
        Text("Item units must equal the receipt total. Add an item for tax, tip, or other charges.", color = MaterialTheme.colorScheme.error)
    }
}
