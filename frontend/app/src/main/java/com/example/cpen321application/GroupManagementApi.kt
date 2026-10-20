package com.example.cpen321application

import org.json.JSONObject

internal data class GroupInvitationLink(val link: String, val expiresAt: String)
internal data class CreatedGroup(val group: ExpenseGroup, val invitation: GroupInvitationLink)
internal data class InvitationPreview(val group: ExpenseGroup, val inviterName: String, val expiresAt: String, val alreadyMember: Boolean)

internal object GroupManagementApi {
    fun create(token: String, name: String, description: String, currency: String, requestId: String): CreatedGroup {
        val json = AccountApi.request("POST", "/api/groups", JSONObject().put("name", name).put("description", description)
            .put("currency", currency).put("requestId", requestId), token)
        return CreatedGroup(parseExpenseGroup(json.getJSONObject("group")), parseInvitation(json.getJSONObject("invitation")))
    }
    fun invite(token: String, groupId: String): GroupInvitationLink = parseInvitation(
        AccountApi.request("POST", "/api/groups/$groupId/invitations", token = token).getJSONObject("invitation"))
    fun rename(token: String, groupId: String, name: String) {
        // HttpURLConnection supports PUT but does not consistently support PATCH on Android.
        AccountApi.request("PUT", "/api/groups/$groupId/name", JSONObject().put("name", name), token)
    }
    fun delete(token: String, groupId: String) { AccountApi.request("DELETE", "/api/groups/$groupId", token = token) }
    fun preview(token: String, invitationToken: String): InvitationPreview {
        val json = AccountApi.request("GET", "/api/groups/invitations/$invitationToken", token = token)
        return InvitationPreview(parseExpenseGroup(json.getJSONObject("group")), json.getString("inviterName"),
            json.getString("expiresAt"), json.getBoolean("alreadyMember"))
    }
    fun accept(token: String, invitationToken: String): ExpenseGroup = parseExpenseGroup(
        AccountApi.request("POST", "/api/groups/invitations/$invitationToken/accept", token = token).getJSONObject("group"))
    private fun parseInvitation(json: JSONObject) = GroupInvitationLink(json.getString("link"), json.getString("expiresAt"))
}

internal fun parseExpenseGroup(json: JSONObject) = ExpenseGroup(json.getString("id"), json.getString("name"), json.getString("currency"),
    json.optString("description"), json.optString("ownerId"), json.optInt("memberCount"))
