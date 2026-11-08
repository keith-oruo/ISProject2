package ke.co.pesapath

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

internal class GoalReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val userId = inputData.getString(GoalReminderScheduler.KEY_USER_ID) ?: return Result.failure()
        val requestedFrequency = inputData.getString(GoalReminderScheduler.KEY_FREQUENCY) ?: return Result.failure()
        if (!GoalReminderCache.isEnabled(applicationContext, userId)) return Result.success()
        val frequency = GoalReminderCache.frequency(applicationContext, userId)
        if (frequency != requestedFrequency) {
            GoalReminderScheduler.scheduleNext(applicationContext, userId, frequency)
            return Result.success()
        }

        if (GoalReminderCache.goalCount(applicationContext, userId) > 0 && notificationsAllowed()) {
            createNotificationChannel()
            val detail = "Take a small step toward one of your savings goals today."
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("A little reminder for your savings")
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(userId.hashCode(), notification)
        }
        GoalReminderScheduler.scheduleNext(applicationContext, userId, frequency)
        return Result.success()
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Savings goal reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminders to keep making progress on your savings goals"
            }
        )
    }

    private companion object {
        const val CHANNEL_ID = "savings_goal_reminders"
    }
}
