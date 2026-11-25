package com.cutedog.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cutedog.app.data.ReminderRepository
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
        val item = repository.get(id) ?: return
        val occurrence = intent.getLongExtra(ReminderScheduler.EXTRA_OCCURRENCE, item.dueAt)
        val token = intent.getStringExtra(ReminderScheduler.EXTRA_TOKEN) ?: ReminderScheduler.normalToken(occurrence)
        when (intent.action) {
            ReminderScheduler.ACTION_COMPLETE -> {
                // A recurring task was already advanced on its original alarm. A snooze
                // acknowledgement must never consume the next scheduled occurrence.
                if (item.repeat != "none" && token.startsWith("snooze:")) {
                    scheduler.dismissNotification(id, token)
                } else scheduler.completeOccurrence(id, occurrence, token)
                repository.acknowledgeDelivery(id, token)
            }
            ReminderScheduler.ACTION_SNOOZE -> {
                if (!item.completed) {
                    repository.setSnooze(id, System.currentTimeMillis() + 10 * 60_000L)
                    repository.acknowledgeDelivery(id, token)
                    repository.get(id)?.let(scheduler::schedule)
                }
                scheduler.dismissNotification(id, token)
            }
            ReminderScheduler.ACTION_DISMISS -> {
                repository.acknowledgeDelivery(id, token)
                scheduler.dismissNotification(id, token)
            }
            ReminderScheduler.ACTION_FIRE -> {
                if (item.completed || item.dueAt != occurrence) return
                repository.claimDelivery(id, ReminderScheduler.normalToken(occurrence), expectedDue = occurrence)
                ReminderTime.nextOccurrence(item, System.currentTimeMillis())?.let { next ->
                    repository.advance(id, item.dueAt, next)
                    repository.get(id)?.let(scheduler::schedule)
                }
                scheduler.retryPending()
            }
            ReminderScheduler.ACTION_SNOOZE_FIRE -> {
                if (item.completed || item.snoozedUntil != occurrence) return
                repository.claimDelivery(id, "snooze:$occurrence", expectedSnooze = occurrence)
                repository.setSnooze(id, 0)
                scheduler.retryPending()
            }
        }
        if (intent.action in setOf(ReminderScheduler.ACTION_COMPLETE, ReminderScheduler.ACTION_SNOOZE, ReminderScheduler.ACTION_DISMISS)) {
            dogReminders.removeToken(id, token)
        }
        context.sendBroadcast(Intent("com.cutedog.app.REMINDERS_CHANGED").setPackage(context.packageName))
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
