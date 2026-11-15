package com.cutedog.app.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, 0)
        if (id <= 0) return
        val pending = goAsync()
        ReminderExecutor.run {
            try { handle(context.applicationContext, intent, id) }
            catch (error: Exception) { Log.w("CuteDogReminders", "Reminder processing failed: ${error.javaClass.simpleName}") }
            finally { pending.finish() }
        }
    }

    private fun handle(context: Context, intent: Intent, id: Long) {
        val repository = ReminderRepository(context)
        val scheduler = ReminderScheduler(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val item = repository.get(id) ?: return
        val occurrence = intent.getLongExtra(ReminderScheduler.EXTRA_OCCURRENCE, item.dueAt)
        when (intent.action) {
            ReminderScheduler.ACTION_COMPLETE -> {
                if (item.repeat == "none" && item.dueAt == occurrence) repository.setCompleted(id, true)
                // Repeating reminders have already advanced; complete only this occurrence.
                repository.setSnooze(id, 0)
                repository.get(id)?.let(scheduler::schedule)
                manager.cancel(id.toString(), NOTIFICATION_ID)
                changed(context)
            }
            ReminderScheduler.ACTION_SNOOZE -> {
                if (!item.completed) {
                    repository.setSnooze(id, System.currentTimeMillis() + 10 * 60_000L)
                    repository.get(id)?.let(scheduler::schedule)
                }
                manager.cancel(id.toString(), NOTIFICATION_ID)
                changed(context)
            }
            ReminderScheduler.ACTION_DISMISS -> manager.cancel(id.toString(), NOTIFICATION_ID)
            ReminderScheduler.ACTION_FIRE -> {
                if (item.completed || item.dueAt != occurrence) return
                if (!repository.claimDelivery(id, ReminderScheduler.normalToken(occurrence), expectedDue = occurrence)) return
                deliver(context, item, occurrence)
                ReminderTime.nextOccurrence(item, System.currentTimeMillis())?.let { next ->
                    repository.advance(id, item.dueAt, next)
                    repository.get(id)?.let(scheduler::schedule)
                }
                changed(context)
            }
            ReminderScheduler.ACTION_SNOOZE_FIRE -> {
                if (item.completed || item.snoozedUntil != occurrence) return
                if (!repository.claimDelivery(id, "snooze:$occurrence", expectedSnooze = occurrence)) return
                repository.setSnooze(id, 0)
                deliver(context, item, item.dueAt, isSnooze = true)
                changed(context)
            }
        }
    }

    private fun changed(context: Context) {
        context.sendBroadcast(Intent("com.cutedog.app.REMINDERS_CHANGED").setPackage(context.packageName))
    }

    private fun deliver(context: Context, item: Reminder, occurrence: Long, isSnooze: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "事项提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "备忘事项到期、提前与稍后提醒"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
        val time = Instant.ofEpochMilli(occurrence).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
        val content = if (isSnooze) "稍后提醒 · ${item.title}" else "$time · ${item.title}"
        val launch = Intent().setClassName(context.packageName, "com.cutedog.app.MainActivity")
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("cutedog://reminder/${item.id}"))
            .putExtra(ReminderScheduler.EXTRA_ID, item.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun actionIntent(action: String) = PendingIntent.getBroadcast(context, 0,
            ReminderScheduler.intent(context, item.id, action).putExtra(ReminderScheduler.EXTRA_OCCURRENCE, occurrence),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (item.priority >= 3) "重要事项 · ${item.title}" else "小狗来提醒你啦")
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content + if (item.details.isNotBlank()) "\n${item.details}" else ""))
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setWhen(if (isSnooze) System.currentTimeMillis() else occurrence).setShowWhen(true)
            .addAction(Notification.Action.Builder(null, "完成", actionIntent(ReminderScheduler.ACTION_COMPLETE)).build())
            .addAction(Notification.Action.Builder(null, "10 分钟后", actionIntent(ReminderScheduler.ACTION_SNOOZE)).build())
            .addAction(Notification.Action.Builder(null, "关闭", actionIntent(ReminderScheduler.ACTION_DISMISS)).build())
            .build()
        val allowed = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed && manager.areNotificationsEnabled()) {
            try { manager.notify(item.id.toString(), NOTIFICATION_ID, notification) }
            catch (_: SecurityException) { /* Permission may have been revoked concurrently. */ }
        }
        // Existing overlay may show a bubble; never start a foreground service from an alarm.
        context.sendBroadcast(Intent(ReminderScheduler.EVENT_REMINDER).setPackage(context.packageName)
            .putExtra(ReminderScheduler.EXTRA_ID, item.id).putExtra("title", item.title)
            .putExtra("message", content))
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        internal const val NOTIFICATION_ID = 1
    }
}

internal object ReminderExecutor {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "cutedog-reminders") }
    fun run(block: () -> Unit) { executor.execute(block) }
}
