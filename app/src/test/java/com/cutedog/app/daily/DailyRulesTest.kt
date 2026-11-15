package com.cutedog.app.daily

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyRulesTest {
    @Test fun commonAndLeapYearsHaveNoRepeat() {
        for (year in listOf(2024, 2025, 2026, 2028, 2100)) {
            val first = LocalDate.of(year, 1, 1)
            val ids = (0 until first.lengthOfYear()).map { DailyRules.quoteId(first.plusDays(it.toLong()), 372) }
            assertEquals(first.lengthOfYear(), ids.toSet().size)
            assertTrue(ids.all { it in 0 until 372 })
        }
    }

    @Test fun dateAssignmentIsStableAndYearRotationChanges() {
        val date = LocalDate.of(2026, 11, 15)
        assertEquals(136, DailyRules.quoteId(date, 372))
        assertEquals(173, DailyRules.quoteId(date.plusYears(1), 372))
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteLibraryIsRejected() { DailyRules.quoteId(LocalDate.of(2026, 1, 1), 365) }

    @Test fun quietHoursCrossMidnightAndRespectBoundaries() {
        assertTrue(DailyRules.isQuiet(22, 22, 8))
        assertTrue(DailyRules.isQuiet(0, 22, 8))
        assertTrue(DailyRules.isQuiet(7, 22, 8))
        assertFalse(DailyRules.isQuiet(8, 22, 8))
        assertFalse(DailyRules.isQuiet(21, 22, 8))
        assertTrue(DailyRules.isQuiet(13, 12, 14))
        assertFalse(DailyRules.isQuiet(14, 12, 14))
        assertFalse(DailyRules.isQuiet(12, 12, 12))
    }

    @Test fun greetingIsScheduledTodayOrNextDayBasedOnClock() {
        val morning = ZonedDateTime.of(2026, 11, 15, 8, 10, 0, 0, ZoneId.of("Asia/Shanghai"))
        assertEquals(morning.withHour(9).withMinute(0), DailyRules.nextGreeting(morning, 9, 22, 8))
        val atDelivery = morning.withHour(9).withMinute(0)
        assertEquals(atDelivery.plusDays(1), DailyRules.nextGreeting(atDelivery, 9, 22, 8))
    }

    @Test fun lateGreetingDefersToQuietEndAndSurvivesMorningReschedule() {
        val noon = ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        assertEquals(noon.plusDays(1).withHour(8), DailyRules.nextGreeting(noon, 23, 22, 8))
        val nextMorning = noon.plusDays(1).withHour(7)
        assertEquals(nextMorning.withHour(8), DailyRules.nextGreeting(nextMorning, 23, 22, 8))
    }

    @Test fun springDstGapDoesNotProducePastOrInvalidTime() {
        val before = ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        val next = DailyRules.nextGreeting(before, 2, 0, 0)
        assertEquals(3, next.hour)
        assertEquals(before.toLocalDate(), next.toLocalDate())
        assertTrue(next.isAfter(before))
    }

    @Test fun fallDstKeepsLocalChosenHour() {
        val before = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        val next = DailyRules.nextGreeting(before, 9, 22, 8)
        assertEquals(LocalDate.of(2026, 10, 25), next.toLocalDate())
        assertEquals(9, next.hour)
        assertEquals(1, next.offset.totalSeconds / 3600)
    }
}
