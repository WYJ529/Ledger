package com.example.cpen321application

import android.content.Context
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject

/**
 * Keeps the signed-in session across app restarts in private app storage.
 * The file is excluded from cloud backup and device transfer (see res/xml).
 */
internal class SessionStorage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): Session? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        val account = try {
            prefs.getString(KEY_ACCOUNT, null)?.let { parseAccount(JSONObject(it)) }
        } catch (_: JSONException) { null }
        if (account == null || expiresAt <= System.currentTimeMillis()) {
            clear()
            return null
        }
        return Session(token, expiresAt, account)
    }

    fun save(session: Session) {
        prefs.edit {
            putString(KEY_TOKEN, session.token)
            putLong(KEY_EXPIRES_AT, session.expiresAt)
            putString(KEY_ACCOUNT, session.account.toJson().toString())
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        const val FILE_NAME = "account_session"
        private const val KEY_TOKEN = "token"
        private const val KEY_EXPIRES_AT = "expiresAt"
        private const val KEY_ACCOUNT = "account"
    }
}
