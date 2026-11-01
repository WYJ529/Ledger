package com.example.cpen321application

import android.os.Build
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val BACKEND_SAVE_BODY_LIMIT_BYTES = 8 * 1024 * 1024

/** Blocking calls to the account's three cloud save slots. Call them from Dispatchers.IO. */
internal object LedgerSavesApi : LedgerSavesRemote {
    override fun list(token: String): List<CloudSaveSlot> = parsed {
        val slots = AccountApi.request("GET", "/api/ledger-saves", token = token).getJSONArray("slots")
        (0 until slots.length()).map { slot(slots.getJSONObject(it)) }
    }

    override fun load(token: String, slot: Int): LedgerSnapshot = parsed {
        val save = AccountApi.request("GET", "/api/ledger-saves/$slot", token = token).getJSONObject("save")
        val records = save.getJSONArray("records")
        LedgerSnapshot(save.getString("baseCurrency"), (0 until records.length()).map { LedgerDatabase.recordFromJson(records.getJSONObject(it)) })
    }

    override fun save(token: String, slot: Int, snapshot: LedgerSnapshot, deviceName: String, replaces: Int?): CloudSaveSlot = parsed {
        val body = JSONObject().put("deviceName", deviceName).put("baseCurrency", snapshot.baseCurrency)
            .put("records", JSONArray(snapshot.records.map(LedgerDatabase::recordToJson)))
            .put("replaces", replaces ?: JSONObject.NULL)
        if (body.toString().toByteArray(Charsets.UTF_8).size > BACKEND_SAVE_BODY_LIMIT_BYTES) {
            throw LedgerException("This ledger is too large for one cloud save. Delete some transactions or notes and try again.")
        }
        slot(AccountApi.request("PUT", "/api/ledger-saves/$slot", body, token).getJSONObject("save"))
    }

    override fun delete(token: String, slot: Int, revision: Int) {
        AccountApi.request("DELETE", "/api/ledger-saves/$slot?revision=$revision", token = token)
    }

    private fun slot(json: JSONObject) = CloudSaveSlot(json.getInt("slot"), json.getInt("revision"), json.getString("savedAt"),
        json.optString("deviceName"), json.getString("baseCurrency"), json.getInt("recordCount"))

    private fun <T> parsed(block: () -> T): T = try { block() }
        catch (_: JSONException) { throw LedgerException("The server returned an unreadable cloud save. Nothing on this device was changed.") }
}

/** Shown on each save so the user can tell which phone made it. */
internal fun cloudSaveDeviceName(): String = Build.MODEL?.trim().orEmpty().ifBlank { "Android device" }
