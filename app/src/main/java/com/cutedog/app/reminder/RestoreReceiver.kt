package com.cutedog.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cutedog.app.daily.DailyScheduler

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        ReminderExecutor.run {
            try {
                ReminderScheduler(context.applicationContext).restoreAll()
                DailyScheduler(context.applicationContext).schedule()
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
