package com.example.cpen321application

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real dialogs and SQLite; only the authenticated HTTP boundary is replaced. */
class EntrySharingUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val database = "entry-sharing-ui-${UUID.randomUUID()}.db"
    private val oldLanguage = L10n.selected
    private val session = Session("test-token", Long.MAX_VALUE, Account("me", "me@example.com", "Me", listOf("password"), "2026-01-01"))
    private val group = ExpenseGroup("first", "Roommates", "CAD", memberCount = 3)
    private val record = LedgerRecord("personal-record-00000001", LedgerInput(amount = "12.75", merchant = "Dinner", date = "2026-01-15"),
        1275, LedgerFx("CAD", "1", "2026-01-15", "Same currency", 1275), createdAt = "now", updatedAt = "now")
    private val bill = GroupBill("bill", "Dinner", 1275, "someone-else", "equal", "2026-01-15T00:00:00Z", listOf(BillShare("me", 425)))

    @Before fun english() { L10n.select(context, AppLanguage.English) }
    @After fun cleanup() { context.deleteDatabase(database); L10n.select(context, oldLanguage) }

    private class Gateway(var available: List<ExpenseGroup>, var allocation: PersonalShare) : EntrySharingGateway {
        val calls = mutableListOf<String>()
        var failNextGroup: String? = null
        var expired = false
        override fun groups(token: String) = available
        override fun share(session: Session, group: ExpenseGroup, record: LedgerRecord): Boolean {
            if (failNextGroup == group.id) { failNextGroup = null; throw java.io.IOException("Temporary failure") }
            synchronized(calls) { calls.add("${group.id}/${record.id}") }
            return true
        }
        override fun personalShare(session: Session, groupId: String, expenseId: String): PersonalShare {
            if (expired) throw ApiException(401, "Expired")
            return allocation
        }
    }

    private fun gateway(groups: List<ExpenseGroup> = listOf(group)) = Gateway(groups, PersonalShare(record.input.copy(amount = "4.25"), null, group.name))
    private fun waitText(text: String) = compose.waitUntil(10000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun sharesSelectedEntriesWithMultipleGroupsAndRetriesOnlyFailedTargets() {
        val gateway = gateway(listOf(group, group.copy(id = "second", name = "Trip")))
        gateway.failNextGroup = "second"
        compose.setContent { MaterialTheme { ShareEntriesDialog(session, listOf(record), {}, gateway) {} } }
        waitText("Roommates · CAD")
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onNodeWithText("Share selected").performClick()
        waitText("Temporary failure")
        compose.onNodeWithText("1 copies saved or already shared.").assertExists()
        compose.onNodeWithText("Share selected").performClick()
        waitText("2 copies saved or already shared.")
        compose.onNodeWithText("Share selected").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("first/${record.id}", "second/${record.id}"), gateway.calls) }
    }

    @Test fun mismatchedCurrencyCannotBeSelected() {
        val gateway = gateway(listOf(group.copy(currency = "USD")))
        compose.setContent { MaterialTheme { ShareEntriesDialog(session, listOf(record), {}, gateway) {} } }
        waitText("Roommates · USD")
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.onNodeWithText("Share selected").assertIsNotEnabled()
    }

    @Test fun noGroupsExplainsHowToStart() {
        compose.setContent { MaterialTheme { ShareEntriesDialog(session, listOf(record), {}, gateway(emptyList())) {} } }
        waitText("Create or join a group first.")
        compose.onNodeWithText("Share selected").assertIsNotEnabled()
    }

    @Test fun importConfirmsOnlyMyAllocationAndPersistsIt() {
        val gateway = gateway()
        var imported = false
        compose.setContent { MaterialTheme {
            ImportGroupShareDialog(session, group, bill, {}, { imported = true }, gateway, database) {}
        } }
        waitText("CAD 4.25")
        compose.onNodeWithText("CAD 12.75").assertDoesNotExist()
        LedgerRepository(context, databaseName = database).use { assertTrue(it.load().isEmpty()) }
        compose.onNodeWithText("Confirm import").performClick()
        waitText("Your share was imported.")
        compose.runOnIdle { assertTrue(imported) }
        LedgerRepository(context, databaseName = database).use {
            val saved = it.load().single()
            assertEquals(425L, saved.amountMinor)
            assertEquals("2026-01-15", saved.input.date)
        }
        compose.onNodeWithText("Confirm import").assertIsNotEnabled()
    }

    @Test fun changedServerAllocationRequiresAnotherConfirmation() {
        val gateway = gateway()
        compose.setContent { MaterialTheme { ImportGroupShareDialog(session, group, bill, {}, {}, gateway, database) {} } }
        waitText("CAD 4.25")
        compose.runOnIdle { gateway.allocation = gateway.allocation.copy(input = record.input.copy(amount = "6.00")) }
        compose.onNodeWithText("Confirm import").performClick()
        waitText("This expense changed. Review your updated share before importing.")
        compose.onNodeWithText("CAD 6.00").assertExists()
        LedgerRepository(context, databaseName = database).use { assertTrue(it.load().isEmpty()) }
        compose.onNodeWithText("Confirm import").performClick()
        waitText("Your share was imported.")
        LedgerRepository(context, databaseName = database).use { assertEquals(600L, it.load().single().amountMinor) }
    }

    @Test fun expiredSessionCannotImport() {
        val gateway = gateway()
        gateway.expired = true
        var expired = false
        compose.setContent { MaterialTheme { ImportGroupShareDialog(session, group, bill, { expired = true }, {}, gateway, database) {} } }
        compose.waitUntil(10000) { expired }
        compose.onNodeWithText("Confirm import").assertIsNotEnabled()
        LedgerRepository(context, databaseName = database).use { assertTrue(it.load().isEmpty()) }
    }
}
