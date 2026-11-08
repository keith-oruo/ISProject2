package ke.co.pesapath

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

internal object GoalReminderScheduler {
    const val KEY_USER_ID = "user_id"
    const val KEY_FREQUENCY = "frequency"
    private const val TAG_PREFIX = "goal-reminder-"

    fun schedule(context: Context, userId: String, frequency: String) {
        enqueueNext(context, userId, frequency, nextReminderTime(frequency))
    }

    fun cancel(context: Context, userId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(userId))
    }

    internal fun scheduleNext(context: Context, userId: String, frequency: String) {
        enqueueNext(context, userId, frequency, nextReminderTime(frequency))
    }

    private fun enqueueNext(context: Context, userId: String, frequency: String, atMillis: Long) {
        val request = OneTimeWorkRequestBuilder<GoalReminderWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_USER_ID, userId)
                    .putString(KEY_FREQUENCY, frequency)
                    .build()
            )
            .setInitialDelay((atMillis - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(tag(userId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "goal-reminder-$userId-$atMillis",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun nextReminderTime(frequency: String): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (frequency == "monthly") {
            next.set(Calendar.DAY_OF_MONTH, 1)
            if (!next.after(now)) next.add(Calendar.MONTH, 1)
        } else if (!next.after(now)) {
            next.add(Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis
    }

    private fun tag(userId: String) = "$TAG_PREFIX$userId"
}
