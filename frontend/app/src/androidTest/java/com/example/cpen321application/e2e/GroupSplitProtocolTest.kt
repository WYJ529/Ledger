package com.example.cpen321application.e2e

import com.example.cpen321application.GroupSplitPendingState
import com.example.cpen321application.PendingSplitMutation
import com.example.cpen321application.SplitRequestResult
import com.example.cpen321application.parseSplitUpdate
import com.example.cpen321application.splitRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSplitProtocolTest {
    private fun envelope() = JSONObject("""{
        "event": {"groupId":"group-1","sessionId":"split-1","sequence":2},
        "session": {"id":"split-1","groupId":"group-1","description":"Dinner",
          "amountCents":100,"currency":"CAD","paidBy":"owner","units":[],
          "status":"open","version":1,"sequence":2,"finalizedExpenseId":null}
    }""")

    @Test fun parsesObjectAndStringEventsWithMatchingIdentityAndSequence() {
        val parsed = parseSplitUpdate(envelope(), "group-1")!!
        assertEquals("split-1", parsed.id)
        assertEquals(2L, parsed.sequence)
        assertEquals(parsed, parseSplitUpdate(envelope().toString(), "group-1"))
    }

    @Test fun ignoresMalformedAndUnrelatedEvents() {
        for (raw in listOf(null, 42, "{", JSONObject(), envelope().put("session", JSONObject()))) {
            assertNull(parseSplitUpdate(raw, "group-1"))
        }
        assertNull(parseSplitUpdate(envelope(), "other"))
        for ((field, value) in listOf("groupId" to "other", "sessionId" to "wrong", "sequence" to 3)) {
            val invalid = envelope()
            invalid.getJSONObject("event").put(field, value)
            assertNull(parseSplitUpdate(invalid, "group-1"))
        }
    }

    @Test fun preservesOriginalMutationPayloadAcrossSerializationAndExistingStorageFormat() {
        val operation = PendingSplitMutation("original-key", "split-1", "claim", 3, "unit-1", "unclaim")
        val original = GroupSplitPendingState("request-1", "saved-payload", operation)
        val restored = GroupSplitPendingState.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original.operation, restored.operation)
        assertEquals(original.startRequestId, restored.startRequestId)
        assertEquals(original.startPayload, restored.startPayload)
        restored.clear()
        assertTrue(restored.isEmpty)
    }

    @Test fun malformedResponseIsAnExpectedRequestFailure() = runBlocking {
        val result = splitRequest { JSONObject("{") }
        assertTrue(result is SplitRequestResult.Failure)
        assertTrue((result as SplitRequestResult.Failure).error is JSONException)
    }
}
