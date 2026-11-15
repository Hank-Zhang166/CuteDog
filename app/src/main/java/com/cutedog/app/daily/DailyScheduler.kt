package com.cutedog.app.daily

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime

class DailyScheduler(context: Context) {
    private val appContext = context.applicationContext

    fun schedule() {
        val manager = appContext.getSystemService(AlarmManager::class.java)
        val operation = PendingIntent.getBroadcast(appContext, 84001,
            Intent(appContext, DailyReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.cancel(operation)
        val settings = DailySettings(appContext)
        if (!settings.enabled) return
        val next = DailyRules.nextGreeting(ZonedDateTime.now(), settings.hour, settings.quietStart, settings.quietEnd)
        // A greeting is deliberately inexact and non-wakeup; reminders use a separate scheduler.
        manager.set(AlarmManager.RTC, next.toInstant().toEpochMilli(), operation)
    }

    companion object { internal const val ACTION = "com.cutedog.app.DAILY_GREETING" }
}
