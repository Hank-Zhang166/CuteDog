package com.cutedog.app.reminder

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun completeFromDog(context: Context, task: DogReminder) {
    context.sendBroadcast(ReminderScheduler.intent(context, task.id, ReminderScheduler.ACTION_COMPLETE)
        .putExtra(ReminderScheduler.EXTRA_OCCURRENCE, task.occurrence)
        .putExtra(ReminderScheduler.EXTRA_TOKEN, task.token))
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

internal fun DogReminder.clock(): String {
    val time = token.removePrefix("snooze:").toLongOrNull() ?: occurrence
    return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(clockFormat)
}
