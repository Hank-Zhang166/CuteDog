package com.cutedog.app.reminder

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderDraftParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.parse("2026-11-15T10:00+08:00[Asia/Shanghai]").toInstant().toEpochMilli()
    private fun parse(text: String) = ReminderDraftParser.parse(text, now, zone)
    private fun time(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    @Test fun extractsRequestedChineseExampleForConfirmation() {
        val result = parse("明天下午三点记得给老师发邮件。")!!
        assertEquals("给老师发邮件", result.title)
        assertEquals(time("2026-11-16T15:00+08:00[Asia/Shanghai]"), result.dueAt)
        assertTrue(result.explanation.contains("确认"))
    }
    @Test fun supportsHalfHourAndChineseMinutes() {
        assertEquals(time("2026-11-16T08:30+08:00[Asia/Shanghai]"), parse("明天上午八点半散步")!!.dueAt)
        assertEquals(time("2026-11-16T08:25+08:00[Asia/Shanghai]"), parse("明天上午八点二十五分散步")!!.dueAt)
    }
    @Test fun supports24HourClock() {
        assertEquals(time("2026-11-17T18:45+08:00[Asia/Shanghai]"), parse("后天18:45接朋友")!!.dueAt)
    }
    @Test fun neverSilentlyMovesPastTimeToTomorrow() { assertNull(parse("今天上午九点喝水")) }
    @Test fun rejectsImpossibleCalendarDate() { assertNull(parse("2026-02-30 12:00 看牙")) }
    @Test fun rejectsImpossibleClockTime() { assertNull(parse("明天25:70散步")) }
    @Test fun doesNotInventTimeForVagueText() { assertNull(parse("最近提醒我锻炼")) }
    @Test fun needsNonemptyReminderTitle() { assertNull(parse("明天下午三点")) }
    @Test fun supportsExplicitDate() {
        assertEquals(time("2026-12-01T12:00+08:00[Asia/Shanghai]"), parse("2026-12-01 12:00 交房租")!!.dueAt)
    }
    @Test fun rejectsNonexistentDstWallTime() {
        val result = ReminderDraftParser.parse("2027-03-14 02:30 开会", now, ZoneId.of("America/New_York"))
        assertNull(result)
    }
}
