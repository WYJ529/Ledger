package com.example.cpen321application.e2e

import com.example.cpen321application.*
import android.content.ContentValues
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.view.KeyEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** End-to-end personal expenses. Cleanup is restricted to this test's unique merchant. */
@RunWith(AndroidJUnit4::class)
class LedgerUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val merchant = "Ledger UI ${UUID.randomUUID()}"
    private var savedTransactions = false
    private var downloadFixtureUri: Uri? = null
    private var legacyDownloadId: Long? = null
    private val dashboard get() = compose.onNodeWithTag("ledger-dashboard")
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun openMyExpenses() {
        awaitDashboard()
        // Surface a blocked emulator (for example a System UI ANR) before sending platform keys.
        compose.waitUntil(20_000) { compose.activity.hasWindowFocus() }
    }

    @Test fun myExpensesIsHomeAndTabsCanBeReopened() {
        compose.onNodeWithTag("section-Expenses").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("section-Groups").assertIsDisplayed()
        compose.onNodeWithTag("section-Me").assertIsDisplayed().performClick()
        compose.onNodeWithTag("section-Me").assertIsSelected()
        pressSystemBack()
        awaitDashboard()
        compose.onNodeWithTag("section-Expenses").assertIsSelected()
        compose.onNodeWithTag("section-Groups").performClick()
        compose.onNodeWithText("Welcome back").performScrollTo().assertIsDisplayed()
        pressSystemBack()
        awaitDashboard()
    }

    @Test fun expensesTabReturnsToDashboardAndReselectionResetsEditor() {
        openManualEntry()
        editorField("Amount").performTextInput("42")
        closeKeyboard()
        compose.onNodeWithTag("section-Me").performClick()
        compose.onNodeWithTag("section-Expenses").performClick()
        awaitDashboard()
        openManualEntry()
        compose.onNodeWithTag("section-Expenses").performClick()
        awaitDashboard()
    }

    @After fun cleanOnlyThisTestsRecords() {
        downloadFixtureUri?.let { context.contentResolver.delete(it, null, null) }
        legacyDownloadId?.let { context.getSystemService(DownloadManager::class.java).remove(it) }
        if (!savedTransactions) return
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        compose.onNodeWithTag("section-Expenses").performClick()
        awaitDashboard()
        searchForMerchant()
        chooseKind("All")
        val card = hasText(merchant) and (hasText("IN") or hasText("OUT")) and hasClickAction()
        repeat(4) {
            dashboard.performScrollToNode(hasText("Transactions"))
            if (compose.onAllNodes(filteredCount(0)).fetchSemanticsNodes().isNotEmpty()) return
            dashboard.performScrollToNode(card)
            compose.onAllNodes(card).onFirst().performClick()
            scrollEditorTo("Delete transaction")
            compose.onNodeWithText("Delete transaction").performClick()
            compose.onNodeWithText("Delete", substring = false).performClick()
            awaitNotice("Transaction deleted.")
        }
        assertFilteredCount(0)
    }

    @Test fun csvFilePickerRequiresDuplicateReviewBeforeAtomicImport() {
        awaitDashboard()
        val currency = currentCurrency()
        createTransaction("7", "expense")
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val fileName = "ui-${UUID.randomUUID().toString().take(8)}.csv"
        publishCsv(fileName, "date,merchant,amount,currency,category,kind,notes\n" +
            "$today,$merchant,7,$currency,Other,expense,duplicate candidate\n" +
            "$today,$merchant,12,$currency,Food,expense,new transaction\n")

        compose.onNodeWithText("Add a bill").performClick()
        compose.onNodeWithText("Choose a file").performClick()
        selectDocumentInPicker(fileName)
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        awaitEnabled("Preview CSV")
        compose.onNodeWithTag("ledger-import").performScrollToNode(hasText(fileName))
        compose.onNodeWithText("Preview CSV").assertIsEnabled().performClick()
        awaitNotice("Confirm import (2)")
        compose.onNodeWithText("Confirm import (2)").assertIsNotEnabled()
        compose.onNodeWithTag("ledger-import").performScrollToNode(hasText("Possible duplicate"))
        captureIfRequested("ledger-import-review")
        compose.onNodeWithTag("ledger-import").performScrollToNode(hasText("Keep this transaction anyway"))
        compose.onNodeWithContentDescription("Keep transaction 1 despite possible duplicates").performClick()
        awaitEnabled("Confirm import (2)")
        compose.onNodeWithText("Confirm import (2)").performClick()
        awaitNotice("2 transactions imported.")
        searchForMerchant()
        assertFilteredCount(3)
        showRecord(hasText("CSV import") and hasText(expenseAmount("7", currency)))
        showRecord(hasText("CSV import") and hasText(expenseAmount("12", currency)))
        dashboard.performScrollToNode(hasText("Overview"))
        compose.onNodeWithText(currency).performClick()
        compose.onNodeWithText("✓").assertIsDisplayed()
        captureIfRequested("ledger-currency-menu")
        pressSystemBack()
        compose.onNodeWithText("✓").assertDoesNotExist()
    }

    @Test fun createEditAndDeleteTransactionThroughTheUi() {
        val currency = currentCurrency()
        val firstAmount = if (Money.digits(currency) == 0) "1234" else "12.34"
        val changedAmount = if (Money.digits(currency) == 0) "2345" else "23.45"
        awaitDashboard()
        openManualEntry()
        editorField("Amount").performTextInput(firstAmount)
        scrollEditorTo("Merchant / payee")
        editorField("Merchant / payee").performTextInput(merchant)
        compose.onNodeWithText("Save").performClick()
        savedTransactions = true
        awaitNotice("Transaction added.")
        showRecord(hasText(expenseAmount(firstAmount, currency))).performClick()
        editorField("Amount").performTextReplacement(changedAmount)
        compose.onNodeWithText("Save").performClick()
        awaitNotice("Transaction updated.")
        showRecord(hasText(expenseAmount(changedAmount, currency))).performClick()
        scrollEditorTo("Delete transaction")
        compose.onNodeWithText("Delete transaction").performClick()
        compose.onNodeWithText("Delete transaction?").assertIsDisplayed()
        compose.onNodeWithText("Delete", substring = false).performClick()
        awaitNotice("Transaction deleted.")
        searchForMerchant()
        assertFilteredCount(0)
    }

    @Test fun draftSurvivesActivityRecreationWithoutSaving() {
        awaitDashboard()
        openManualEntry()
        editorField("Amount").performTextInput("42")
        scrollEditorTo("Merchant / payee")
        editorField("Merchant / payee").performTextInput(merchant)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        scrollEditorTo("Merchant / payee")
        editorField("Merchant / payee").assertTextContains(merchant)
        scrollEditorTo("Amount")
        editorField("Amount").assertTextContains("42")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard your changes?").assertIsDisplayed()
        compose.onNodeWithText("Discard", substring = false).performClick()
        awaitDashboard()
        searchForMerchant()
        assertFilteredCount(0)
    }

    @Test fun bulkDeleteCanCancelAndDeletesOnlyFilteredSelectedRecords() {
        awaitDashboard()
        createTransaction("10", "expense")
        createTransaction("20", "expense")
        createTransaction("30", "income")

        searchForMerchant()
        chooseKind("Expenses")
        assertFilteredCount(2)
        compose.onNodeWithTag("ledger-dashboard").performScrollToNode(hasText("Select transactions"))
        compose.onNodeWithText("Select transactions").performClick()
        compose.onNodeWithText("Select all shown").performClick()
        compose.onNodeWithText("Delete (2)").performClick()
        compose.onNodeWithText("Delete 2 transactions?").assertIsDisplayed()
        compose.onNodeWithText("Cancel", substring = false).performClick()

        compose.onNodeWithText("2 selected · 2 shown").assertIsDisplayed()
        chooseKind("All")
        assertFilteredCount(3)
        compose.onNodeWithText("Select transactions").assertIsDisplayed()
        chooseKind("Expenses")
        assertFilteredCount(2)
        compose.onNodeWithTag("ledger-dashboard").performScrollToNode(hasText("Select transactions"))
        compose.onNodeWithText("Select transactions").performClick()
        compose.onNodeWithText("Select all shown").performClick()
        captureIfRequested("ledger-bulk-delete")
        compose.onNodeWithText("Delete (2)").performClick()
        compose.onNodeWithText("Delete selected").performClick()
        awaitDashboard()
        dashboard.performScrollToNode(hasText("2 transactions deleted."))
        assertFilteredCount(0)
        chooseKind("Income")
        assertFilteredCount(1)
        showRecord(hasText("IN"))
    }

    private fun createTransaction(amount: String, kind: String) {
        openManualEntry()
        if (kind == "income") compose.onNodeWithText("Income", substring = false).performClick()
        editorField("Amount").performTextInput(amount)
        scrollEditorTo(if (kind == "income") "Merchant / payer" else "Merchant / payee")
        editorField(if (kind == "income") "Merchant / payer" else "Merchant / payee").performTextInput(merchant)
        compose.onNodeWithText("Save").performClick()
        savedTransactions = true
        awaitNotice("Transaction added.")
    }

    private fun searchForMerchant() {
        val search = hasSetTextAction() and hasText("Search merchant, category or notes")
        dashboard.performScrollToNode(search)
        compose.onNode(search).performTextReplacement(merchant)
        closeKeyboard()
    }

    private fun chooseKind(label: String) {
        val chip = hasText(label) and hasClickAction()
        dashboard.performScrollToNode(chip)
        compose.onNode(chip and hasAnyAncestor(hasTestTag("ledger-dashboard"))).performClick()
    }

    private fun filteredCount(count: Int) = SemanticsMatcher("filtered count equals $count") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("$count / ") } == true
    }

    private fun assertFilteredCount(count: Int) {
        dashboard.performScrollToNode(hasText("Transactions"))
        // The list is lazy. Read the header's result count, including when the ledger is empty.
        val result = filteredCount(count)
        compose.waitUntil(10_000) { compose.onAllNodes(result).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun showRecord(details: SemanticsMatcher): androidx.compose.ui.test.SemanticsNodeInteraction {
        val card = hasText(merchant) and details and hasClickAction()
        dashboard.performScrollToNode(card)
        return compose.onNode(card).assertIsDisplayed()
    }

    private fun expenseAmount(amount: String, currency: String) =
        "− ${Money.display(Money.requireMinor(amount, currency), currency)}"

    private fun currentCurrency(): String {
        dashboard.performScrollToNode(hasText("Overview"))
        return LedgerDomain.currencies.single { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun publishCsv(name: String, contents: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
            file.writeText(contents)
            @Suppress("DEPRECATION")
            legacyDownloadId = context.getSystemService(DownloadManager::class.java)
                .addCompletedDownload(name, name, false, "text/csv", file.absolutePath, file.length(), false)
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        downloadFixtureUri = uri
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(contents) }
            ?: error("Could not write the CSV test fixture")
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
    }

    private fun selectDocumentInPicker(name: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val title = UiSelector().resourceId("android:id/title")
        device.findObject(UiSelector().description("Show roots")).click()
        device.findObject(title.text("Downloads")).click()
        val file = device.findObject(title.text(name))
        if (!file.waitForExists(2_000)) {
            device.findObject(UiSelector().description("Search")).click()
            device.findObject(UiSelector().className("android.widget.EditText")).setText(name)
            device.pressEnter()
        }
        file.click()
    }

    private fun awaitEnabled(text: String) {
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText(text, substring = false).assertIsEnabled() }.isSuccess
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
        closeKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("ledger-editor").performScrollToNode(hasText(label))
        compose.waitForIdle()
    }
    private fun openManualEntry() {
        compose.onNodeWithText("Add a bill").performClick()
        compose.onNodeWithText("Enter manually").performClick()
    }
    // Send a real system Back key without Espresso's focused-root selection (also works for popups).
    private fun pressSystemBack() {
        // Navigation installs BackHandler in a composition side effect; settle it before the key.
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun closeKeyboard() {
        compose.runOnIdle {
            val window = compose.activity.window
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) {
            var hidden = false
            compose.runOnIdle {
                hidden = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
            hidden
        }
        compose.waitForIdle()
    }
    private fun awaitDashboard() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Add a bill") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun awaitNotice(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }
}
