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
            dogReminders.remove(item.id)
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
        dogReminders.remove(id)
        cancelAlarms(id)
        dismissNotification(id)
    }

    private fun cancelAlarms(id: Long) {
        alarms.cancel(operation(id, ACTION_FIRE, 0))
        alarms.cancel(operation(id, ACTION_SNOOZE_FIRE, 0))
    }

    internal fun dismissNotification(id: Long, token: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val tag = id.toString()
        if (token != null) {
            val card = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == ReminderReceiver.NOTIFICATION_ID }
            val activeToken = card?.notification?.extras?.getString(EXTRA_TOKEN)
            if (activeToken != null && activeToken != token) return
        }
        manager.cancel(tag, ReminderReceiver.NOTIFICATION_ID)
    }

    fun restoreAll() {
        val now = System.currentTimeMillis()
        repository.list().forEach { stored ->
            var item = stored
            if (!item.completed && ReminderTime.alarmAt(item) <= now && !repository.wasDelivered(item.id, normalToken(item.dueAt))) {
                repository.claimDelivery(item.id, normalToken(item.dueAt), expectedDue = item.dueAt)
            }
            if (!item.completed && item.snoozedUntil in 1..now) {
                repository.claimDelivery(item.id, "snooze:${item.snoozedUntil}", expectedSnooze = item.snoozedUntil)
                repository.setSnooze(item.id, 0)
                item = item.copy(snoozedUntil = 0)
            }
            // A captured outbox entry survives a process crash even if notification delivery did not.
            if (!item.completed && item.repeat != "none" && repository.wasDelivered(item.id, normalToken(item.dueAt))) {
                ReminderTime.nextOccurrence(item, now)?.let { next ->
                    if (repository.advance(item.id, item.dueAt, next)) item = item.copy(dueAt = next)
                }
            }
            schedule(item)
        }
        retryPending()
    }

    fun retryPending(): Int = ReminderOutbox(context).retryPending()

    fun completeOccurrence(id: Long, occurrenceAt: Long? = null, deliveryToken: String? = null) {
        val db = repository.database
        db.beginTransaction()
        try {
            val item = repository.get(id)
            if (item != null) {
                val occurrence = occurrenceAt ?: item.dueAt
                if (item.repeat == "none") {
                    if (item.dueAt == occurrence) repository.setCompleted(id, true)
                } else if (item.dueAt == occurrence) {
                    repository.claimDelivery(id, normalToken(occurrence), expectedDue = occurrence)
                    ReminderTime.nextOccurrence(item, System.currentTimeMillis())?.let { repository.advance(id, occurrence, it) }
                }
                // Capture and acknowledge atomically: a concurrent retry cannot see a future occurrence as pending.
                repository.acknowledgeDelivery(id, normalToken(occurrence))
                if (item.dueAt == occurrence) repository.setSnooze(id, 0)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        dogReminders.remove(id, occurrenceAt)
        repository.get(id)?.let(::schedule)
        dismissNotification(id, deliveryToken)
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
        const val EXTRA_TOKEN = "delivery_token"
        internal fun normalToken(due: Long) = "due:$due"
        internal fun intent(context: Context, id: Long, action: String) = Intent(context, ReminderReceiver::class.java)
            .setAction(action).setData(Uri.parse("cutedog://reminder/$id/${action.substringAfterLast('.')}"))
            .putExtra(EXTRA_ID, id)
    }
}
