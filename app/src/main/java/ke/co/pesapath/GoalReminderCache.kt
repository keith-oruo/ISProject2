package ke.co.pesapath

import android.content.Context

internal object GoalReminderCache {
    private const val PREFERENCES = "goal_reminder_cache"

    fun saveSettings(context: Context, userId: String, enabled: Boolean, frequency: String) {
        preferences(context).edit()
            .putBoolean(key(userId, "enabled"), enabled)
            .putString(key(userId, "frequency"), frequency)
            .apply()
    }

    fun saveGoalCount(context: Context, userId: String, count: Int) {
        preferences(context).edit()
            .putInt(key(userId, "goal_count"), count)
            .apply()
    }

    fun isEnabled(context: Context, userId: String): Boolean =
        preferences(context).getBoolean(key(userId, "enabled"), false)

    fun frequency(context: Context, userId: String): String =
        preferences(context).getString(key(userId, "frequency"), "daily") ?: "daily"

    fun goalCount(context: Context, userId: String): Int =
        preferences(context).getInt(key(userId, "goal_count"), 0)

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun key(userId: String, field: String) = "$userId:$field"
}
