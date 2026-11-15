package com.cutedog.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cutedog.app.daily.DailyScheduler

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        ReminderExecutor.run {
            try {
                try { ReminderScheduler(context.applicationContext).restoreAll() }
                catch (error: Exception) { Log.w("CuteDogReminders", "Reminder restore failed: ${error.javaClass.simpleName}") }
                try { DailyScheduler(context.applicationContext).schedule() }
                catch (error: Exception) { Log.w("CuteDogReminders", "Daily restore failed: ${error.javaClass.simpleName}") }
            }
            finally { pending.finish() }
        }
    }

    companion object {
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
