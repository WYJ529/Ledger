package com.example.cpen321application

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

internal data class SavedSplitStart(val description: String, val paidBy: String, val units: List<SplitItemUnit>)

internal data class PendingSplitMutation(
    val key: String,
    val sessionId: String,
    val type: String,
    val version: Int,
    val unitId: String = "",
    val action: String = "",
) {
    val successMessage: String get() = when {
        type == "finalize" -> "Split finalized and added to group expenses."
        action == "claim" -> "Item claimed."
        else -> "Item released."
    }

    fun send(token: String): GroupSplitSession = if (type == "finalize") {
        GroupLiveSplitApi.finalize(token, sessionId, version, key)
    } else {
        GroupLiveSplitApi.claim(token, sessionId, unitId, action, version, key)
    }
}

internal class GroupSplitPendingState(
    requestId: String = UUID.randomUUID().toString(),
    payload: String = "",
    mutation: PendingSplitMutation? = null,
) {
    var startRequestId by mutableStateOf(requestId)
    var startPayload by mutableStateOf(payload)
    var operation by mutableStateOf(mutation)
    val isEmpty: Boolean get() = operation == null && startPayload.isBlank()

    fun clearStart() {
        startPayload = ""
        startRequestId = UUID.randomUUID().toString()
    }

    fun clear() {
        operation = null
        clearStart()
    }

    fun toJson(): JSONObject = JSONObject().put("startRequestId", startRequestId).put("startPayload", startPayload)
        .put("operationKey", operation?.key.orEmpty()).put("sessionId", operation?.sessionId.orEmpty())
        .put("type", operation?.type.orEmpty()).put("version", operation?.version ?: -1)
        .put("unitId", operation?.unitId.orEmpty()).put("action", operation?.action.orEmpty())

    companion object {
        val StateSaver = Saver<GroupSplitPendingState, String>(
            save = { it.toJson().toString() }, restore = { fromJson(JSONObject(it)) },
        )

        fun fromJson(json: JSONObject): GroupSplitPendingState {
            val key = json.optString("operationKey")
            val mutation = key.takeIf { it.isNotBlank() }?.let {
                PendingSplitMutation(it, json.optString("sessionId"), json.optString("type"),
                    json.optInt("version", -1), json.optString("unitId"), json.optString("action"))
            }
            return GroupSplitPendingState(json.optString("startRequestId").ifBlank { UUID.randomUUID().toString() },
                json.optString("startPayload"), mutation)
        }
    }
}

internal fun encodeSplitStart(description: String, paidBy: String, units: List<SplitItemUnit>): String {
    val encodedUnits = JSONArray()
    units.forEach { unit ->
        encodedUnits.put(JSONObject().put("unitId", unit.unitId).put("description", unit.description)
            .put("amountCents", unit.amountCents))
    }
    return JSONObject().put("description", description).put("paidBy", paidBy).put("units", encodedUnits).toString()
}

internal fun decodeSplitStart(payload: String): SavedSplitStart {
    val json = JSONObject(payload)
    val units = json.getJSONArray("units")
    return SavedSplitStart(json.getString("description"), json.getString("paidBy"), List(units.length()) {
        val unit = units.getJSONObject(it)
        SplitItemUnit(unit.getString("unitId"), unit.getString("description"), unit.getLong("amountCents"), null)
    })
}

internal fun restoredSplitStart(payload: String): SavedSplitStart? = if (payload.isBlank()) null else try {
    decodeSplitStart(payload)
} catch (_: JSONException) { null }
