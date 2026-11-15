package com.cutedog.app.reminder

import com.cutedog.app.data.Reminder
import java.time.Instant
import java.time.YearMonth
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object ReminderTime {
    fun alarmAt(item: Reminder): Long = item.dueAt - item.leadMinutes * 60_000L

    /** Next occurrence with a future notification time; skips missed repeats without notification floods. */
    fun nextOccurrence(item: Reminder, now: Long): Long? {
        if (item.repeat == "none") return null
        require(item.repeat in setOf("daily", "weekly", "monthly"))
        val zone = ZoneId.of(item.timeZoneId)
        val anchor = Instant.ofEpochMilli(item.dueAt).atZone(zone)
        val threshold = Instant.ofEpochMilli(now + item.leadMinutes * 60_000L).atZone(zone)
        val steps = when (item.repeat) {
            "daily" -> ChronoUnit.DAYS.between(anchor.toLocalDate(), threshold.toLocalDate()).coerceAtLeast(0)
            "weekly" -> (ChronoUnit.DAYS.between(anchor.toLocalDate(), threshold.toLocalDate()) / 7).coerceAtLeast(0)
            else -> ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(threshold)).coerceAtLeast(0)
        }.coerceAtLeast(1)
        var count = steps
        val wallTime = item.anchorTime.takeIf { it.isNotEmpty() }?.let(LocalTime::parse) ?: anchor.toLocalTime()
        var next = increment(anchor, count, item.repeat, item.anchorDay, wallTime)
        while (next.toInstant().toEpochMilli() <= now + item.leadMinutes * 60_000L) {
            next = increment(anchor, ++count, item.repeat, item.anchorDay, wallTime)
        }
        return next.toInstant().toEpochMilli()
    }

    private fun increment(anchor: ZonedDateTime, count: Long, repeat: String, anchorDay: Int, wallTime: LocalTime): ZonedDateTime = when (repeat) {
        "daily" -> anchor.toLocalDate().plusDays(count).atTime(wallTime).atZone(anchor.zone)
        "weekly" -> anchor.toLocalDate().plusWeeks(count).atTime(wallTime).atZone(anchor.zone)
        "monthly" -> {
            val month = YearMonth.from(anchor).plusMonths(count)
            val day = (anchorDay.takeIf { it > 0 } ?: anchor.dayOfMonth).coerceAtMost(month.lengthOfMonth())
            month.atDay(day).atTime(wallTime).atZone(anchor.zone)
        }
        else -> error("Unsupported repeat rule")
    }
}
