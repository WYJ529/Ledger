package com.example.cpen321application

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class LedgerCloudSavesTest {
    private fun record(id: String, amount: String = "12.50", amountMinor: Long = 1250, converted: Long = 1700, base: String = "CAD") =
        LedgerRecord(id, LedgerInput("expense", amount, "USD", "2026-10-01", "Corner Cafe", "Food"), amountMinor,
            LedgerFx(base, "1.36", "2026-10-01", "Frankfurter (ECB)", converted), createdAt = "2026-10-01T12:00:00Z", updatedAt = "2026-10-01T12:00:00Z")

    private fun slot(number: Int, revision: Int = 1, count: Int = 1) =
        CloudSaveSlot(number, revision, "2026-10-02T08:00:00Z", "Pixel 9", "CAD", count)

    private val today = LocalDate.of(2026, 11, 2)

    private class FakeLocal(var ledger: LedgerSnapshot, val events: MutableList<String>) : LocalLedger {
        override suspend fun snapshot() = ledger
        override suspend fun replace(snapshot: LedgerSnapshot) { events += "replace:${snapshot.records.size}"; ledger = snapshot }
        override suspend fun clear() { events += "clear"; ledger = LedgerSnapshot(ledger.baseCurrency, emptyList()) }
    }

    private class FakeRemote(val saves: MutableMap<Int, Pair<CloudSaveSlot, LedgerSnapshot>>, val events: MutableList<String>) : LedgerSavesRemote {
        var failure: Exception? = null
        override fun list(token: String) = saves.values.map { it.first }.sortedBy { it.slot }
        override fun load(token: String, slot: Int): LedgerSnapshot {
            failure?.let { throw it }
            events += "load:$slot"
            return saves.getValue(slot).second
        }
        override fun save(token: String, slot: Int, snapshot: LedgerSnapshot, deviceName: String, replaces: Int?): CloudSaveSlot {
            failure?.let { throw it }
            events += "save:$slot:replaces=$replaces"
            if (saves[slot]?.first?.revision != replaces) throw ApiException(409, "This save slot changed on another device. Review the save slots and try again.")
            val saved = CloudSaveSlot(slot, (replaces ?: 0) + 1, "2026-11-02T10:00:00Z", deviceName, snapshot.baseCurrency, snapshot.records.size)
            saves[slot] = saved to snapshot
            return saved
        }
        override fun delete(token: String, slot: Int, revision: Int) {
            failure?.let { throw it }
            events += "delete:$slot:$revision"
            saves.remove(slot)
        }
        override fun saveNamed(token: String, slot: Int, snapshot: LedgerSnapshot, deviceName: String, replaces: Int?, name: String): CloudSaveSlot {
            val saved = save(token, slot, snapshot, deviceName, replaces).copy(name = name)
            saves[slot] = saved to snapshot
            return saved
        }
        override fun rename(token: String, slot: Int, revision: Int, name: String): CloudSaveSlot {
            val (saved, snapshot) = saves.getValue(slot)
            if (saved.revision != revision) throw ApiException(409, "Changed")
            return saved.copy(name = name, revision = revision + 1).also { saves[slot] = it to snapshot }
        }
    }

    private class Harness(localRecords: Int, saves: Map<Int, Pair<CloudSaveSlot, LedgerSnapshot>> = emptyMap(), make: (String) -> LedgerRecord) {
        val events = mutableListOf<String>()
        val local = FakeLocal(LedgerSnapshot("CAD", (1..localRecords).map { make("local-$it") }), events)
        val remote = FakeRemote(saves.toMutableMap(), events)
        val cloud = CloudSaves(local, remote, "Pixel 9").apply { onSessionExpired = { events += "expired" } }
    }

    private fun harness(localRecords: Int, vararg saves: Pair<CloudSaveSlot, Int>) = Harness(localRecords,
        saves.associate { (save, records) -> save.slot to (save to LedgerSnapshot("CAD", (1..records).map { record("cloud-${save.slot}-$it") })) },
        ::record)

    @Test fun `rows always show three fixed slots in order`() {
        val rows = LedgerCloudSaves.rows(listOf(slot(3), slot(1)))
        assertEquals(listOf(1, 2, 3), rows.map { it.first })
        assertEquals(listOf(1, null, 3), rows.map { it.second?.slot })
    }

    @Test fun `naming waits for confirmation and rename preserves snapshot and save time`() = runBlocking {
        val h = harness(2)
        h.cloud.refresh("token")
        h.cloud.pickSaveSlot("token", 1, CloudSaveGoal.Manual)
        assertTrue(h.events.isEmpty())
        assertTrue(h.cloud.dialog is CloudSaveDialog.NameSave)
        h.cloud.confirmName("token", "  Trip  ")
        val original = h.cloud.slots!!.single()
        assertEquals("Trip", original.name)
        assertEquals("Trip", cloudSaveTitle(original))
        val snapshot = h.remote.saves.getValue(1).second
        h.cloud.show(CloudSaveDialog.Rename(original))
        h.cloud.rename("token", "Summer")
        assertEquals(original.copy(name = "Summer", revision = 2), h.cloud.slots!!.single())
        assertEquals(snapshot, h.remote.saves.getValue(1).second)
        assertEquals("Save 2", cloudSaveTitle(slot(2)))
    }

    @Test fun `sign-in offers to save local records, or to load into an empty device`() {
        assertEquals(CloudSaveDialog.OfferSave(4), LedgerCloudSaves.signInOffer(4, listOf(slot(1))))
        assertEquals(CloudSaveDialog.OfferLoad(2), LedgerCloudSaves.signInOffer(0, listOf(slot(1), slot(2))))
        assertNull(LedgerCloudSaves.signInOffer(0, emptyList()))
    }

    @Test fun `a consistent snapshot validates`() {
        LedgerCloudSaves.validate(LedgerSnapshot("CAD", listOf(record("a"), record("b"))), today)
        LedgerCloudSaves.validate(LedgerSnapshot("EUR", emptyList()), today)
    }

    @Test fun `a damaged snapshot is rejected before anything is replaced`() {
        val damaged = listOf(
            LedgerSnapshot("XYZ", emptyList()),
            LedgerSnapshot("CAD", listOf(record("a"), record("a"))),
            LedgerSnapshot("CAD", listOf(record(""))),
            LedgerSnapshot("CAD", listOf(record("a", base = "USD"))),
            LedgerSnapshot("CAD", listOf(record("a", converted = 1699))),
            LedgerSnapshot("CAD", listOf(record("a", amountMinor = 1251))),
            LedgerSnapshot("CAD", listOf(record("a", amount = "12.505"))),
            LedgerSnapshot("CAD", List(LedgerCloudSaves.MAX_RECORDS + 1) { record("r$it") }),
        )
        damaged.forEach { snapshot -> assertThrows(LedgerException::class.java) { LedgerCloudSaves.validate(snapshot, today) } }
    }

    @Test fun `saving to an empty slot needs no overwrite confirmation`() = runBlocking {
        val h = harness(2)
        h.cloud.open("token", CloudSaveDialog.PickSaveSlot(CloudSaveGoal.Manual))
        assertEquals(CloudSaveDialog.PickSaveSlot(CloudSaveGoal.Manual), h.cloud.dialog)
        h.cloud.pickSaveSlot("token", 2, CloudSaveGoal.Manual)
        h.cloud.confirmName("token", "Save 2")
        assertEquals(listOf("save:2:replaces=null"), h.events)
        assertEquals(listOf(2), h.cloud.slots?.map { it.slot })
        assertEquals(CloudSaveDialog.Message("Saved 2 transactions to Save 2.", false), h.cloud.dialog)
    }

    @Test fun `an occupied slot is overwritten only after confirmation, at its listed revision`() = runBlocking {
        val h = harness(3, slot(1, revision = 4) to 1)
        h.cloud.refresh("token")
        h.cloud.pickSaveSlot("token", 1, CloudSaveGoal.Manual)
        assertTrue(h.cloud.dialog is CloudSaveDialog.ConfirmOverwrite)
        assertTrue(h.events.isEmpty())
        h.cloud.confirmOverwrite("token")
        h.cloud.confirmName("token", "Save 1")
        assertEquals(listOf("save:1:replaces=4"), h.events)
        assertEquals(3, h.remote.saves.getValue(1).second.records.size)
    }

    @Test fun `a slot changed by another device is not overwritten and the slots are re-listed`() = runBlocking {
        val h = harness(1, slot(1, revision = 1) to 1)
        h.cloud.refresh("token")
        h.remote.saves[1] = slot(1, revision = 2, count = 9) to LedgerSnapshot("CAD", emptyList())
        h.cloud.pickSaveSlot("token", 1, CloudSaveGoal.Manual)
        h.cloud.confirmOverwrite("token")
        h.cloud.confirmName("token", "Save 1")
        val message = h.cloud.dialog as CloudSaveDialog.Message
        assertTrue(message.isError)
        assertEquals(2, h.cloud.slots?.single()?.revision)
        assertEquals(9, h.cloud.slots?.single()?.recordCount)
    }

    @Test fun `loading replaces the whole device ledger after confirmation`() = runBlocking {
        val h = harness(5, slot(2, count = 2) to 2)
        h.cloud.refresh("token")
        h.cloud.show(CloudSaveDialog.ConfirmLoad(h.cloud.slots!!.single()))
        assertTrue(h.cloud.dialog is CloudSaveDialog.ConfirmLoad)
        h.cloud.confirmLoad("token")
        assertEquals(listOf("load:2", "replace:2"), h.events)
        assertEquals(listOf("cloud-2-1", "cloud-2-2"), h.local.ledger.records.map { it.id })
    }

    @Test fun `a damaged cloud save leaves the device ledger untouched`() = runBlocking {
        val h = harness(5, slot(2) to 0)
        h.remote.saves[2] = slot(2) to LedgerSnapshot("CAD", listOf(record("x", converted = 1)))
        h.cloud.refresh("token")
        h.cloud.show(CloudSaveDialog.ConfirmLoad(h.cloud.slots!!.single()))
        h.cloud.confirmLoad("token")
        assertEquals(listOf("load:2"), h.events)
        assertEquals(5, h.local.ledger.records.size)
        assertEquals(CloudSaveDialog.Message("This cloud save is damaged and was not loaded.", true), h.cloud.dialog)
    }

    @Test fun `the current ledger can be saved to another slot before loading`() = runBlocking {
        val h = harness(3, slot(1, count = 1) to 1)
        h.cloud.refresh("token")
        h.cloud.show(CloudSaveDialog.ConfirmLoad(h.cloud.slots!!.single()))
        h.cloud.saveBeforeLoad()
        assertEquals(CloudSaveDialog.PickSaveSlot(CloudSaveGoal.BeforeLoad, excludedSlot = 1), h.cloud.dialog)
        h.cloud.pickSaveSlot("token", 3, CloudSaveGoal.BeforeLoad)
        h.cloud.confirmName("token", "Save 3")
        assertEquals(listOf("save:3:replaces=null", "load:1", "replace:1"), h.events)
        assertEquals(3, h.remote.saves.getValue(3).second.records.size)
        assertEquals(1, h.local.ledger.records.size)
    }

    @Test fun `a failed save before loading does not load`() = runBlocking {
        val h = harness(3, slot(1) to 1)
        h.cloud.refresh("token")
        h.cloud.show(CloudSaveDialog.ConfirmLoad(h.cloud.slots!!.single()))
        h.cloud.saveBeforeLoad()
        h.remote.failure = IOException("Can't reach the server. Check your connection and try again.")
        h.cloud.pickSaveSlot("token", 2, CloudSaveGoal.BeforeLoad)
        h.cloud.confirmName("token", "Save 2")
        assertTrue(h.events.isEmpty())
        assertEquals(3, h.local.ledger.records.size)
        assertTrue((h.cloud.dialog as CloudSaveDialog.Message).isError)
    }

    @Test fun `an empty device logs out with the ordinary confirmation`() {
        val h = harness(0)
        assertFalse(h.cloud.askLogout(0) { h.events += "logout" })
        assertNull(h.cloud.dialog)
    }

    @Test fun `save then log out keeps the device ledger and logs out only after the save`() = runBlocking {
        val h = harness(2)
        assertTrue(h.cloud.askLogout(2) { h.events += "logout" })
        assertEquals(CloudSaveDialog.LogoutChoice(2), h.cloud.dialog)
        h.cloud.open("token", CloudSaveDialog.PickSaveSlot(CloudSaveGoal.BeforeLogout))
        assertEquals(CloudSaveDialog.PickSaveSlot(CloudSaveGoal.BeforeLogout), h.cloud.dialog)
        h.cloud.pickSaveSlot("token", 1, CloudSaveGoal.BeforeLogout)
        h.cloud.confirmName("token", "Save 1")
        assertEquals(listOf("save:1:replaces=null", "logout"), h.events)
        assertEquals(2, h.local.ledger.records.size)
        assertNull(h.cloud.dialog)
    }

    @Test fun `a failed save keeps the user signed in`() = runBlocking {
        val h = harness(2)
        h.cloud.askLogout(2) { h.events += "logout" }
        h.cloud.open("token", CloudSaveDialog.PickSaveSlot(CloudSaveGoal.BeforeLogout))
        h.remote.failure = IOException("offline")
        h.cloud.pickSaveSlot("token", 1, CloudSaveGoal.BeforeLogout)
        h.cloud.confirmName("token", "Save 1")
        assertFalse("logout" in h.events)
        assertEquals(CloudSaveDialog.Message("offline", true), h.cloud.dialog)
    }

    @Test fun `delete then log out clears only the device after a second confirmation`() = runBlocking {
        val h = harness(2, slot(1) to 1)
        h.cloud.askLogout(2) { h.events += "logout" }
        h.cloud.clearAndLogout("token")
        assertTrue("clearing needs its own confirmation", h.events.isEmpty())
        h.cloud.show(CloudSaveDialog.ConfirmClearAndLogout(2))
        assertEquals(CloudSaveDialog.ConfirmClearAndLogout(2), h.cloud.dialog)
        h.cloud.clearAndLogout("token")
        assertEquals(listOf("clear", "logout"), h.events)
        assertTrue(h.local.ledger.records.isEmpty())
        assertEquals(setOf(1), h.remote.saves.keys)
    }

    @Test fun `deleting a save uses its listed revision and leaves the device ledger alone`() = runBlocking {
        val h = harness(2, slot(1, revision = 3) to 1, slot(2) to 1)
        h.cloud.refresh("token")
        h.cloud.show(CloudSaveDialog.ConfirmDelete(h.cloud.slots!!.first()))
        h.cloud.confirmDelete("token")
        assertEquals(listOf("delete:1:3"), h.events)
        assertEquals(listOf(2), h.cloud.slots?.map { it.slot })
        assertEquals(2, h.local.ledger.records.size)
    }

    @Test fun `an expired session ends the flow and signs out`() = runBlocking {
        val h = harness(1)
        h.remote.failure = ApiException(401, "Your session has expired. Please sign in again.")
        h.cloud.open("token", CloudSaveDialog.PickSaveSlot(CloudSaveGoal.Manual))
        h.cloud.pickSaveSlot("token", 1, CloudSaveGoal.Manual)
        h.cloud.confirmName("token", "Save 1")
        assertEquals(listOf("expired"), h.events)
        assertNull(h.cloud.dialog)
        assertNull(h.cloud.slots)
    }

    @Test fun `sign-in offers follow the device and account state, and skip when offline`() = runBlocking {
        val withLocal = harness(3)
        withLocal.cloud.offerAfterSignIn("token", 3)
        assertEquals(CloudSaveDialog.OfferSave(3), withLocal.cloud.dialog)
        val emptyDevice = harness(0, slot(2) to 1)
        emptyDevice.cloud.offerAfterSignIn("token", 0)
        assertEquals(CloudSaveDialog.OfferLoad(1), emptyDevice.cloud.dialog)
        val offline = object : LedgerSavesRemote by FakeRemote(mutableMapOf(), mutableListOf()) {
            override fun list(token: String): List<CloudSaveSlot> = throw IOException("offline")
        }
        val cloud = CloudSaves(FakeLocal(LedgerSnapshot("CAD", emptyList()), mutableListOf()), offline, "Pixel 9")
        cloud.offerAfterSignIn("token", 4)
        assertNull(cloud.dialog)
    }
}
