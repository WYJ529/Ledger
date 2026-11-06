package com.example.cpen321application

import android.content.Context
import org.json.JSONObject

internal data class PersonalShare(val input: LedgerInput, val sourcePersonalId: String?, val groupName: String)

internal interface EntrySharingGateway {
    fun groups(token: String): List<ExpenseGroup>
    fun share(session: Session, group: ExpenseGroup, record: LedgerRecord): Boolean
    fun personalShare(session: Session, groupId: String, expenseId: String): PersonalShare
    fun markImported(session: Session, groupId: String, expenseId: String)
}

internal object EntrySharingApi : EntrySharingGateway {
    override fun groups(token: String) = GroupExpenseApi.groups(token)

    override fun share(session: Session, group: ExpenseGroup, record: LedgerRecord): Boolean {
        val json = AccountApi.request("POST", "/api/groups/${group.id}/shared-entries",
            JSONObject().put("sourceId", record.id).put("input", LedgerDatabase.inputToJson(record.input)), session.token)
        return !json.getBoolean("alreadyShared")
    }

    override fun personalShare(session: Session, groupId: String, expenseId: String): PersonalShare {
        val json = AccountApi.request("GET", "/api/groups/$groupId/expenses/$expenseId/personal-entry", token = session.token)
        return PersonalShare(LedgerDatabase.inputFromJson(json.getJSONObject("input")),
            if (json.isNull("sourcePersonalId")) null else json.getString("sourcePersonalId"), json.getString("groupName"))
    }

    override fun markImported(session: Session, groupId: String, expenseId: String) {
        AccountApi.request("POST", "/api/groups/$groupId/expenses/$expenseId/personal-entry/imported", JSONObject(), session.token)
    }
}

/** Bills whose share is in this device's ledger. Nothing is known if the ledger cannot be read. */
internal fun localShareImports(context: Context, accountId: String, groupId: String, expenseIds: List<String>): Set<String> = try {
    LedgerRepository(context).use { it.groupImports.imported(accountId, groupId, expenseIds) }
} catch (_: LedgerException) { emptySet() }

internal fun shareProblem(records: List<LedgerRecord>, group: ExpenseGroup): String? = when {
    records.any { it.input.kind != "expense" } -> "Only expenses can be shared with groups."
    records.any { it.input.currency != group.currency } -> "Choose a group with the same currency as every selected expense."
    records.any { it.amountMinor > 100_000_000L } -> "A selected expense exceeds the group amount limit."
    else -> null
}
