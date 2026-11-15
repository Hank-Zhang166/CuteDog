package com.cutedog.app.reminder

import com.cutedog.app.data.Reminder
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTimeTest {
    private fun ms(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()
    private fun item(time: String, repeat: String = "none", lead: Int = 0, anchor: Int = 0) = Reminder(
        id = 1, title = "给老师发邮件", dueAt = ms(time), repeat = repeat, leadMinutes = lead,
        timeZoneId = ZonedDateTime.parse(time).zone.id, anchorDay = anchor,
    )

    @Test fun oneTimeDoesNotRepeat() {
        assertNull(ReminderTime.nextOccurrence(item("2026-11-15T15:00+08:00[Asia/Shanghai]"), 0))
    }

    @Test fun advanceNoticeUsesRealMinutes() {
        val reminder = item("2026-11-15T15:00+08:00[Asia/Shanghai]", lead = 30)
        assertEquals(ms("2026-11-15T14:30+08:00[Asia/Shanghai]"), ReminderTime.alarmAt(reminder))
    }

    @Test fun dailyPreservesLocalTimeAcrossSpringDst() {
        val reminder = item("2026-03-07T09:00-05:00[America/New_York]", "daily")
        assertEquals(ms("2026-03-08T09:00-04:00[America/New_York]"), ReminderTime.nextOccurrence(reminder, reminder.dueAt))
    }

    @Test fun dailyPreservesLocalTimeAcrossFallDst() {
        val reminder = item("2026-10-31T09:00-04:00[America/New_York]", "daily")
        assertEquals(ms("2026-11-01T09:00-05:00[America/New_York]"), ReminderTime.nextOccurrence(reminder, reminder.dueAt))
    }

    @Test fun monthlyClampsWithoutLosingOriginalDay() {
        val january = item("2026-01-31T09:00+08:00[Asia/Shanghai]", "monthly", anchor = 31)
        val february = ReminderTime.nextOccurrence(january, january.dueAt)!!
        assertEquals(ms("2026-02-28T09:00+08:00[Asia/Shanghai]"), february)
        assertEquals(ms("2026-03-31T09:00+08:00[Asia/Shanghai]"), ReminderTime.nextOccurrence(january.copy(dueAt = february), february))
    }

    @Test fun monthlySupportsLeapYear() {
        val reminder = item("2028-01-31T09:00+08:00[Asia/Shanghai]", "monthly", anchor = 31)
        assertEquals(ms("2028-02-29T09:00+08:00[Asia/Shanghai]"), ReminderTime.nextOccurrence(reminder, reminder.dueAt))
    }

    @Test fun skipsMissedDailyOccurrencesWithoutFlood() {
        val reminder = item("2020-01-01T09:00+08:00[Asia/Shanghai]", "daily")
        val now = ms("2026-11-15T18:00+08:00[Asia/Shanghai]")
        assertEquals(ms("2026-11-16T09:00+08:00[Asia/Shanghai]"), ReminderTime.nextOccurrence(reminder, now))
    }

    @Test fun nextNotificationMustBeAfterNowIncludingAdvanceNotice() {
        val reminder = item("2026-11-14T09:00+08:00[Asia/Shanghai]", "daily", lead = 60)
        val now = ms("2026-11-15T08:30+08:00[Asia/Shanghai]")
        assertEquals(ms("2026-11-16T09:00+08:00[Asia/Shanghai]"), ReminderTime.nextOccurrence(reminder, now))
    }

    @Test fun weeklyKeepsDayOfWeek() {
        val reminder = item("2026-11-01T09:00+08:00[Asia/Shanghai]", "weekly")
        assertEquals(ms("2026-11-22T09:00+08:00[Asia/Shanghai]"), ReminderTime.nextOccurrence(reminder, ms("2026-11-15T10:00+08:00[Asia/Shanghai]")))
    }

    @Test(expected = IllegalArgumentException::class) fun invalidRepeatRejected() {
        item("2026-11-01T09:00+08:00[Asia/Shanghai]", "sometimes").validated()
    }

    @Test(expected = IllegalArgumentException::class) fun negativeAdvanceRejected() {
        item("2026-11-01T09:00+08:00[Asia/Shanghai]", lead = -1).validated()
    }

    @Test(expected = IllegalArgumentException::class) fun blankTitleRejected() {
        item("2026-11-01T09:00+08:00[Asia/Shanghai]").copy(title = "  ").validated()
    }
}
