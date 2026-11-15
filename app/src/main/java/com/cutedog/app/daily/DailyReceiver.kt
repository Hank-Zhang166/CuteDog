package com.cutedog.app.daily

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDateTime

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailyScheduler.ACTION) return
        val pending = goAsync()
        Thread({
            try {
                deliver(context.applicationContext)
            } finally {
                try { DailyScheduler(context).schedule() } finally { pending.finish() }
            }
        }, "daily-greeting").start()
    }

    private fun deliver(context: Context) {
        val settings = DailySettings(context)
        val now = LocalDateTime.now()
        if (!settings.enabled || DailyRules.isQuiet(now.hour, settings.quietStart, settings.quietEnd)) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "每日温暖问候", NotificationManager.IMPORTANCE_LOW).apply {
            description = "每天至多一次的静音问候，可在 CuteDog 设置中关闭"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        synchronized(DailyRepository.lock) {
            val repository = DailyRepository(context)
            val date = now.toLocalDate().toString()
            if (repository.wasGreeted(date)) return
            val quote = repository.today()
            val contentIntent = PendingIntent.getActivity(context, 84002,
                Intent().setClassName(context.packageName, "com.cutedog.app.MainActivity")
                    .putExtra("open_daily", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle("CuteDog · 给今天的你")
                .setContentText(quote.text)
                .setStyle(Notification.BigTextStyle().bigText(quote.text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
            try {
                manager.notify(84001, notification)
                repository.markGreeted(date)
            } catch (_: SecurityException) {
                // Permission may be withdrawn after the check. The in-app quote remains available.
            }
        }
    }

    companion object { private const val CHANNEL = "daily_greeting" }
}
