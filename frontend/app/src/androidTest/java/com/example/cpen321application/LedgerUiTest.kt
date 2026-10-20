package com.example.cpen321application

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** End-to-end guest workflow. Never clears the user's database; cleanup is restricted to this test's unique merchant. */
@RunWith(AndroidJUnit4::class)
class LedgerUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val merchant = "Ledger UI ${UUID.randomUUID()}"
    private var fixture: File? = null
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun openGuestLedgerFromIntegratedHome() {
        compose.waitUntil(10_000) {
            listOf("Open local guest ledger", "Local guest ledger").any {
                compose.onAllNodesWithText(it, substring = false).fetchSemanticsNodes().isNotEmpty()
            }
        }
        val entry = if (compose.onAllNodesWithText("Open local guest ledger").fetchSemanticsNodes().isNotEmpty())
            "Open local guest ledger" else "Local guest ledger"
        compose.onNodeWithText(entry, substring = false).performScrollTo().performClick()
        awaitDashboard()
    }

    @Test fun guestLedgerReturnsToAccountHomeAndCanBeReopened() {
        compose.onNodeWithText("Device-only · not linked to an account").assertIsDisplayed()
        compose.onNodeWithText("Account & groups").performClick()
        openGuestLedgerFromIntegratedHome()
        Espresso.pressBack()
        openGuestLedgerFromIntegratedHome()
    }

    @After fun cleanOnlyThisTestsRecords() {
        fixture?.delete()
        LedgerRepository(context).use { repository ->
            repository.load().filter { it.input.merchant == merchant }.forEach { repository.delete(it.id, it.revision) }
        }
    }

    @Test fun createEditAndDeleteTransactionThroughTheUi() {
        val currency = LedgerRepository(context).use { it.baseCurrency() }
        val firstAmount = if (currency == "JPY") "1234" else "12.34"
        val changedAmount = if (currency == "JPY") "2345" else "23.45"
        awaitDashboard()
        compose.onNodeWithText("+ Add transaction").performClick()
        editorField("Amount").performTextInput(firstAmount)
        scrollEditorTo("Merchant / payee")
        editorField("Merchant / payee").performTextInput(merchant)
        compose.onNodeWithText("Save transaction").performClick()
        awaitNotice("Transaction added.")
        LedgerRepository(context).use { repository ->
            val saved = repository.load().single { it.input.merchant == merchant }
            assertEquals(LedgerDomain.parseMinor(firstAmount, currency), saved.amountMinor)
        }

        compose.onNodeWithTag("ledger-dashboard").performScrollToNode(hasText(merchant))
        compose.onNodeWithText(merchant).performClick()
        editorField("Amount").performTextReplacement(changedAmount)
        compose.onNodeWithText("Save transaction").performClick()
        awaitNotice("Transaction updated.")
        LedgerRepository(context).use { repository ->
            val saved = repository.load().single { it.input.merchant == merchant }
            assertEquals(LedgerDomain.parseMinor(changedAmount, currency), saved.amountMinor)
            assertEquals(2, saved.revision)
        }

        compose.onNodeWithTag("ledger-dashboard").performScrollToNode(hasText(merchant))
        compose.onNodeWithText(merchant).performClick()
        scrollEditorTo("Delete transaction")
        compose.onNodeWithText("Delete transaction").performClick()
        compose.onNodeWithText("Delete transaction?").assertIsDisplayed()
        compose.onNodeWithText("Delete", substring = false).performClick()
        awaitNotice("Transaction deleted.")
        LedgerRepository(context).use { repository -> assertTrue(repository.load().none { it.input.merchant == merchant }) }
    }

    @Test fun draftSurvivesActivityRecreationWithoutSaving() {
        awaitDashboard()
        compose.onNodeWithText("+ Add transaction").performClick()
        editorField("Amount").performTextInput("42")
        scrollEditorTo("Merchant / payee")
        editorField("Merchant / payee").performTextInput(merchant)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        scrollEditorTo("Merchant / payee")
        editorField("Merchant / payee").assertTextContains(merchant)
        scrollEditorTo("Amount")
        editorField("Amount").assertTextContains("42")
        LedgerRepository(context).use { repository -> assertTrue(repository.load().none { it.input.merchant == merchant }) }
        compose.onNodeWithText("Back", substring = false).performClick()
        compose.onNodeWithText("Discard your changes?").assertIsDisplayed()
        compose.onNodeWithText("Discard", substring = false).performClick()
        compose.onNodeWithText("+ Add transaction").assertIsDisplayed()
    }

    @Test fun csvContentUriRequiresDuplicateReviewBeforeAtomicImport() {
        awaitDashboard()
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val currency = LedgerRepository(context).use { repository ->
            val base = repository.baseCurrency()
            repository.save(LedgerInput(amount = "7", currency = base, date = today, merchant = merchant), requestId = UUID.randomUUID().toString())
            base
        }
        val file = File(context.cacheDir, "ledger-captures/ui-${UUID.randomUUID()}.csv").also {
            it.parentFile!!.mkdirs()
            it.writeText("date,merchant,amount,currency,category,kind,notes\n$today,$merchant,7,$currency,Other,expense,duplicate candidate\n$today,$merchant,12,$currency,Food,expense,new transaction\n")
            fixture = it
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ledger.files", file)
        lateinit var model: LedgerViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[LedgerViewModel::class.java]
            model.refresh()
        }
        compose.waitUntil(10_000) { !model.busy }
        compose.onNodeWithText("Import", substring = false).performClick()
        compose.runOnIdle { model.documents.chooseDocument(uri) }
        compose.waitUntil(10_000) { !model.busy && model.documentName == file.name }
        compose.onNodeWithText("Preview CSV").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !model.busy && model.importReviews.size == 2 }
        compose.onNodeWithText("Confirm import (2)").assertIsNotEnabled()
        compose.onNodeWithTag("ledger-import").performScrollToNode(hasText("Possible duplicate"))
        captureIfRequested("ledger-import-review")
        compose.onNodeWithTag("ledger-import").performScrollToNode(hasText("Keep this transaction anyway"))
        compose.onNodeWithContentDescription("Keep transaction 1 despite possible duplicates").performClick()
        compose.waitUntil(10_000) { !model.reviewing && model.importReviews.first().row.acknowledgeDuplicate }
        compose.onNodeWithText("Confirm import (2)").assertIsEnabled().performClick()
        awaitNotice("2 transactions imported.")
        LedgerRepository(context).use { repository ->
            val saved = repository.load().filter { it.input.merchant == merchant }
            assertEquals(3, saved.size)
            assertEquals(2, saved.count { it.source == "csv" })
            assertEquals(2, saved.count { it.amountMinor == LedgerDomain.parseMinor("7", currency) })
        }
        compose.onNodeWithTag("ledger-dashboard").performScrollToNode(hasText("Overview"))
        compose.onNodeWithText("$currency ▾").performClick()
        compose.onNodeWithText("Reporting currency").assertIsDisplayed()
        captureIfRequested("ledger-currency-dialog")
        compose.onNodeWithText("Cancel", substring = false).performClick()
    }

    @Test fun newViewModelShowsHistoricPdfAndReceiptImportsWithoutReimporting() {
        val savedIds = LedgerRepository(context).use { repository ->
            val currency = repository.baseCurrency()
            listOf("statement.pdf", "receipt.jpg").forEachIndexed { index, name ->
                repository.commitImport(listOf(LedgerImportRow("row", LedgerInput(amount = (30 + index).toString(),
                    currency = currency, date = "2025-01-06", merchant = merchant))), "receipt", name, UUID.randomUUID().toString())
            }
            repository.load().filter { it.input.merchant == merchant }.map { it.id }.toSet()
        }
        // A new ViewModelStore models a cold process; Activity recreation alone retains the old ViewModel.
        val store = ViewModelStore()
        lateinit var fresh: LedgerViewModel
        try {
            compose.runOnIdle {
                fresh = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(context as Application))[LedgerViewModel::class.java]
            }
            compose.waitUntil(10_000) { !fresh.busy && !fresh.loading }
            compose.runOnIdle {
                assertEquals(null, fresh.month)
                assertTrue(fresh.visibleRecords.map { it.id }.containsAll(savedIds))
            }
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun bulkDeleteCanCancelAndDeletesOnlyFilteredSelectedRecords() {
        val ids = LedgerRepository(context).use { repository ->
            val currency = repository.baseCurrency()
            (1..3).map { index -> repository.save(LedgerInput(amount = index.toString(), currency = currency,
                date = "2025-01-06", merchant = merchant, kind = if (index == 3) "income" else "expense"),
                requestId = UUID.randomUUID().toString()).id }
        }
        lateinit var model: LedgerViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[LedgerViewModel::class.java]; model.refresh() }
        compose.waitUntil(10_000) { !model.busy }
        compose.runOnIdle { model.filters.changeSearch(merchant); model.filters.changeKindFilter("expense") }
        compose.waitUntil(10_000) { model.visibleRecords.size == 2 }
        compose.onNodeWithTag("ledger-dashboard").performScrollToNode(hasText("Select transactions"))
        compose.onNodeWithText("Select transactions").performClick()
        compose.onNodeWithText("Select all shown").performClick()
        compose.onNodeWithText("Delete (2)").performClick()
        compose.onNodeWithText("Delete 2 transactions?").assertIsDisplayed()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        LedgerRepository(context).use { repository -> assertTrue(repository.load().map { it.id }.containsAll(ids)) }
        // Changing a filter clears selection so hidden rows cannot be accidentally deleted.
        compose.runOnIdle { model.filters.changeKindFilter("all") }
        compose.waitUntil(10_000) { model.visibleRecords.size == 3 }
        compose.runOnIdle { assertTrue(model.selectedRecordIds.isEmpty()); assertTrue(!model.selectingRecords); model.filters.changeKindFilter("expense") }
        compose.waitUntil(10_000) { model.visibleRecords.size == 2 }
        compose.onNodeWithTag("ledger-dashboard").performScrollToNode(hasText("Select transactions"))
        compose.onNodeWithText("Select transactions").performClick()
        compose.onNodeWithText("Select all shown").performClick()
        captureIfRequested("ledger-bulk-delete")
        compose.onNodeWithText("Delete (2)").performClick()
        compose.onNodeWithText("Delete selected").performClick()
        compose.waitUntil(10_000) { !model.busy && model.notice == "2 transactions deleted." }
        LedgerRepository(context).use { repository ->
            assertEquals(listOf(ids[2]), repository.load().filter { it.input.merchant == merchant }.map { it.id })
        }
    }

    private fun captureIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("ledgerScreenshots") != "true") return
        compose.waitForIdle()
        // Native dialog window fades are outside Compose's test clock; let them settle for a useful optional artifact.
        android.os.SystemClock.sleep(400)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun editorField(label: String) = compose.onNode(hasText(label) and hasSetTextAction())
    private fun scrollEditorTo(label: String) {
        // Let the platform IME finish closing before LazyColumn moves a different text field.
        // Otherwise its inset animation can detach the matched semantics node between lookup and focus.
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("ledger-editor").performScrollToNode(hasText(label))
        compose.waitForIdle()
    }
    private fun awaitDashboard() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("+ Add transaction") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun awaitNotice(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }
}
