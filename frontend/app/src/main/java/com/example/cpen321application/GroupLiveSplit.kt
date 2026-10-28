package com.example.cpen321application

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** A single receipt item unit. Each unit has exactly one claimant or is unassigned. */
internal data class SplitItemUnit(
    val unitId: String,
    val description: String,
    val amountCents: Long,
    val ownerId: String?,
)

/** Authoritative session state returned by the Feature 3 REST API. */
internal data class GroupSplitSession(
    val id: String,
    val groupId: String,
    val description: String,
    val amountCents: Long,
    val currency: String,
    val paidBy: String,
    val units: List<SplitItemUnit>,
    val status: String,
    val version: Int,
    val sequence: Long,
    val finalizedExpenseId: String?,
)

internal data class GroupSplitSnapshot(
    val groupId: String,
    val splitSessions: List<GroupSplitSession>,
)

internal data class SplitOperation(
    val operationKey: String,
    val sessionId: String,
    val groupId: String,
    val type: String,
    val version: Int,
    val sequence: Long,
    val state: String,
)

/**
 * REST client for Let's Go Dutch. The backend validates the caller and group membership.
 * Mutations remain HTTP requests; authenticated Socket.IO only delivers committed state.
 */
internal object GroupLiveSplitApi {
    fun snapshot(token: String, groupId: String): GroupSplitSnapshot {
        val json = AccountApi.request("GET", "/api/groups/$groupId/snapshot", token = token)
        val sessions = json.optJSONArray("splitSessions") ?: JSONArray()
        return GroupSplitSnapshot(
            groupId = json.getString("groupId"),
            splitSessions = List(sessions.length()) { parseSession(sessions.getJSONObject(it)) },
        )
    }

    fun start(
        token: String,
        groupId: String,
        description: String,
        paidBy: String,
        units: List<SplitItemUnit>,
        requestId: String,
    ): GroupSplitSession {
        require(units.isNotEmpty()) { "Add at least one receipt item unit." }
        val amountCents = units.sumOf { it.amountCents }
        val items = JSONArray()
        units.forEach { unit ->
            items.put(JSONObject()
                .put("unitId", unit.unitId)
                .put("description", unit.description)
                .put("amountCents", unit.amountCents))
        }
        val body = JSONObject()
            .put("reviewed", true)
            .put("requestId", requestId)
            .put("description", description)
            .put("amountCents", amountCents)
            .put("paidBy", paidBy)
            .put("units", items)
        return parseSession(AccountApi.request("POST", "/api/groups/$groupId/split-sessions", body, token)
            .getJSONObject("session"))
    }

    fun claim(
        token: String,
        sessionId: String,
        unitId: String,
        action: String,
        version: Int,
        operationKey: String,
    ): GroupSplitSession {
        val body = JSONObject()
            .put("unitId", unitId)
            .put("action", action)
            .put("version", version)
            .put("operationKey", operationKey)
        return parseSession(AccountApi.request("POST", "/api/split-sessions/$sessionId/claims", body, token)
            .getJSONObject("session"))
    }

    fun finalize(token: String, sessionId: String, version: Int, operationKey: String): GroupSplitSession {
        val body = JSONObject().put("version", version).put("operationKey", operationKey)
        return parseSession(AccountApi.request("POST", "/api/split-sessions/$sessionId/finalize", body, token)
            .getJSONObject("session"))
    }

    fun operation(token: String, operationKey: String): SplitOperation {
        val operation = AccountApi.request("GET", "/api/operations/$operationKey", token = token)
            .getJSONObject("operation")
        return SplitOperation(
            operationKey = operation.getString("operationKey"),
            sessionId = operation.getString("sessionId"),
            groupId = operation.getString("groupId"),
            type = operation.getString("type"),
            version = operation.getInt("version"),
            sequence = operation.getLong("sequence"),
            state = operation.getString("state"),
        )
    }

    fun parseSession(json: JSONObject): GroupSplitSession {
        val units = json.getJSONArray("units")
        return GroupSplitSession(
            id = json.getString("id"),
            groupId = json.getString("groupId"),
            description = json.getString("description"),
            amountCents = json.getLong("amountCents"),
            currency = json.getString("currency"),
            paidBy = json.getString("paidBy"),
            units = List(units.length()) { index ->
                val unit = units.getJSONObject(index)
                SplitItemUnit(
                    unitId = unit.getString("unitId"),
                    description = unit.getString("description"),
                    amountCents = unit.getLong("amountCents"),
                    ownerId = unit.optString("ownerId").takeIf { it.isNotBlank() && it != "null" },
                )
            },
            status = json.getString("status"),
            version = json.getInt("version"),
            sequence = json.getLong("sequence"),
            finalizedExpenseId = json.optString("finalizedExpenseId").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

@Composable
internal fun SplitSessionContent(
    split: GroupSplitSession,
    members: List<GroupMember>,
    currentUserId: String,
    isOwner: Boolean,
    enabled: Boolean,
    onClaim: (SplitItemUnit, String) -> Unit,
    onFinalize: () -> Unit,
) {
    val memberName = members.associate { it.id to it.displayName }
    val claimedCents = split.units.filter { it.ownerId != null }.sumOf { it.amountCents }
    val unclaimedCents = split.units.filter { it.ownerId == null }.sumOf { it.amountCents }
    Text(split.description, style = MaterialTheme.typography.titleLarge)
    Text("Paid by ${memberName[split.paidBy] ?: "group member"} · ${displayMoney(split.currency, split.amountCents)}")
    Text("Assigned ${displayMoney(split.currency, claimedCents)} · Unassigned ${displayMoney(split.currency, unclaimedCents)}")
    Text("${split.units.count { it.ownerId != null }} / ${split.units.size} units claimed · session version ${split.version}", style = MaterialTheme.typography.bodySmall)
    split.units.forEach { unit ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(unit.description, fontWeight = FontWeight.Medium)
                Text(displayMoney(split.currency, unit.amountCents), style = MaterialTheme.typography.bodySmall)
                Text(unit.ownerId?.let { "Claimed by ${memberName[it] ?: "group member"}" } ?: "Unassigned", style = MaterialTheme.typography.bodySmall)
            }
            when {
                split.status != "open" -> Text(split.status.replaceFirstChar(Char::uppercase))
                unit.ownerId == currentUserId -> OutlinedButton(onClick = { onClaim(unit, "unclaim") }, enabled = enabled) { Text("Release") }
                unit.ownerId == null -> Button(onClick = { onClaim(unit, "claim") }, enabled = enabled) { Text("Claim") }
                else -> Text("Taken")
            }
        }
    }
    val shares = split.units.filter { it.ownerId != null }.groupBy { it.ownerId!! }.mapValues { (_, units) -> units.sumOf { it.amountCents } }
    Text("Member allocations", style = MaterialTheme.typography.titleMedium)
    members.forEach { member -> Text("${member.displayName}: ${displayMoney(split.currency, shares[member.id] ?: 0)}") }
    if (split.status == "finalized") {
        Text("Finalized · ${split.finalizedExpenseId?.let { "expense $it" } ?: "saved to group expenses"}", color = MaterialTheme.colorScheme.primary)
    } else if (isOwner) {
        val canFinalize = split.status == "open" && unclaimedCents == 0L && claimedCents == split.amountCents
        Button(onClick = onFinalize, enabled = enabled && canFinalize,
            modifier = Modifier.fillMaxWidth()) { Text("Finalize split") }
        if (unclaimedCents > 0) Text("Assign every unit before finalizing.", style = MaterialTheme.typography.bodySmall)
    }
}
