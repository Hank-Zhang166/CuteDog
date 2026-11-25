package com.cutedog.app.reminder

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.cutedog.app.data.DeliveryRecord
import com.cutedog.app.data.ReminderRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Durable at-least-once delivery; stable notification identities prevent duplicate cards on retry. */
internal class ReminderOutbox(private val context: Context) {
    private val repository = ReminderRepository(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun retryPending(): Int = synchronized(deliveryLock) { retryPendingLocked() }

    private fun retryPendingLocked(): Int {
        val records = repository.retryableDeliveries()
        if (records.isEmpty()) return 0
        // Surface new occurrences in a visible dog even if notification permission is off.
        // Reconciliation of an already blocked/failed record must not repeat a dog prompt.
        records.asReversed().filter { it.status == "pending" }.forEach { task ->
            if (repository.get(task.reminderId)?.completed == false) {
                dogReminders.offer(DogReminder(task.reminderId, task.dueAt, task.token, task.title))
            }
        }
        manager.createNotificationChannel(NotificationChannel(ReminderReceiver.CHANNEL_ID, "事项提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "备忘事项到期、提前与稍后提醒"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        val blockedReason = when {
            Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> "通知权限尚未授予，授权后会补发"
            !manager.areNotificationsEnabled() -> "应用通知已关闭，开启后会补发"
            manager.getNotificationChannel(ReminderReceiver.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE -> "事项提醒通知渠道已关闭，开启后会补发"
            else -> null
        }
        if (blockedReason != null) {
            records.forEach { repository.updateDelivery(it.reminderId, it.token, "blocked", blockedReason) }
            return 0
        }
        val now = System.currentTimeMillis()
        val ready = records.filter { it.status != "failed" || now - it.lastAttemptAt >= 60_000 }
        if (ready.isEmpty()) return 0
        if (ready.size > 3) return sendSummary(ready)
        var sent = 0
        ready.forEach { record ->
            if (repository.get(record.reminderId)?.completed != false) return@forEach
            if (repository.pendingDeliveries().none { it.reminderId == record.reminderId && it.token == record.token }) return@forEach
            repository.beginDeliveryAttempt(record)
            try {
                manager.notify(record.reminderId.toString(), ReminderReceiver.NOTIFICATION_ID, notification(record))
                if (repository.get(record.reminderId)?.completed != false) {
                    manager.cancel(record.reminderId.toString(), ReminderReceiver.NOTIFICATION_ID)
                    return@forEach
                }
                repository.updateDelivery(record.reminderId, record.token, "sent")
                sent++
            } catch (_: SecurityException) {
                repository.updateDelivery(record.reminderId, record.token, "blocked", "通知权限在投递时被撤销，授权后会补发")
            } catch (_: RuntimeException) {
                repository.updateDelivery(record.reminderId, record.token, "failed", "系统暂时无法显示通知，请稍后重试")
            }
        }
        return sent
    }

    private fun sendSummary(records: List<DeliveryRecord>): Int {
        records.forEach(repository::beginDeliveryAttempt)
        return try {
            val open = Intent().setClassName(context.packageName, "com.cutedog.app.MainActivity")
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse("cutedog://missed-reminders"))
                .putExtra("show_missed_reminders", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val titles = records.take(3).joinToString("\n") { "${displayTime(it.dueAt)} · ${it.title}" }
            val notification = Notification.Builder(context, ReminderReceiver.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("${records.size} 条提醒等待查看")
                .setContentText("点击查看错过的提醒，已合并通知以减少打扰")
                .setStyle(Notification.BigTextStyle().bigText(titles + if (records.size > 3) "\n另有 ${records.size - 3} 条" else ""))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE).build()
            manager.notify("missed-summary", ReminderReceiver.NOTIFICATION_ID, notification)
            records.forEach { repository.updateDelivery(it.reminderId, it.token, "summarized", "已合并到错过提醒摘要，等待你查看") }
            records.size
        } catch (_: SecurityException) {
            records.forEach { repository.updateDelivery(it.reminderId, it.token, "blocked", "通知权限在投递时被撤销，授权后会补发") }; 0
        } catch (_: RuntimeException) {
            records.forEach { repository.updateDelivery(it.reminderId, it.token, "failed", "系统暂时无法显示提醒摘要，请稍后重试") }; 0
        }
    }

    private fun notification(record: DeliveryRecord): Notification {
        val content = if (record.isSnooze) "稍后提醒 · ${record.title}" else "${displayTime(record.dueAt)} · ${record.title}"
        val launch = Intent().setClassName(context.packageName, "com.cutedog.app.MainActivity")
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("cutedog://reminder/${record.reminderId}"))
            .putExtra(ReminderScheduler.EXTRA_ID, record.reminderId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(action: String) = PendingIntent.getBroadcast(context, 0,
            ReminderScheduler.intent(context, record.reminderId, action)
                .putExtra(ReminderScheduler.EXTRA_OCCURRENCE, record.dueAt).putExtra(ReminderScheduler.EXTRA_TOKEN, record.token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, ReminderReceiver.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .addExtras(android.os.Bundle().apply { putString(ReminderScheduler.EXTRA_TOKEN, record.token) })
            .setContentTitle(if (record.priority >= 3) "重要事项 · ${record.title}" else "小狗来提醒你啦")
            .setContentText(content).setStyle(Notification.BigTextStyle().bigText(content + if (record.details.isNotBlank()) "\n${record.details}" else ""))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setWhen(if (record.isSnooze) record.createdAt else record.dueAt).setShowWhen(true)
            .addAction(Notification.Action.Builder(null, "完成", action(ReminderScheduler.ACTION_COMPLETE)).build())
            .addAction(Notification.Action.Builder(null, "10 分钟后", action(ReminderScheduler.ACTION_SNOOZE)).build())
            .addAction(Notification.Action.Builder(null, "关闭", action(ReminderScheduler.ACTION_DISMISS)).build()).build()
    }

    companion object { private val deliveryLock = Any() }

    private fun displayTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
}
