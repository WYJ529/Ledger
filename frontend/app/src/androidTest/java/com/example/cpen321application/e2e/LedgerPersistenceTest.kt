package com.example.cpen321application.e2e

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.LedgerException
import com.example.cpen321application.LedgerImportRow
import com.example.cpen321application.LedgerInput
import com.example.cpen321application.LedgerRate
import com.example.cpen321application.LedgerRemote
import com.example.cpen321application.LedgerRepository
import org.junit.After
import org.junit.Assert.assertEquals

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Android SQLite integration tests; only the external network boundary is replaced. */
@RunWith(AndroidJUnit4::class)
class LedgerPersistenceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var remote: ControlledRemote
    private lateinit var repository: LedgerRepository

    @Before
    fun createIsolatedLedger() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        databaseName = "ledger-persistence-${UUID.randomUUID()}.db"
        remote = ControlledRemote()
        repository = LedgerRepository(context, remote, databaseName)
    }

    @After
    fun removeIsolatedLedger() {
        repository.close()
        assertTrue("The isolated test database should be removed", context.deleteDatabase(databaseName))
    }

    private fun requestId(): String = UUID.randomUUID().toString()

    private fun input(
        merchant: String = "Local cafe",
        amount: String = "12.50",
        currency: String = "CAD",
        date: String = "2025-01-06"
    ): LedgerInput = LedgerInput(
        amount = amount, currency = currency, date = date, merchant = merchant,
        category = "Food", notes = "A receipt checked by the user"
    )

    private fun reopen() {
        repository.close()
        repository = LedgerRepository(context, remote, databaseName)
    }

    private fun expectLedgerFailure(messagePart: String, action: () -> Unit) {
        try {
            action()
            fail("Expected a ledger error containing: $messagePart")
        } catch (error: LedgerException) {
            assertTrue("Actual error: ${error.message}", error.message.orEmpty().contains(messagePart))
        }
    }

    @Test
    fun transactionsAndHistoricalRateSurviveClosingAndReopeningOffline() {
        remote.effectiveDate = "2025-01-03"
        val foreign = repository.save(input(currency = "USD", date = "2025-01-05"), requestId = requestId())
        assertEquals(1250L, foreign.amountMinor)
        assertEquals(1750L, foreign.fx.convertedMinor)
        assertEquals("USD", foreign.input.currency)
        assertEquals("CAD", foreign.fx.baseCurrency)
        assertEquals("2025-01-03", foreign.fx.rateDate)
        assertEquals("1.4", foreign.fx.rate)

        reopen()
        remote.failAll = true
        assertEquals(listOf(foreign), repository.load())
        assertEquals("CAD", repository.baseCurrency())
        // Local CAD entries and the saved summary must remain usable with no network.
        repository.save(input(merchant = "Bus fare", amount = "3.20"), requestId = requestId())
        val summary = repository.summary(repository.load())
        assertEquals(2070L, summary.expenseMinor)
        assertEquals(-2070L, summary.netMinor)
        assertEquals(2, summary.count)
        assertEquals(1, remote.calls.size)
    }

    @Test
    fun metadataAndAmountEditsReuseTheStoredExchangeRateWhenOffline() {
        remote.effectiveDate = "2025-01-03"
        val initial = repository.save(input(currency = "USD", date = "2025-01-05"), requestId = requestId())
        // Remove the optional cache to prove the saved transaction owns its rate snapshot.
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.delete("ledger_rates", null, null)
        }
        reopen()
        remote.failAll = true
        val edited = repository.save(initial.input.copy(notes = "Corrected note"), initial.id, initial.revision, requestId())
        assertEquals(initial.fx, edited.fx)
        val corrected = repository.save(edited.input.copy(amount = "10.00"), edited.id, edited.revision, requestId())
        assertEquals(initial.fx.rate, corrected.fx.rate)
        assertEquals(initial.fx.rateDate, corrected.fx.rateDate)
        assertEquals(1400L, corrected.fx.convertedMinor)
        assertEquals(1, remote.calls.size)
        reopen()
        assertEquals(listOf(corrected), repository.load())
    }

    @Test
    fun invalidSelectedRowPreventsEveryRowInTheImportFromBeingSaved() {
        val rows = listOf(
            LedgerImportRow("valid", input(merchant = "Valid row")),
            LedgerImportRow("invalid", input(merchant = "Needs correction", amount = "0"))
        )
        expectLedgerFailure("highlighted fields") {
            repository.commitImport(rows, "csv", "statement.csv", requestId())
        }
        assertTrue(repository.load().isEmpty())
        reopen()
        assertTrue(repository.load().isEmpty())
        assertEquals(0, remote.calls.size)
    }

    @Test
    fun duplicatesRequireAnExplicitKeepChoiceAndSkippedRowsAreNotInserted() {
        val original = repository.save(input(), requestId = requestId())
        val suspected = LedgerImportRow("duplicate", input())
        val unrelated = LedgerImportRow("new", input(merchant = "Different shop", amount = "8.00"))

        expectLedgerFailure("possible duplicates") {
            repository.commitImport(listOf(unrelated, suspected), "receipt", "receipt.jpg", requestId())
        }
        assertEquals(listOf(original), repository.load())

        assertEquals(1, repository.commitImport(
            listOf(unrelated, suspected.copy(selected = false)), "receipt", "receipt.jpg", requestId()
        ))
        assertEquals(2, repository.load().size)
        assertEquals(1, repository.commitImport(
            listOf(suspected.copy(acknowledgeDuplicate = true)), "receipt", "receipt.jpg", requestId()
        ))
        assertEquals(2, repository.load().count { it.input.merchant == original.input.merchant })

        val first = LedgerImportRow("same-batch-a", input(merchant = "A new bookstore", amount = "45.00"))
        val second = first.copy(rowId = "same-batch-b")
        expectLedgerFailure("possible duplicates") {
            repository.commitImport(listOf(first, second), "csv", "overlap.csv", requestId())
        }
        assertEquals(3, repository.load().size)
    }

    @Test
    fun successfulImportRetriesAfterReopenDoNotDuplicateRecordsOrAcceptChangedContent() {
        val rows = listOf(
            LedgerImportRow("a", input(merchant = "Groceries", amount = "25.00")),
            LedgerImportRow("b", input(merchant = "Transit", amount = "3.20"))
        )
        val request = requestId()
        assertEquals(2, repository.commitImport(rows, "csv", "statement.csv", request))
        val committed = repository.load()

        reopen()
        remote.failAll = true
        assertEquals(2, repository.commitImport(rows, "csv", "statement.csv", request))
        assertEquals(committed, repository.load())

        val changed = rows.mapIndexed { index, row ->
            if (index == 0) row.copy(input = row.input.copy(amount = "26.00")) else row
        }
        expectLedgerFailure("different content") {
            repository.commitImport(changed, "csv", "statement.csv", request)
        }
        assertEquals(committed, repository.load())
    }

    @Test
    fun failedQuoteLeavesCurrencyAndAllRecordsUnchangedThenRetryCommitsTogether() {
        repository.save(input(merchant = "Canada", amount = "10.00", date = "2025-01-08"), requestId = requestId())
        repository.save(input(merchant = "USA", amount = "20.00", currency = "USD", date = "2025-01-07"), requestId = requestId())
        repository.save(input(merchant = "Europe", amount = "30.00", currency = "EUR", date = "2025-01-06"), requestId = requestId())
        val before = repository.load()
        remote.failPair = "EUR" to "GBP"
        expectLedgerFailure("injected quote failure") { repository.setBaseCurrency("GBP") }
        assertEquals(listOf("CAD", "USD", "EUR"), remote.calls.filter { it.second == "GBP" }.map { it.first })
        assertEquals("CAD", repository.baseCurrency())
        assertEquals(before, repository.load())

        reopen()
        assertEquals("CAD", repository.baseCurrency())
        assertEquals(before, repository.load())
        remote.failPair = null
        repository.setBaseCurrency("GBP")
        reopen()
        val converted = repository.load()
        assertEquals("GBP", repository.baseCurrency())
        assertTrue(converted.all { it.fx.baseCurrency == "GBP" && it.revision == 2 })
        assertEquals(before.map { it.input }, converted.map { it.input })
        assertEquals(listOf(500L, 1200L, 2100L), converted.map { it.fx.convertedMinor })
        assertEquals(3800L, repository.summary(converted).expenseMinor)
    }

    @Test
    fun preferenceWriteFailureRollsBackAlreadyRevaluedRecords() {
        repository.save(input(amount = "10.00"), requestId = requestId())
        val before = repository.load()
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("""
                CREATE TRIGGER reject_reporting_currency BEFORE INSERT ON ledger_preferences
                BEGIN SELECT RAISE(ABORT, 'injected preference write failure'); END
            """.trimIndent())
        }
        expectLedgerFailure("local ledger") { repository.setBaseCurrency("USD") }
        reopen()
        assertEquals("CAD", repository.baseCurrency())
        assertEquals(before, repository.load())
        assertEquals(1000L, repository.summary(repository.load()).expenseMinor)
    }

    @Test
    fun midBatchSqliteFailureRollsBackRowsAndAllowsTheSameRequestToRetry() {
        // Trigger failure on the second insertion, after the first row was written.
        repository.load()
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("""
                CREATE TRIGGER reject_one_row BEFORE INSERT ON ledger_records
                WHEN NEW.data LIKE '%Blocked merchant%'
                BEGIN SELECT RAISE(ABORT, 'injected transaction write failure'); END
            """.trimIndent())
        }
        val rows = listOf(
            LedgerImportRow("first", input(merchant = "First merchant")),
            LedgerImportRow("second", input(merchant = "Blocked merchant", amount = "9.00"))
        )
        val request = requestId()
        expectLedgerFailure("local ledger") { repository.commitImport(rows, "csv", "statement.csv", request) }
        assertTrue(repository.load().isEmpty())
        reopen()
        assertTrue(repository.load().isEmpty())
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("DROP TRIGGER reject_one_row")
        }
        assertEquals(2, repository.commitImport(rows, "csv", "statement.csv", request))
        assertEquals(2, repository.load().size)
    }

    @Test
    fun staleEditAndDeleteCannotOverwriteTheLatestRevisionAcrossInstances() {
        val first = repository.save(input(), requestId = requestId())
        val editorRequest = requestId()
        LedgerRepository(context, remote, databaseName).use { anotherEditor ->
            val updated = anotherEditor.save(first.input.copy(amount = "15.00"), first.id, first.revision, editorRequest)
            assertEquals(2, updated.revision)
            expectLedgerFailure("changed") {
                repository.save(first.input.copy(amount = "99.00"), first.id, first.revision, requestId())
            }
            expectLedgerFailure("changed") { repository.delete(first.id, first.revision) }
            assertEquals(listOf(updated), repository.load())
        }
        reopen()
        val latest = repository.load().single()
        // A successful edit is also idempotent when its acknowledgement was lost.
        assertEquals(latest, repository.save(first.input.copy(amount = "15.00"), first.id, first.revision, editorRequest))
        repository.delete(latest.id, latest.revision)
        reopen()
        assertTrue(repository.load().isEmpty())

    }

    @Test fun batchDeleteRemovesOnlyChosenRowsAndPersistsAfterReopen() {
        val first = repository.save(input(merchant = "First"), requestId = requestId())
        val kept = repository.save(input(merchant = "Keep"), requestId = requestId())
        val last = repository.save(input(merchant = "Last"), requestId = requestId())
        assertEquals(2, repository.deleteMany(listOf(first, last)))
        reopen()
        assertEquals(listOf(kept), repository.load())
        expectLedgerFailure("at least one") { repository.deleteMany(emptyList()) }
        expectLedgerFailure("only once") { repository.deleteMany(listOf(kept, kept)) }
        assertEquals(listOf(kept), repository.load())
    }

    @Test fun staleBatchDeleteRollsBackAlreadyDeletedRows() {
        val first = repository.save(input(merchant = "First"), requestId = requestId())
        val stale = repository.save(input(merchant = "Stale"), requestId = requestId())
        repository.save(stale.input.copy(amount = "13.00"), stale.id, stale.revision, requestId())
        val before = repository.load()
        expectLedgerFailure("Nothing was deleted") { repository.deleteMany(listOf(first, stale)) }
        reopen()
        assertEquals(before, repository.load())
    }

    @Test fun sqliteFailureDuringBatchDeleteRollsBackTheWholeSelection() {
        val first = repository.save(input(merchant = "First"), requestId = requestId())
        val blocked = repository.save(input(merchant = "Blocked"), requestId = requestId())
        val before = repository.load()
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("""
                CREATE TRIGGER reject_bulk_delete BEFORE DELETE ON ledger_records
                WHEN OLD.data LIKE '%Blocked%'
                BEGIN SELECT RAISE(ABORT, 'injected delete failure'); END
            """.trimIndent())
        }
        expectLedgerFailure("local ledger") { repository.deleteMany(listOf(first, blocked)) }
        reopen()
        assertEquals(before, repository.load())
    }

    @Test fun historicPdfAndReceiptImportsRemainSavedAndDeduplicatedAfterReopen() {
        for ((index, name) in listOf("statement.pdf", "receipt.jpg").withIndex()) {
            val row = LedgerImportRow("row", input(merchant = "Document $index"),
                acknowledgeDuplicate = index == 1)
            repository.commitImport(listOf(row), "receipt", name, requestId())
        }
        reopen()
        val saved = repository.load()
        assertEquals(2, saved.size)
        assertTrue(saved.all { it.source == "receipt" && it.input.date == "2025-01-06" })
        assertTrue(repository.review(listOf(LedgerImportRow("again", saved.first().input)), saved).single().duplicates.isNotEmpty())
    }

    private class ControlledRemote : LedgerRemote {
        var failAll = false
        var failPair: Pair<String, String>? = null
        var effectiveDate: String? = null
        val calls = mutableListOf<Triple<String, String, String>>()
        private val rates = mapOf(
            ("USD" to "CAD") to "1.4", ("EUR" to "CAD") to "1.5",
            ("CAD" to "USD") to "0.7", ("CAD" to "GBP") to "0.5",
            ("USD" to "GBP") to "0.6", ("EUR" to "GBP") to "0.7"
        )

        override fun quote(from: String, to: String, date: String): LedgerRate {
            calls.add(Triple(from, to, date))
            if (failAll || failPair == (from to to)) throw LedgerException("injected quote failure")
            val rate = rates[from to to] ?: throw LedgerException("Unexpected test currency pair: $from/$to")
            return LedgerRate(rate, effectiveDate ?: date, "Persistence test rate")
        }

        override fun extract(fileName: String, mimeType: String, bytes: ByteArray, defaultCurrency: String): List<LedgerImportRow> {
            throw AssertionError("Persistence tests must not upload documents")
        }
    }
}
