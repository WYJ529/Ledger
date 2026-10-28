package com.example.cpen321application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GroupSplitRecoveryTest {
    private val complete = SplitOperation("original-key", "split-1", "group-1", "claim", 1, 2, "complete")

    @Test fun `all mutation kinds stay confirmed when the following refresh fails`() = runBlocking {
        for (kind in listOf("claim", "unclaim", "finalize")) {
            val events = mutableListOf<String>()
            val refreshFailure = IOException("offline after acceptance")
            runSplitMutation(
                send = { kind },
                accepted = { events += "accepted:$it" },
                rejected = { events += "rejected" },
                uncertain = { events += "uncertain" },
                refresh = { throw refreshFailure },
                refreshFailed = { assertSame(refreshFailure, it); events += "refresh failed" },
            )
            assertEquals(listOf("accepted:$kind", "refresh failed"), events)
        }
    }

    @Test fun `rejected mutation clears pending once even if canonical refresh fails`() = runBlocking {
        val events = mutableListOf<String>()
        runSplitMutation<String>(
            send = { throw ApiException(409, "already claimed") },
            accepted = { events += "accepted" }, rejected = { events += "rejected:${it.status}" },
            uncertain = { events += "uncertain" }, refresh = { throw IOException("offline") },
            refreshFailed = { events += "stale" },
        )
        assertEquals(listOf("rejected:409", "stale"), events)
    }

    @Test fun `lost response retains exact operation for a successful retry`() = runBlocking {
        for (kind in listOf("claim", "unclaim", "finalize")) {
            val original = PendingSplitMutation("stable-key", "split-1", kind, 7, "unit-1", kind)
            var pending: PendingSplitMutation? = original
            val attempts = mutableListOf<PendingSplitMutation>()
            var refreshes = 0
            repeat(2) { attempt ->
                val saved = pending!!
                runSplitMutation(
                    send = { attempts += saved; if (attempt == 0) throw IOException("lost response") else "accepted" },
                    accepted = { pending = null }, rejected = { throw AssertionError("unexpected rejection") },
                    uncertain = { assertSame(original, pending) }, refresh = { refreshes++ },
                    refreshFailed = { throw AssertionError("unexpected refresh failure") },
                )
            }
            assertEquals(listOf(original, original), attempts)
            assertEquals(null, pending)
            assertEquals(1, refreshes)
        }
    }

    @Test fun `accepted operation lookup keeps key until canonical refresh succeeds`() = runBlocking {
        var cleared = false
        val failed = splitRequest {
            recoverPendingSplit({ complete }, { throw IOException("refresh failed") }, { cleared = true })
        }
        assertTrue(failed is SplitRequestResult.Failure)
        assertFalse(cleared)
        assertTrue(recoverPendingSplit({ complete }, {}, { cleared = true }))
        assertTrue(cleared)
    }

    @Test fun `unknown and incomplete operations preserve original pending request`() = runBlocking {
        var refreshes = 0
        var clears = 0
        assertFalse(recoverPendingSplit({ throw ApiException(404, "unknown") }, { refreshes++ }, { clears++ }))
        assertFalse(recoverPendingSplit({ complete.copy(state = "pending") }, { refreshes++ }, { clears++ }))
        assertEquals(0, refreshes)
        assertEquals(0, clears)
    }

    @Test fun `authentication failure retains pending action and propagates to caller handling`() = runBlocking {
        var received: Exception? = null
        runSplitMutation<String>(
            send = { throw ApiException(401, "expired") }, accepted = {},
            rejected = { throw AssertionError("must retain pending") }, uncertain = { received = it },
            refresh = { throw AssertionError("must sign in before refresh") }, refreshFailed = {},
        )
        assertEquals(401, (received as ApiException).status)
    }

    @Test fun `cancellation and programming errors are never converted to recoverable failures`() {
        assertThrows(CancellationException::class.java) { runBlocking { splitRequest { throw CancellationException("cancelled") } } }
        assertThrows(IllegalStateException::class.java) { runBlocking { splitRequest { error("programming defect") } } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            runSplitMutation(send = { "accepted" }, accepted = {}, rejected = {}, uncertain = {},
                refresh = { throw IllegalArgumentException("broken config") }, refreshFailed = {})
        } }
    }
}
