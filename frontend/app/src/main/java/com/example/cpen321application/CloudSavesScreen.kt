package com.example.cpen321application

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Me → Cloud saves: three game-style slots, each holding one complete copy of the device ledger. */
@Composable
internal fun CloudSavesPage(cloud: CloudSaves, session: Session, localCount: Int) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(session.token) { cloud.refresh(session.token) }
    Text(t("Cloud saves"), style = MaterialTheme.typography.headlineMedium)
    Text(t("Your account has 3 cloud save slots. A save stores every transaction on this device and its reporting currency. Saving and loading are manual: load a save on another device to continue there."),
        style = MaterialTheme.typography.bodyMedium)
    Text(tf("This device: %1\$d transactions", localCount), style = MaterialTheme.typography.titleSmall)
    if (cloud.working) LinearProgressIndicator(Modifier.fillMaxWidth())
    val slots = cloud.slots
    if (slots == null) {
        if (!cloud.working) BarButton(onClick = { scope.launch { cloud.refresh(session.token) } }, modifier = Modifier.fillMaxWidth()) {
            Text(t("Retry"))
        }
        return
    }
    LedgerCloudSaves.rows(slots).forEach { (number, save) ->
        CloudSaveCard(number, save, session.account.timeZone, enabled = !cloud.working,
            onSave = { scope.launch { cloud.pickSaveSlot(session.token, number, CloudSaveGoal.Manual) } },
            onLoad = { save?.let { cloud.show(CloudSaveDialog.ConfirmLoad(it)) } },
            onDelete = { save?.let { cloud.show(CloudSaveDialog.ConfirmDelete(it)) } })
    }
}

@Composable
private fun CloudSaveCard(number: Int, save: CloudSaveSlot?, timeZone: String, enabled: Boolean,
    onSave: () -> Unit, onLoad: () -> Unit, onDelete: () -> Unit) {
    Panel(Modifier.fillMaxWidth().testTag("cloud-save-$number")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tf("Save %1\$d", number), style = MaterialTheme.typography.titleMedium)
            if (save == null) Text(t("Empty slot"), style = MaterialTheme.typography.bodyMedium)
            else {
                Text(tf("%1\$d transactions · reporting in %2\$s", save.recordCount, save.baseCurrency), style = MaterialTheme.typography.bodyMedium)
                Text(tf("Saved %1\$s on %2\$s", formatSaveTime(save.savedAt, timeZone), save.deviceName),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryBarButton(enabled = enabled, onClick = onSave, modifier = Modifier.weight(1f).testTag("cloud-save-$number-save")) {
                    Text(t("Save here"))
                }
                if (save != null) {
                    BarButton(enabled = enabled, onClick = onLoad, modifier = Modifier.weight(1f).testTag("cloud-save-$number-load")) { Text(t("Load")) }
                    BarButton(enabled = enabled, onClick = onDelete, modifier = Modifier.weight(1f)) {
                        Text(t("Delete"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun formatSaveTime(iso: String, zone: String): String = try {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(L10n.locale)
        .format(Instant.parse(iso).atZone(ZoneId.of(zone)))
} catch (_: Exception) { t("Unknown") }

/** One button of a cloud save confirmation; [label] is already translated. */
private data class CloudSaveAction(val label: String, val tag: String, val danger: Boolean = false, val onClick: () -> Unit)

private data class CloudSaveConfirmation(val title: String, val message: String, val actions: List<CloudSaveAction>, val cancelLabel: String)

/** Every cloud save flow's dialogs, wherever it started: the Cloud saves page, sign-in, or log out. */
@Composable
internal fun CloudSaveDialogs(cloud: CloudSaves, session: Session?, localCount: Int) {
    val current = cloud.dialog ?: return
    val token = session?.token ?: return
    val scope = rememberCoroutineScope()
    val zone = session.account.timeZone
    val run: (suspend CloudSaves.() -> Unit) -> Unit = { action -> scope.launch { cloud.action() } }
    when (current) {
        is CloudSaveDialog.PickSaveSlot -> SlotPicker(cloud, if (current.goal == CloudSaveGoal.BeforeLogout) t("Save before logging out") else t("Choose a save slot"),
            tf("The %1\$d transactions on this device will be saved. They also stay on this device.", localCount),
            // Saving over the save about to be loaded would lose it, so that slot is not offered.
            LedgerCloudSaves.rows(cloud.slots.orEmpty()).filter { it.first != current.excludedSlot }, zone, tagPrefix = "pick-save") { number, _ ->
            run { pickSaveSlot(token, number, current.goal) }
        }
        CloudSaveDialog.PickLoadSlot -> SlotPicker(cloud, t("Choose a save to load"), null,
            cloud.slots.orEmpty().map { it.slot to it }, zone, tagPrefix = "pick-load") { _, save ->
            save?.let { cloud.show(CloudSaveDialog.ConfirmLoad(it)) }
        }
        else -> ConfirmationDialog(cloud, confirmation(current, cloud, localCount, zone) { action -> run { action(this, token) } })
    }
}

private fun confirmation(
    current: CloudSaveDialog, cloud: CloudSaves, localCount: Int, zone: String, run: (suspend CloudSaves.(String) -> Unit) -> Unit,
): CloudSaveConfirmation = when (current) {
    is CloudSaveDialog.ConfirmOverwrite -> CloudSaveConfirmation(tf("Overwrite Save %1\$d?", current.existing.slot),
        tf("Save %1\$d from %2\$s (%3\$d transactions) will be replaced by the %4\$d transactions on this device.",
            current.existing.slot, formatSaveTime(current.existing.savedAt, zone), current.existing.recordCount, localCount),
        listOf(CloudSaveAction(t("Overwrite"), "confirm-overwrite", danger = true) { run { confirmOverwrite(it) } }), t("Cancel"))
    is CloudSaveDialog.ConfirmLoad -> CloudSaveConfirmation(tf("Load Save %1\$d?", current.save.slot),
        tf("Loading replaces the %1\$d transactions on this device with the %2\$d transactions in Save %3\$d. Changes you have not saved to the cloud will be lost.",
            localCount, current.save.recordCount, current.save.slot),
        listOfNotNull(CloudSaveAction(t("Load and replace"), "confirm-load", danger = true) { run { confirmLoad(it) } },
            if (localCount > 0) CloudSaveAction(t("Save current ledger first"), "save-before-load", onClick = cloud::saveBeforeLoad) else null),
        t("Cancel"))
    is CloudSaveDialog.ConfirmDelete -> CloudSaveConfirmation(tf("Delete Save %1\$d?", current.save.slot),
        t("This cloud save will be removed from your account. Transactions on this device are not changed."),
        listOf(CloudSaveAction(t("Delete save"), "confirm-delete", danger = true) { run { confirmDelete(it) } }), t("Cancel"))
    is CloudSaveDialog.OfferSave -> CloudSaveConfirmation(t("Save this device's ledger?"),
        tf("This device has %1\$d transactions. Save them to one of this account's 3 cloud save slots so you can load them on another device.", current.localCount),
        listOf(CloudSaveAction(t("Choose a slot"), "offer-save") { run { open(it, CloudSaveDialog.PickSaveSlot(CloudSaveGoal.AfterSignIn)) } }), t("Not now"))
    is CloudSaveDialog.OfferLoad -> CloudSaveConfirmation(t("Load a cloud save?"),
        tf("This account has %1\$d cloud saves. Load one to bring its transactions to this device.", current.saveCount),
        listOf(CloudSaveAction(t("Choose a save"), "offer-load") { run { open(it, CloudSaveDialog.PickLoadSlot) } }), t("Not now"))
    is CloudSaveDialog.LogoutChoice -> CloudSaveConfirmation(t("Log out?"),
        tf("This device has %1\$d transactions. Choose what happens to them before you log out.", current.localCount),
        listOf(CloudSaveAction(t("Save to the cloud, then log out"), "logout-save") { run { open(it, CloudSaveDialog.PickSaveSlot(CloudSaveGoal.BeforeLogout)) } },
            CloudSaveAction(t("Delete from this device, then log out"), "logout-clear", danger = true) {
                cloud.show(CloudSaveDialog.ConfirmClearAndLogout(current.localCount))
            }),
        t("Cancel"))
    is CloudSaveDialog.ConfirmClearAndLogout -> CloudSaveConfirmation(t("Delete this device's ledger?"),
        tf("This permanently removes the %1\$d transactions on this device. Your cloud saves stay in your account.", current.localCount),
        listOf(CloudSaveAction(t("Delete and log out"), "confirm-clear-logout", danger = true) { run { clearAndLogout(it) } }), t("Cancel"))
    is CloudSaveDialog.Message -> CloudSaveConfirmation(t("Cloud saves"), t(current.text), emptyList(), t("Close"))
    is CloudSaveDialog.PickSaveSlot, CloudSaveDialog.PickLoadSlot -> error("Slot pickers are not confirmations.")
}

@Composable
private fun ConfirmationDialog(cloud: CloudSaves, confirmation: CloudSaveConfirmation) {
    val isError = (cloud.dialog as? CloudSaveDialog.Message)?.isError == true
    AlertDialog(onDismissRequest = cloud::dismiss, title = { Text(confirmation.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(confirmation.message, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (cloud.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                confirmation.actions.forEach { action ->
                    BarButton(enabled = !cloud.working, onClick = action.onClick, modifier = Modifier.testTag(action.tag)) {
                        Text(action.label, color = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
                BarButton(enabled = !cloud.working, onClick = cloud::dismiss) { Text(confirmation.cancelLabel) }
            }
        })
}

@Composable
private fun SlotPicker(cloud: CloudSaves, title: String, message: String?, rows: List<Pair<Int, CloudSaveSlot?>>, zone: String,
    tagPrefix: String, onPick: (Int, CloudSaveSlot?) -> Unit) {
    AlertDialog(onDismissRequest = cloud::dismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let { Text(it) }
                if (cloud.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                rows.forEach { (number, save) ->
                    BarButton(enabled = !cloud.working, onClick = { onPick(number, save) },
                        modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-$number")) {
                        Column(Modifier.weight(1f)) {
                            Text(tf("Save %1\$d", number), style = MaterialTheme.typography.titleSmall)
                            Text(if (save == null) t("Empty slot") else tf("%1\$d transactions · saved %2\$s", save.recordCount, formatSaveTime(save.savedAt, zone)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { BarButton(enabled = !cloud.working, onClick = cloud::dismiss) { Text(t("Cancel")) } })
}
