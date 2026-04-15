package com.example.leitnerbox

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.math.max

private const val dueReminderChannelId = "due_cards_reminders"
private const val dueReminderWorkName = "due_cards_reminder_work"
private const val dueReminderNotificationId = 1001

class DueCardsReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val sharedPreferences = applicationContext.getSharedPreferences(
            cardsPrefsName,
            Context.MODE_PRIVATE
        )
        val cards = loadCards(sharedPreferences)
        val now = System.currentTimeMillis()
        val dueCount = cards.count { it.nextReviewAt <= now }

        if (dueCount > 0) {
            postDueCardsNotification(dueCount)
        }

        scheduleDueReminder(applicationContext, cards)
        return Result.success()
    }

    private fun postDueCardsNotification(dueCount: Int) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager = NotificationManagerCompat.from(applicationContext)
        ensureNotificationChannel(notificationManager)

        val launchIntent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, dueReminderChannelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Cards are due")
            .setContentText(
                "$dueCount card${if (dueCount == 1) "" else "s"} ready to review in Leitner Box."
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(dueReminderNotificationId, notification)
    }

    private fun ensureNotificationChannel(notificationManager: NotificationManagerCompat) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            dueReminderChannelId,
            "Due cards",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Reminders when flashcards are due for review."
        }

        notificationManager.createNotificationChannel(channel)
    }
}

fun scheduleDueReminder(context: Context, cards: List<FlashCard>) {
    val nextDueAt = cards
        .map { it.nextReviewAt }
        .filter { it > System.currentTimeMillis() }
        .minOrNull()

    val workManager = WorkManager.getInstance(context)

    if (nextDueAt == null) {
        workManager.cancelUniqueWork(dueReminderWorkName)
        return
    }

    val delayMillis = max(nextDueAt - System.currentTimeMillis(), 0L)
    val request = OneTimeWorkRequestBuilder<DueCardsReminderWorker>()
        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        .build()

    workManager.enqueueUniqueWork(
        dueReminderWorkName,
        ExistingWorkPolicy.REPLACE,
        request
    )
}
