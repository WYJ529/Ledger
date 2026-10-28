package com.example.cpen321application

import android.content.Context
import androidx.core.content.edit

internal enum class NotificationPreference(val title: String, val description: String, val initialValue: Boolean) {
    ExpenseActivity("Expense activity", "When a shared expense is added, changed, or deleted.", true),
    Settlements("Payments & settlements", "When a group member records a payment or settles up.", true),
    Invitations("Group invitations", "When you are invited to a group.", true),
    Reminders("Balance reminders", "A gentle reminder about unsettled balances.", false),
    WeeklySummary("Weekly summary", "A weekly overview of spending and shared balances.", false),
}

/** Notification choices are per-account, device-local mock preferences. No push permission or delivery. */
internal class AccountPreferences(context: Context, accountId: String) {
    private val prefs = context.applicationContext.getSharedPreferences("account_preferences", Context.MODE_PRIVATE)
    private val prefix = "$accountId."
    fun enabled(preference: NotificationPreference): Boolean = prefs.getBoolean(prefix + preference.name, preference.initialValue)
    fun setEnabled(preference: NotificationPreference, enabled: Boolean) {
        prefs.edit { putBoolean(prefix + preference.name, enabled) }
    }
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(prefix + "notifications", true)
        set(value) { prefs.edit { putBoolean(prefix + "notifications", value) } }
}
