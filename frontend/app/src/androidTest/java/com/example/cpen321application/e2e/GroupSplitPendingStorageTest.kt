package com.example.cpen321application.e2e

import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.GroupSplitPendingStorage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID

class GroupSplitPendingStorageTest {
    @Test fun uncertainOperationSurvivesReopeningAndIsIsolatedByAccountAndGroup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "test-${UUID.randomUUID()}"
        val group = "group-${UUID.randomUUID()}"
        val storage = GroupSplitPendingStorage(context, account, group)
        val saved = JSONObject().put("operationKey", "op-retained-for-retry").put("sessionId", "split-1")
            .put("type", "finalize").put("version", 3)
        try {
            storage.saveBeforeRequest(saved)
            val reopened = GroupSplitPendingStorage(context, account, group).load()
            assertEquals("op-retained-for-retry", reopened.getString("operationKey"))
            assertEquals(3, reopened.getInt("version"))
            assertFalse(GroupSplitPendingStorage(context, "$account-other", group).load().has("operationKey"))
            assertFalse(GroupSplitPendingStorage(context, account, "$group-other").load().has("operationKey"))
            storage.clear()
            assertFalse(GroupSplitPendingStorage(context, account, group).load().has("operationKey"))
        } finally { storage.clear() }
    }
}
