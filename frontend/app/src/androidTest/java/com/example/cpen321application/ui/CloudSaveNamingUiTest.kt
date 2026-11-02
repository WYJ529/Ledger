package com.example.cpen321application.ui

import com.example.cpen321application.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudSaveNamingUiTest {
    @get:Rule val compose = createComposeRule()
    private val session = Session("test-token", Long.MAX_VALUE,
        Account("owner", "owner@example.com", "Owner", listOf("password"), "2026-10-30"))
    private val existing = CloudSaveSlot(1, 3, "2026-10-30T12:00:00Z", "Phone", "CAD", 0, "Trip")
    private val local = object : LocalLedger {
        override suspend fun snapshot() = LedgerSnapshot("CAD", emptyList())
        override suspend fun replace(snapshot: LedgerSnapshot) = error("Rename must not replace the ledger")
        override suspend fun clear() = error("Rename must not clear the ledger")
    }
    private inner class Remote : LedgerSavesRemote {
        @Volatile var renamed: String? = null
        override fun list(token: String) = listOf(existing)
        override fun load(token: String, slot: Int) = error("Unexpected load")
        override fun save(token: String, slot: Int, snapshot: LedgerSnapshot, deviceName: String, replaces: Int?) = error("Unexpected save")
        override fun delete(token: String, slot: Int, revision: Int) = error("Unexpected delete")
        override fun rename(token: String, slot: Int, revision: Int, name: String): CloudSaveSlot {
            assertEquals(1, slot)
            assertEquals(3, revision)
            renamed = name
            return existing.copy(name = name, revision = revision + 1)
        }
    }

    @Test fun invalidNamesRemainEditableAndValidRenameSendsTrimmedNameAndRevision() = runBlocking {
        val remote = Remote()
        val cloud = CloudSaves(local, remote, "Phone")
        cloud.refresh(session.token)
        cloud.show(CloudSaveDialog.Rename(existing))
        compose.setContent { MaterialTheme { CloudSaveDialogs(cloud, session, 0) } }
        compose.onNodeWithText("Trip").assertExists()
        compose.onNodeWithText("Save name").performTextReplacement(" ")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Enter a save name of 1–40 characters.").assertExists()
        val tooLong = "x".repeat(41)
        compose.onNodeWithText("Save name").performTextReplacement(tooLong)
        compose.onNodeWithText(tooLong).assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertNull(remote.renamed)
        compose.onNodeWithText("Save name").performTextReplacement("  Before import  ")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { remote.renamed != null && !cloud.working }
        assertEquals("Before import", remote.renamed)
        assertEquals("Before import", cloud.slots!!.single().name)
        assertEquals(existing.savedAt, cloud.slots!!.single().savedAt)
        compose.onNodeWithText("Rename save").assertDoesNotExist()
    }

    @Test fun unnamedSlotStartsWithFallbackAndCancelDoesNotSave() {
        val remote = Remote()
        val cloud = CloudSaves(local, remote, "Phone")
        cloud.show(CloudSaveDialog.NameSave(2, CloudSaveGoal.Manual, null))
        compose.setContent { MaterialTheme { CloudSaveDialogs(cloud, session, 0) } }
        compose.onNodeWithText("Save 2").assertExists()
        compose.onNodeWithText("Save name").performTextReplacement("x".repeat(40))
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Name this save").assertDoesNotExist()
        assertNull(remote.renamed)
        assertNull(cloud.dialog)
    }
}
