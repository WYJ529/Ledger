package com.example.cpen321application

import org.json.JSONArray
import org.json.JSONObject

internal data class ExpenseGroup(val id: String, val name: String, val currency: String,
    val description: String = "", val ownerId: String = "", val memberCount: Int = 0)
internal data class GroupMember(val id: String, val displayName: String, val role: String = "member")
internal data class GroupBill(
    val id: String, val description: String, val amountCents: Long, val paidBy: String,
    val splitMode: String, val createdAt: String, val shares: List<BillShare>,
    val canEdit: Boolean = false, val revision: Int = 0,
    val subtotalCents: Long = amountCents, val taxCents: Long = 0, val tipCents: Long = 0,
    val customCalculation: MealCalculation? = null,
)
internal data class GroupExpenseSummary(
    val group: ExpenseGroup, val members: List<GroupMember>, val totalCents: Long,
    val expenseCount: Int, val memberShares: List<BillShare>, val expenses: List<GroupBill>,
)
internal data class BillAmounts(val totalCents: Long, val subtotalCents: Long = totalCents,
    val taxCents: Long = 0, val tipCents: Long = 0)
internal data class BillSubmission(val description: String, val paidBy: String, val splitMode: String,
    val shares: List<BillShare>, val amounts: BillAmounts, val meal: MealCalculation? = null,
    /** Decimal places of the group currency: 0 for JPY and KRW. */
    val digits: Int = 2)

internal interface GroupExpenseReader {
    fun groups(token: String): List<ExpenseGroup>
    fun summary(token: String, groupId: String): GroupExpenseSummary
}

internal object GroupExpenseApi : GroupExpenseReader {
    override fun groups(token: String): List<ExpenseGroup> {
        val items = AccountApi.request("GET", "/api/groups", token = token).getJSONArray("groups")
        return List(items.length()) { parseExpenseGroup(items.getJSONObject(it)) }
    }

    override fun summary(token: String, groupId: String): GroupExpenseSummary {
        val json = AccountApi.request("GET", "/api/groups/$groupId/summary", token = token)
        return parseSummary(json)
    }

    internal fun parseSummary(json: JSONObject): GroupExpenseSummary {
        val group = json.getJSONObject("group")
        val digits = moneyDigits(group.getString("currency"))
        val members = group.getJSONArray("members")
        val expenses = json.getJSONArray("expenses")
        return GroupExpenseSummary(
            parseExpenseGroup(group),
            List(members.length()) { members.getJSONObject(it).let { m -> GroupMember(m.getString("id"), m.getString("displayName"), m.optString("role", "member")) } },
            json.getLong("totalCents"), json.getInt("expenseCount"), parseShares(json.getJSONArray("memberShares")),
            List(expenses.length()) {
                val bill = expenses.getJSONObject(it)
                GroupBill(bill.getString("id"), bill.getString("description"), bill.getLong("amountCents"),
                    bill.getString("paidBy"), bill.getString("splitMode"), bill.getString("createdAt"), parseShares(bill.getJSONArray("shares")),
                    bill.optBoolean("canEdit"), bill.optInt("revision"),
                    bill.optLong("subtotalCents", bill.getLong("amountCents")), bill.optLong("taxCents"), bill.optLong("tipCents"), parseMeal(bill.optJSONObject("customCalculation"), digits))
            },
        )
    }

    fun addBill(token: String, groupId: String, input: BillSubmission, requestId: String) {
        val body = billBody(input).put("requestId", requestId)
        AccountApi.request("POST", "/api/groups/$groupId/expenses", body, token)
    }

    fun updateBill(token: String, groupId: String, billId: String, input: BillSubmission, revision: Int) {
        val body = billBody(input).put("revision", revision)
        AccountApi.request("PUT", "/api/groups/$groupId/expenses/$billId", body, token)
    }

    private fun billBody(input: BillSubmission): JSONObject {
        val items = JSONArray()
        input.shares.forEach { share ->
            val item = JSONObject().put("userId", share.userId)
            when (input.splitMode) {
                "percentage" -> item.put("percentage", percentageText(requireNotNull(share.percentageBasisPoints)))
                // The server recomputes every share from the adjustments; the amount is not sent.
                "adjusted" -> item.put("adjustment", adjustmentText(requireNotNull(share.adjustmentCents), input.digits).ifEmpty { "0" })
                else -> item.put("amount", decimalAmount(share.amountCents, input.digits))
            }
            items.put(item)
        }
        return JSONObject().put("description", input.description).put("amount", decimalAmount(input.amounts.totalCents, input.digits))
            .put("customCalculation", input.meal?.let { meal -> JSONObject().put("tipRate", meal.tipRate).put("members", JSONArray().also { list -> meal.members.forEach { m -> list.put(JSONObject().put("userId", m.userId).put("subtotal", decimalAmount(m.subtotalCents, input.digits)).put("extraTaxRate", m.extraTaxRate)) } }) } ?: JSONObject.NULL)
            .put("subtotal", decimalAmount(input.amounts.subtotalCents, input.digits)).put("tax", decimalAmount(input.amounts.taxCents, input.digits))
            .put("tip", decimalAmount(input.amounts.tipCents, input.digits))
            .put("paidBy", input.paidBy).put("splitMode", input.splitMode).put("shares", items)
    }

    private fun parseMeal(json: JSONObject?, digits: Int): MealCalculation? {
        if (json == null) return null
        val members = json.getJSONArray("members")
        return MealCalculation(json.getString("tipRate"), List(members.length()) { index ->
            val m = members.getJSONObject(index)
            MealMember(m.getString("userId"), moneyCents(m.getString("subtotal"), true, digits)!!, m.getString("extraTaxRate"))
        })
    }

    private fun parseShares(items: JSONArray) = List(items.length()) {
        items.getJSONObject(it).let { share -> BillShare(share.getString("userId"), share.getLong("amountCents"),
            if (share.has("percentageBasisPoints") && !share.isNull("percentageBasisPoints")) share.getInt("percentageBasisPoints") else null,
            if (share.has("adjustmentCents") && !share.isNull("adjustmentCents")) share.getLong("adjustmentCents") else null) }
    }
}
