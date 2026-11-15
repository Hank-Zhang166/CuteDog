package com.cutedog.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository

/** AlarmManager owns reminders independently of the UI and overlay service. */
class ReminderScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val repository = ReminderRepository(context)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun schedule(item: Reminder) {
        require(item.id > 0) { "Save the reminder before scheduling it" }
        cancelAlarms(item.id)
        if (item.completed) {
            dismissNotification(item.id)
            return
        }
        if (!repository.wasDelivered(item.id, normalToken(item.dueAt))) {
            scheduleAlarm(ReminderTime.alarmAt(item), operation(item.id, ACTION_FIRE, item.dueAt))
        }
        if (item.snoozedUntil > 0) {
            scheduleAlarm(item.snoozedUntil, operation(item.id, ACTION_SNOOZE_FIRE, item.snoozedUntil))
        }
    }

    fun cancel(id: Long) {
        cancelAlarms(id)
        dismissNotification(id)
    }

    private fun cancelAlarms(id: Long) {
        alarms.cancel(operation(id, ACTION_FIRE, 0))
        alarms.cancel(operation(id, ACTION_SNOOZE_FIRE, 0))
    }

    private fun dismissNotification(id: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(id.toString(), ReminderReceiver.NOTIFICATION_ID)
    }

    fun restoreAll() {
        val now = System.currentTimeMillis()
        repository.list().forEach { stored ->
            var item = stored
            // Recover if the process died between delivery and writing the next recurrence.
            if (!item.completed && item.repeat != "none" && repository.wasDelivered(item.id, normalToken(item.dueAt))) {
                ReminderTime.nextOccurrence(item, now)?.let { next ->
                    if (repository.advance(item.id, item.dueAt, next)) item = item.copy(dueAt = next)
                }
            }
            schedule(item)
        }
    }

    private fun scheduleAlarm(timestamp: Long, operation: PendingIntent) {
        val trigger = timestamp.coerceAtLeast(System.currentTimeMillis() + 1000)
        if (canScheduleExact()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
                return
            } catch (_: SecurityException) {
                // Exact-alarm access can be revoked after canScheduleExactAlarms().
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
    }

    private fun operation(id: Long, action: String, occurrence: Long): PendingIntent =
        PendingIntent.getBroadcast(context, 0, intent(context, id, action).putExtra(EXTRA_OCCURRENCE, occurrence),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val ACTION_FIRE = "com.cutedog.app.reminder.FIRE"
        const val ACTION_SNOOZE_FIRE = "com.cutedog.app.reminder.SNOOZE_FIRE"
        const val ACTION_COMPLETE = "com.cutedog.app.reminder.COMPLETE"
        const val ACTION_SNOOZE = "com.cutedog.app.reminder.SNOOZE"
        const val ACTION_DISMISS = "com.cutedog.app.reminder.DISMISS"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_OCCURRENCE = "occurrence_at"
        const val EVENT_REMINDER = "com.cutedog.app.REMINDER_EVENT"
        internal fun normalToken(due: Long) = "due:$due"
        internal fun intent(context: Context, id: Long, action: String) = Intent(context, ReminderReceiver::class.java)
            .setAction(action).setData(Uri.parse("cutedog://reminder/$id/${action.substringAfterLast('.')}"))
            .putExtra(EXTRA_ID, id)
    }
}
