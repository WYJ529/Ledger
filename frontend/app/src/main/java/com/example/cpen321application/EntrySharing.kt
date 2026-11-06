package com.example.cpen321application

import org.json.JSONObject

internal data class PersonalShare(val input: LedgerInput, val sourcePersonalId: String?, val groupName: String)

internal interface EntrySharingGateway {
    fun groups(token: String): List<ExpenseGroup>
    fun share(session: Session, group: ExpenseGroup, record: LedgerRecord): Boolean
    fun personalShare(session: Session, groupId: String, expenseId: String): PersonalShare
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
}

internal fun shareProblem(records: List<LedgerRecord>, group: ExpenseGroup): String? = when {
    records.any { it.input.kind != "expense" } -> "Only expenses can be shared with groups."
    records.any { it.input.currency != group.currency } -> "Choose a group with the same currency as every selected expense."
    records.any { it.amountMinor > Money.MAX_MINOR } -> "A selected expense exceeds the group amount limit."
    else -> null
}
