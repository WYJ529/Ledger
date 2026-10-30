package com.example.cpen321application

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSplitStateTest {
    private val initial = GroupSplitSession("split-1", "group-1", "Dinner", 100, "CAD", "owner",
        listOf(SplitItemUnit("unit-1", "Soup", 100, null)), "open", 0, 1, null)

    @Test fun `delayed snapshots never roll back a committed write or finalization fence`() {
        val state = GroupSplitState("group-1")
        state.install(GroupSplitSnapshot("group-1", listOf(initial)))
        val fenced = initial.copy(status = "finalizing", version = 1, sequence = 2)
        state.merge(fenced, select = true)
        state.install(GroupSplitSnapshot("group-1", listOf(initial.copy(version = 1, sequence = 2))))
        assertEquals(fenced, state.activeSplit)
        state.install(GroupSplitSnapshot("group-1", listOf(initial)))
        assertEquals(fenced, state.snapshot!!.splitSessions.single())
    }

    @Test fun `buffered sequence gap resolves after snapshot and never crosses sessions`() {
        val state = GroupSplitState("group-1")
        state.install(GroupSplitSnapshot("group-1", listOf(initial)))
        val ahead = initial.copy(version = 3, sequence = 4)
        state.buffer(ahead)
        assertFalse(state.drainEvents())
        state.install(GroupSplitSnapshot("group-1", listOf(initial.copy(version = 2, sequence = 3))))
        assertTrue(state.drainEvents())
        assertEquals(ahead, state.activeSplit)
        assertFalse(state.installUpdate(initial.copy(id = "another-split", sequence = 5)))
        assertEquals(ahead, state.activeSplit)
    }

    @Test fun `unrelated groups and stale duplicates leave canonical state unchanged`() {
        val state = GroupSplitState("group-1")
        state.merge(initial)
        assertTrue(state.installUpdate(initial))
        state.merge(initial.copy(groupId = "other", sequence = 10))
        state.install(GroupSplitSnapshot("other", emptyList()))
        state.buffer(initial.copy(groupId = "other"))
        assertTrue(state.bufferedLive.isEmpty())
        assertEquals(initial, state.activeSplit)
        state.discardGroup()
        assertEquals(null, state.activeSplit)
        assertEquals(null, state.snapshot)
    }

    @Test fun `disconnect and recovery both keep mutations read only`() {
        val state = GroupSplitState("group-1")
        state.stale = false
        state.liveConnected = true
        assertTrue(state.canInteract)
        state.recoveringLive = true
        assertFalse(state.canInteract)
        state.recoveringLive = false
        state.disconnect()
        assertFalse(state.canInteract)
        assertTrue(state.stale)
    }

    @Test fun `receipt validation expands quantities and edits revoke review`() {
        val form = GroupSplitForm()
        form.description = "Dinner"
        form.payer = "owner"
        form.receiptTotal = "3.00"
        form.update(0, SplitReceiptRow("Soup", "2", "1.50"))
        form.reviewed = true
        assertTrue(form.valid(listOf(GroupMember("owner", "Owner"))))
        assertEquals(listOf(150L, 150L), form.expandedUnits.map { it.amountCents })
        form.update(0, SplitReceiptRow("Soup", "3", "1.50"))
        assertFalse(form.reviewed)
        form.reviewed = true
        assertFalse(form.valid(listOf(GroupMember("owner", "Owner"))))
    }

    @Test fun `form saver restores all editable state across activity recreation`() {
        val original = GroupSplitForm().apply {
            description = "Dinner"; payer = "owner"; receiptTotal = "3.00"
            items = listOf(SplitReceiptRow("Soup", "2", "1.50"))
            reviewed = true; startingAnother = true
        }
        val scope = SaverScope { true }
        val saved = with(GroupSplitForm.StateSaver) { scope.save(original) }!!
        val restored = GroupSplitForm.StateSaver.restore(saved)!!
        assertEquals(original.description, restored.description)
        assertEquals(original.payer, restored.payer)
        assertEquals(original.receiptTotal, restored.receiptTotal)
        assertEquals(original.items, restored.items)
        assertTrue(restored.reviewed)
        assertTrue(restored.startingAnother)
    }

    @Test fun `receipt items in won are whole amounts`() {
        val form = GroupSplitForm()
        form.useCurrencyDigits(0)
        form.description = "Dinner"
        form.payer = "owner"
        form.receiptTotal = "30000"
        form.update(0, SplitReceiptRow("Bibimbap", "2", "15000"))
        form.reviewed = true
        assertTrue(form.valid(listOf(GroupMember("owner", "Owner"))))
        assertEquals(listOf(15000L, 15000L), form.expandedUnits.map { it.amountCents })
        form.update(0, SplitReceiptRow("Bibimbap", "2", "15000.50"))
        form.reviewed = true
        assertFalse(form.valid(listOf(GroupMember("owner", "Owner"))))
    }
}
