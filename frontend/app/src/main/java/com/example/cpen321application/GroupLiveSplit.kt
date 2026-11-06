package com.example.cpen321application

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
    val taxCents: Long = 0,
    val tipCents: Long = 0,
) {
    /** The items alone; tax and tip follow them when the split is finalized. */
    val subtotalCents: Long get() = amountCents - taxCents - tipCents
}

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

    fun start(token: String, groupId: String, start: SavedSplitStart, requestId: String): GroupSplitSession {
        val units = start.units
        require(units.isNotEmpty()) { "Add at least one receipt item unit." }
        val amountCents = units.sumOf { it.amountCents } + start.taxCents + start.tipCents
        val body = start.toJson().put("reviewed", true).put("requestId", requestId).put("amountCents", amountCents)
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
            taxCents = json.optLong("taxCents"),
            tipCents = json.optLong("tipCents"),
        )
    }
}

/** Each member's items with their share of tax and tip. Unclaimed items keep their share until someone claims them. */
internal fun splitAllocations(split: GroupSplitSession): Map<String, Long> {
    val items = split.units.groupBy { it.ownerId.orEmpty() }.mapValues { (_, units) -> units.sumOf { it.amountCents } }
    return proportionalShares(items, split.taxCents + split.tipCents)
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
    Text(tf("Paid by %1\$s · %2\$s", memberName[split.paidBy] ?: t("group member"), Money.display(split.amountCents, split.currency)))
    Text(tf("Assigned %1\$s · Unassigned %2\$s", Money.display(claimedCents, split.currency), Money.display(unclaimedCents, split.currency)))
    Text(tf("%1\$d / %2\$d units claimed", split.units.count { it.ownerId != null }, split.units.size), style = MaterialTheme.typography.bodySmall)
    if (split.taxCents + split.tipCents > 0) {
        Text(tf("Subtotal %1\$s · Tax %2\$s · Tip %3\$s", Money.display(split.subtotalCents, split.currency),
            Money.display(split.taxCents, split.currency), Money.display(split.tipCents, split.currency)))
        Text(t("Tax and tip are shared in proportion to the items each person claims."), style = MaterialTheme.typography.bodySmall)
    }
    split.units.forEach { unit ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(unit.description, fontWeight = FontWeight.Medium)
                Text(Money.display(unit.amountCents, split.currency), style = MaterialTheme.typography.bodySmall)
                Text(unit.ownerId?.let { tf("Claimed by %1\$s", memberName[it] ?: t("group member")) } ?: t("Unassigned"), style = MaterialTheme.typography.bodySmall)
            }
            when {
                split.status != "open" -> Text(t(split.status))
                unit.ownerId == currentUserId -> BarButton(onClick = { onClaim(unit, "unclaim") }, enabled = enabled) { Text(t("Release")) }
                unit.ownerId == null -> PrimaryBarButton(onClick = { onClaim(unit, "claim") }, enabled = enabled) { Text(t("Claim")) }
                else -> Text(t("Taken"))
            }
        }
    }
    val shares = splitAllocations(split)
    Text(t("Member allocations"), style = MaterialTheme.typography.titleMedium)
    members.forEach { member -> Text("${member.displayName}: ${Money.display(shares[member.id] ?: 0, split.currency)}") }
    if (split.status == "finalized") {
        Text(t("Finalized · saved to group expenses"), color = MaterialTheme.colorScheme.primary)
    } else if (isOwner) {
        val canFinalize = split.status == "open" && unclaimedCents == 0L && claimedCents == split.subtotalCents
        PrimaryBarButton(onClick = onFinalize, enabled = enabled && canFinalize,
            modifier = Modifier.fillMaxWidth()) { Text(t("Finalize split")) }
        if (unclaimedCents > 0) Text(t("Assign every unit before finalizing."), style = MaterialTheme.typography.bodySmall)
    }
}
