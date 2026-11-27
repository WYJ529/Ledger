package com.example.cpen321application

import android.content.Context
import androidx.core.content.edit

internal enum class NotificationPreference(val title: String, val initialValue: Boolean) {
    ExpenseActivity("Expense activity", true),
    Settlements("Payments & settlements", true),
    Invitations("Group invitations", true),
    Reminders("Balance reminders", false),
    WeeklySummary("Weekly summary", false),
}

/** Notification choices are per-account, device-local mock preferences. No push permission or delivery. */
internal class AccountPreferences(context: Context, accountId: String) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    companion object { const val FILE_NAME = "account_preferences" }
    private val prefix = "$accountId."
    fun enabled(preference: NotificationPreference): Boolean = prefs.getBoolean(prefix + preference.name, preference.initialValue)
    fun setEnabled(preference: NotificationPreference, enabled: Boolean) {
        prefs.edit { putBoolean(prefix + preference.name, enabled) }
    }
    fun clear() {
        prefs.edit { prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) } }
    }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(prefix + "notifications", true)
        set(value) { prefs.edit { putBoolean(prefix + "notifications", value) } }
}
