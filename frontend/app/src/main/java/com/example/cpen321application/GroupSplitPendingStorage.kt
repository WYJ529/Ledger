package com.example.cpen321application

import android.content.Context
import org.json.JSONObject
import org.json.JSONException
import java.io.IOException

/**
 * Saves only uncertain operations, scoped to this account and group. Using the session
 * preferences keeps them excluded from backups and lets normal sign-out clear them.
 */
internal class GroupSplitPendingStorage(context: Context, accountId: String, groupId: String) {
    private val prefs = context.applicationContext.getSharedPreferences(SessionStorage.FILE_NAME, Context.MODE_PRIVATE)
    private val key = "dutch_pending_$accountId.$groupId"

    fun load(): JSONObject = try {
        prefs.getString(key, null)?.let(::JSONObject) ?: JSONObject()
    } catch (_: JSONException) { JSONObject() }

    /** Call on Dispatchers.IO before sending the operation so recovery survives navigation. */
    fun saveBeforeRequest(value: JSONObject) {
        if (!prefs.edit().putString(key, value.toString()).commit()) {
            throw IOException("Could not save this pending action for recovery. No new request was sent.")
        }
    }

    /** Once a response establishes the outcome, a duplicate recovery would only be a read. */
    fun clear() { prefs.edit().remove(key).apply() }
}
