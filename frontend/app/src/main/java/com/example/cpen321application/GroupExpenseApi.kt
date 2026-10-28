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
        val group = json.getJSONObject("group")
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
                    bill.optLong("subtotalCents", bill.getLong("amountCents")), bill.optLong("taxCents"), bill.optLong("tipCents"), parseMeal(bill.optJSONObject("customCalculation")))
            },
        )
    }

    fun addBill(token: String, groupId: String, description: String, totalCents: Long, paidBy: String,
                splitMode: String, shares: List<BillShare>, requestId: String,
                subtotalCents: Long = totalCents, taxCents: Long = 0, tipCents: Long = 0, meal: MealCalculation? = null) {
        val body = billBody(description, totalCents, paidBy, splitMode, shares, subtotalCents, taxCents, tipCents, meal).put("requestId", requestId)
        AccountApi.request("POST", "/api/groups/$groupId/expenses", body, token)
    }

    fun updateBill(token: String, groupId: String, billId: String, description: String, totalCents: Long,
                   paidBy: String, splitMode: String, shares: List<BillShare>, revision: Int,
                   subtotalCents: Long = totalCents, taxCents: Long = 0, tipCents: Long = 0, meal: MealCalculation? = null) {
        val body = billBody(description, totalCents, paidBy, splitMode, shares, subtotalCents, taxCents, tipCents, meal).put("revision", revision)
        AccountApi.request("PUT", "/api/groups/$groupId/expenses/$billId", body, token)
    }

    private fun billBody(description: String, totalCents: Long, paidBy: String, splitMode: String, shares: List<BillShare>,
                         subtotalCents: Long, taxCents: Long, tipCents: Long, meal: MealCalculation?): JSONObject {
        val items = JSONArray()
        shares.forEach { items.put(JSONObject().put("userId", it.userId).put("amount", decimalAmount(it.amountCents))) }
        return JSONObject().put("description", description).put("amount", decimalAmount(totalCents))
            .put("customCalculation", meal?.let { input -> JSONObject().put("tipRate", input.tipRate).put("members", JSONArray().also { list -> input.members.forEach { m -> list.put(JSONObject().put("userId", m.userId).put("subtotal", decimalAmount(m.subtotalCents)).put("extraTaxRate", m.extraTaxRate)) } }) } ?: JSONObject.NULL)
            .put("subtotal", decimalAmount(subtotalCents)).put("tax", decimalAmount(taxCents)).put("tip", decimalAmount(tipCents))
            .put("paidBy", paidBy).put("splitMode", splitMode).put("shares", items)
    }

    private fun parseMeal(json: JSONObject?): MealCalculation? {
        if (json == null) return null
        val members = json.getJSONArray("members")
        return MealCalculation(json.getString("tipRate"), List(members.length()) { index ->
            val m = members.getJSONObject(index)
            MealMember(m.getString("userId"), moneyCents(m.getString("subtotal"), true)!!, m.getString("extraTaxRate"))
        })
    }

    private fun parseShares(items: JSONArray) = List(items.length()) {
        items.getJSONObject(it).let { share -> BillShare(share.getString("userId"), share.getLong("amountCents")) }
    }
}
