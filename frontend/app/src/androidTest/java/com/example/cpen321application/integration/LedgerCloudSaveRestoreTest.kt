package com.example.cpen321application.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.LedgerException
import com.example.cpen321application.LedgerFx
import com.example.cpen321application.LedgerImportRow
import com.example.cpen321application.LedgerInput
import com.example.cpen321application.LedgerRate
import com.example.cpen321application.LedgerRecord
import com.example.cpen321application.LedgerRemote
import com.example.cpen321application.LedgerRepository
import com.example.cpen321application.LedgerSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Loading a cloud save against real SQLite: all or nothing, and it survives a reopen. */
@RunWith(AndroidJUnit4::class)
class LedgerCloudSaveRestoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repository: LedgerRepository
    private val offline = object : LedgerRemote {
        override fun quote(from: String, to: String, date: String): LedgerRate = throw LedgerException("offline")
        override fun extract(fileName: String, mimeType: String, bytes: ByteArray, defaultCurrency: String): List<LedgerImportRow> =
            throw LedgerException("offline")
    }

    private fun saved(id: String, converted: Long = 1700) = LedgerRecord(id,
        LedgerInput("expense", "12.50", "USD", "2026-10-01", "Corner Cafe", "Food"), 1250,
        LedgerFx("EUR", "1.36", "2026-10-01", "Frankfurter (ECB)", converted),
        createdAt = "2026-10-01T12:00:00Z", updatedAt = "2026-10-01T12:00:00Z")

    @Before
    fun createIsolatedLedger() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        databaseName = "ledger-cloud-save-${UUID.randomUUID()}.db"
        repository = LedgerRepository(context, offline, databaseName)
        repository.save(LedgerInput(amount = "8.00", currency = "CAD", date = "2026-09-01", merchant = "Bakery", category = "Food"),
            requestId = UUID.randomUUID().toString())
    }

    @After
    fun removeIsolatedLedger() {
        repository.close()
        assertTrue(context.deleteDatabase(databaseName))
    }

    @Test fun restoreReplacesEveryRecordAndTheReportingCurrency() {
        repository.restore(LedgerSnapshot("EUR", listOf(saved("a"), saved("b"))))
        repository.close()
        repository = LedgerRepository(context, offline, databaseName)
        assertEquals(listOf("a", "b"), repository.load().map { it.id }.sorted())
        assertEquals("EUR", repository.baseCurrency())
        assertEquals(3400L, repository.summary(repository.load()).expenseMinor)
    }

    @Test fun aDamagedSaveLeavesTheCurrentLedgerIntact() {
        val before = repository.snapshot()
        assertThrows(LedgerException::class.java) { repository.restore(LedgerSnapshot("EUR", listOf(saved("a"), saved("b", converted = 1)))) }
        assertEquals(before, repository.snapshot())
    }

    @Test fun aRestoredGroupImportCannotBeImportedTwice() {
        val share = LedgerInput(amount = "20.00", currency = "CAD", date = "2026-09-02", merchant = "Ski trip", category = "Entertainment")
        fun import() = repository.groupImports.importShare("account-1", "group-1", "expense-1", share, null, "Ski trip")
        assertTrue(import())
        repository.restore(repository.snapshot())
        assertEquals(false, import())
        repository.clear()
        assertTrue("an emptied ledger may import the share again", import())
    }

    @Test fun clearRemovesTransactionsButKeepsTheReportingCurrency() {
        repository.clear()
        assertTrue(repository.load().isEmpty())
        assertEquals("CAD", repository.baseCurrency())
    }
}
