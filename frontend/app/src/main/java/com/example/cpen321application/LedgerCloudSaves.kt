package com.example.cpen321application

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

/** One occupied cloud slot as listed by the backend; the transactions are fetched only when loading. */
internal data class CloudSaveSlot(
    val slot: Int,
    val revision: Int,
    val savedAt: String,
    val deviceName: String,
    val baseCurrency: String,
    val recordCount: Int,
    val name: String = "",
)

/** The whole device ledger: every transaction and the reporting currency their totals use. */
internal data class LedgerSnapshot(val baseCurrency: String, val records: List<LedgerRecord>)

/** Blocking backend calls; the controller runs them on Dispatchers.IO. */
internal interface LedgerSavesRemote {
    fun list(token: String): List<CloudSaveSlot>
    fun load(token: String, slot: Int): LedgerSnapshot
    fun save(token: String, slot: Int, snapshot: LedgerSnapshot, deviceName: String, replaces: Int?): CloudSaveSlot
    fun saveNamed(token: String, slot: Int, snapshot: LedgerSnapshot, deviceName: String, replaces: Int?, name: String): CloudSaveSlot = save(token, slot, snapshot, deviceName, replaces)
    fun rename(token: String, slot: Int, revision: Int, name: String): CloudSaveSlot = throw LedgerException("Save renaming is unavailable.")
    fun delete(token: String, slot: Int, revision: Int)
}

/** The device ledger as seen by cloud saves. */
internal interface LocalLedger {
    suspend fun snapshot(): LedgerSnapshot
    suspend fun replace(snapshot: LedgerSnapshot)
    suspend fun clear()
}

/** Why a save slot is being chosen, which decides what happens once the save succeeds. */
internal enum class CloudSaveGoal { Manual, AfterSignIn, BeforeLoad, BeforeLogout }

internal sealed interface CloudSaveDialog {
    data class PickSaveSlot(val goal: CloudSaveGoal, val excludedSlot: Int? = null) : CloudSaveDialog
    data class NameSave(val slot: Int, val goal: CloudSaveGoal, val existing: CloudSaveSlot?) : CloudSaveDialog
    data class Rename(val save: CloudSaveSlot) : CloudSaveDialog
    data object PickLoadSlot : CloudSaveDialog
    data class ConfirmOverwrite(val existing: CloudSaveSlot, val goal: CloudSaveGoal) : CloudSaveDialog
    data class ConfirmLoad(val save: CloudSaveSlot) : CloudSaveDialog
    data class ConfirmDelete(val save: CloudSaveSlot) : CloudSaveDialog
    data class OfferSave(val localCount: Int) : CloudSaveDialog
    data class OfferLoad(val saveCount: Int) : CloudSaveDialog
    data class LogoutChoice(val localCount: Int) : CloudSaveDialog
    data class ConfirmClearAndLogout(val localCount: Int) : CloudSaveDialog
    data class Message(val text: String, val isError: Boolean) : CloudSaveDialog
}

/** Pure rules for game-style cloud saves: three fixed slots, each a complete ledger snapshot. */
internal object LedgerCloudSaves {
    val slotNumbers = 1..3
    const val MAX_RECORDS = 5000

    fun rows(slots: List<CloudSaveSlot>): List<Pair<Int, CloudSaveSlot?>> =
        slotNumbers.map { number -> number to slots.firstOrNull { it.slot == number } }

    /** After sign-in, offer to keep local records in the account, or to bring an existing save to an empty device. */
    fun signInOffer(localCount: Int, slots: List<CloudSaveSlot>): CloudSaveDialog? = when {
        localCount > 0 -> CloudSaveDialog.OfferSave(localCount)
        slots.isNotEmpty() -> CloudSaveDialog.OfferLoad(slots.size)
        else -> null
    }

    /** A loaded save replaces the whole ledger, so it must be internally consistent before anything is deleted. */
    fun validate(snapshot: LedgerSnapshot, today: LocalDate = LocalDate.now(ZoneOffset.UTC).plusDays(1)) {
        val records = snapshot.records
        val uniqueIds = records.map { it.id }.toSet().size == records.size && records.none { it.id.isBlank() }
        val problem = when {
            snapshot.baseCurrency !in LedgerDomain.currencies -> "This cloud save uses an unsupported reporting currency."
            records.size > MAX_RECORDS -> "A cloud save can hold at most 5,000 transactions."
            !uniqueIds || records.any { !matchesItsOwnConversion(it, snapshot.baseCurrency, today) } -> "This cloud save is damaged and was not loaded."
            else -> null
        }
        problem?.let { throw LedgerException(it) }
        LedgerDomain.summary(records, snapshot.baseCurrency)
    }

    private fun matchesItsOwnConversion(record: LedgerRecord, base: String, today: LocalDate): Boolean = try {
        val inputValid = LedgerDomain.validate(record.input, today).isEmpty() && record.revision >= 1
        val minorMatches = record.amountMinor == Money.requireMinor(record.input.amount, record.input.currency)
        val fxMatches = record.fx.baseCurrency == base &&
            record.fx.convertedMinor == LedgerDomain.convertMinor(record.amountMinor, record.input.currency, base, record.fx.rate)
        inputValid && minorMatches && fxMatches
    } catch (_: LedgerException) { false }
}

/**
 * Drives the save, load, delete, sign-in and log-out flows. Every action is a suspend function so the
 * whole flow runs in tests without Android; the UI launches them and renders [dialog].
 */
internal class CloudSaves(
    private val local: LocalLedger,
    private val remote: LedgerSavesRemote,
    private val deviceName: String,
) {
    var slots by mutableStateOf<List<CloudSaveSlot>?>(null)
        private set
    private val request = RequestState()
    val working get() = request.working
    var dialog by mutableStateOf<CloudSaveDialog?>(null)
        private set
    var onSessionExpired: () -> Unit = {}
    private var saveName: String = ""
    private var pendingLoad: CloudSaveSlot? = null
    private var logout: (() -> Unit)? = null

    suspend fun refresh(token: String) = runOneStepAtATime(token) { slots = remote.slotsFor(token) }

    /** Forgets the previous account's slots and any unfinished flow. */
    fun reset() {
        slots = null
        endFlowWith(null)
    }

    fun dismiss() { if (!working) endFlowWith(null) }

    /** Called once after an explicit sign-in; an unreachable backend simply skips the offer. */
    suspend fun offerAfterSignIn(token: String, localCount: Int) {
        val listed = try { withContext(Dispatchers.IO) { remote.list(token) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return }
        slots = listed
        dialog = LedgerCloudSaves.signInOffer(localCount, listed)
    }

    /** Opens a slot picker, listing the slots first if this account's slots are not known yet. */
    suspend fun open(token: String, picker: CloudSaveDialog) = runOneStepAtATime(token) {
        if (slots == null) slots = remote.slotsFor(token)
        dialog = picker
    }

    /** Shows a confirmation; nothing changes until it is confirmed. */
    fun show(confirmation: CloudSaveDialog) { dialog = confirmation }

    /** Empty slots ask for a name; an occupied slot is overwritten only after its own confirmation. */
    suspend fun pickSaveSlot(token: String, slot: Int, goal: CloudSaveGoal) {
        val existing = slots?.firstOrNull { it.slot == slot }
        if (existing != null) dialog = CloudSaveDialog.ConfirmOverwrite(existing, goal)
        else dialog = CloudSaveDialog.NameSave(slot, goal, null)
    }

    suspend fun confirmOverwrite(token: String) {
        val confirm = dialog as? CloudSaveDialog.ConfirmOverwrite ?: return
        dialog = CloudSaveDialog.NameSave(confirm.existing.slot, confirm.goal, confirm.existing)
    }

    suspend fun confirmName(token: String, name: String) {
        val current = dialog as? CloudSaveDialog.NameSave ?: return
        runOneStepAtATime(token) {
            val clean = name.trim()
            require(clean.isNotEmpty() && clean.length <= 40) { "Enter a save name of 1–40 characters." }
            saveName = clean
            save(token, current.slot, current.existing?.revision, current.goal)
        }
    }

    suspend fun rename(token: String, name: String) {
        val current = dialog as? CloudSaveDialog.Rename ?: return
        runOneStepAtATime(token) {
            val clean = name.trim()
            require(clean.isNotEmpty() && clean.length <= 40) { "Enter a save name of 1–40 characters." }
            val saved = withContext(Dispatchers.IO) { remote.rename(token, current.save.slot, current.save.revision, clean) }
            slots = slots.orEmpty().map { if (it.slot == saved.slot) saved else it }
            endFlowWith(null)
        }
    }

    /** Keep the current ledger in another slot first; the confirmed load continues after that save. */
    fun saveBeforeLoad() {
        val confirm = dialog as? CloudSaveDialog.ConfirmLoad ?: return
        pendingLoad = confirm.save
        dialog = CloudSaveDialog.PickSaveSlot(CloudSaveGoal.BeforeLoad, excludedSlot = confirm.save.slot)
    }

    suspend fun confirmLoad(token: String) {
        val confirm = dialog as? CloudSaveDialog.ConfirmLoad ?: return
        runOneStepAtATime(token) { load(token, confirm.save, savedFirst = null) }
    }

    suspend fun confirmDelete(token: String) {
        val confirm = dialog as? CloudSaveDialog.ConfirmDelete ?: return
        runOneStepAtATime(token) {
            withContext(Dispatchers.IO) { remote.delete(token, confirm.save.slot, confirm.save.revision) }
            slots = slots.orEmpty().filter { it.slot != confirm.save.slot }
            endFlowWith(notice(tf("%1\$s was deleted. Transactions on this device were not changed.", cloudSaveTitle(confirm.save))))
        }
    }

    /** Returns false when the device ledger is empty, so the ordinary log-out confirmation applies. */
    fun askLogout(localCount: Int, onLogout: () -> Unit): Boolean {
        if (localCount == 0) return false
        logout = onLogout
        dialog = CloudSaveDialog.LogoutChoice(localCount)
        return true
    }

    /** Runs only from its own confirmation, after [askLogout]. */
    suspend fun clearAndLogout(token: String) {
        val onLogout = logout.takeIf { dialog is CloudSaveDialog.ConfirmClearAndLogout } ?: return
        runOneStepAtATime(token) {
            local.clear()
            endFlowWith(null)
            onLogout()
        }
    }

    private suspend fun save(token: String, slot: Int, replaces: Int?, goal: CloudSaveGoal) {
        val snapshot = local.snapshot()
        if (snapshot.records.size > LedgerCloudSaves.MAX_RECORDS) throw LedgerException("A cloud save can hold at most 5,000 transactions.")
        val saved = withContext(Dispatchers.IO) { remote.saveNamed(token, slot, snapshot, deviceName, replaces, saveName) }
        slots = (slots.orEmpty().filter { it.slot != slot } + saved).sortedBy { it.slot }
        val message = tf("Saved %1\$d transactions to %2\$s.", saved.recordCount, cloudSaveTitle(saved))
        val toLoad = pendingLoad
        val onLogout = logout
        when {
            goal == CloudSaveGoal.BeforeLoad && toLoad != null -> load(token, toLoad, savedFirst = message)
            goal == CloudSaveGoal.BeforeLogout && onLogout != null -> { endFlowWith(null); onLogout() }
            else -> endFlowWith(notice(message))
        }
    }

    private suspend fun load(token: String, save: CloudSaveSlot, savedFirst: String?) {
        val snapshot = withContext(Dispatchers.IO) { remote.load(token, save.slot) }
        LedgerCloudSaves.validate(snapshot)
        local.replace(snapshot)
        val loaded = tf("Loaded %1\$s: %2\$d transactions are now on this device.", cloudSaveTitle(save), snapshot.records.size)
        endFlowWith(notice(if (savedFirst == null) loaded else "$savedFirst $loaded"))
    }

    private fun endFlowWith(next: CloudSaveDialog?) {
        pendingLoad = null
        logout = null
        dialog = next
    }

    private suspend fun runOneStepAtATime(token: String, action: suspend () -> Unit) =
        request.run(onExpired = { reset(); onSessionExpired() }, onFailure = { error ->
            endFlowWith(failure(error.message))
            if (error is ApiException && error.status == 409) handleRequestFailures({}) { slots = remote.slotsFor(token) }
        }, action = action)

}

private suspend fun LedgerSavesRemote.slotsFor(token: String) = withContext(Dispatchers.IO) { list(token) }

private fun notice(text: String) = CloudSaveDialog.Message(text, isError = false)

private fun failure(message: String?) =
    CloudSaveDialog.Message(message ?: "The cloud save could not be completed. Try again.", isError = true)

internal fun cloudSaveTitle(save: CloudSaveSlot): String = save.name.ifBlank { tf("Save %1\$d", save.slot) }
