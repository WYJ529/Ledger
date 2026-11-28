package com.example.cpen321application.integration

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.cpen321application.LedgerImportRow
import com.example.cpen321application.LedgerInput
import com.example.cpen321application.LedgerRepository
import com.example.cpen321application.LedgerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Integration coverage for restoring imported records into a fresh ViewModel.
 * This is intentionally outside the E2E suite because it verifies state restoration directly.
 */
class LedgerHistoricImportsIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val merchant = "Historic import ${UUID.randomUUID()}"
    private val models = ViewModelStore()

    @After fun cleanUp() {
        models.clear()
        LedgerRepository(application).use { repository ->
            repository.load().filter { it.input.merchant == merchant }
                .forEach { repository.delete(it.id, it.revision) }
        }
    }

    @Test fun freshViewModelShowsHistoricPdfAndReceiptImportsWithoutReimporting() {
        val savedIds = LedgerRepository(application).use { repository ->
            val currency = repository.baseCurrency()
            listOf("statement.pdf", "receipt.jpg").forEachIndexed { index, name ->
                repository.commitImport(
                    listOf(LedgerImportRow("row", LedgerInput(amount = (30 + index).toString(),
                        currency = currency, date = "2025-01-06", merchant = merchant))),
                    "receipt", name, UUID.randomUUID().toString()
                )
            }
            repository.load().filter { it.input.merchant == merchant }.map { it.id }.toSet()
        }

        lateinit var fresh: LedgerViewModel
        compose.runOnIdle {
            fresh = ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory(application),
            )[LedgerViewModel::class.java]
        }
        compose.waitUntil(10_000) { !fresh.busy && !fresh.loading }
        compose.runOnIdle {
            assertEquals(null, fresh.month)
            assertTrue(fresh.visibleRecords.map { it.id }.containsAll(savedIds))
        }
    }
}
