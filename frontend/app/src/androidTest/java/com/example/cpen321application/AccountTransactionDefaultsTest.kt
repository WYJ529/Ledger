package com.example.cpen321application

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Exercises persisted account defaults through the production new-transaction path. */
class AccountTransactionDefaultsTest {
    @get:Rule val compose = createComposeRule()
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val storage = SessionStorage(application)
    private val models = ViewModelStore()
    private var previousSession: Session? = null
    private lateinit var model: LedgerViewModel
    private val account = Account("defaults-${UUID.randomUUID()}", "defaults@example.com", "Defaults Test",
        emptyList(), "2026-10-28T00:00:00Z")

    @Before fun setUp() {
        previousSession = storage.load()
        compose.runOnIdle {
            model = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(application))[LedgerViewModel::class.java]
        }
        compose.waitUntil(10_000) { !model.busy && !model.loading }
    }

    @After fun tearDown() {
        compose.runOnIdle { models.clear() }
        previousSession?.let(storage::save) ?: storage.clear()
    }

    @Test fun newTransactionsUseSavedCurrencyAndTimeZoneWithoutChangingReportingCurrency() {
        val reportingCurrency = model.baseCurrency
        val dates = mutableSetOf<String>()
        // These zones are 25 hours apart, so a hard-coded UTC date cannot satisfy both.
        listOf("Pacific/Kiritimati" to "JPY", "Pacific/Pago_Pago" to "EUR").forEach { (zone, currency) ->
            storage.save(Session("unused-defaults-test-token", Long.MAX_VALUE, account.copy(timeZone = zone, defaultCurrency = currency)))
            val before = LocalDate.now(ZoneId.of(zone)).toString()
            compose.runOnIdle { model.newTransaction() }
            val after = LocalDate.now(ZoneId.of(zone)).toString()
            compose.runOnIdle {
                assertEquals(LedgerPage.Editor, model.page)
                assertEquals(currency, model.draft.currency)
                assertTrue(model.draft.date in setOf(before, after))
                assertEquals(reportingCurrency, model.baseCurrency)
                dates.add(model.draft.date)
            }
        }
        assertEquals(2, dates.size)
    }
}
