package com.cutedog.app.daily

import java.time.LocalDate
import java.time.ZonedDateTime

/** Pure calendar rules. IDs are stable asset line numbers, starting at zero. */
object DailyRules {
    const val MINIMUM_QUOTE_COUNT = 366

    fun quoteId(date: LocalDate, count: Int): Int {
        require(count >= MINIMUM_QUOTE_COUNT) { "需要至少 366 条短句" }
        return Math.floorMod(date.year.toLong() * 37L + date.dayOfYear - 1L, count.toLong()).toInt()
    }

    fun isQuiet(hour: Int, quietStart: Int, quietEnd: Int): Boolean {
        require(hour in 0..23 && quietStart in 0..23 && quietEnd in 0..23)
        return when {
            quietStart == quietEnd -> false
            quietStart < quietEnd -> hour >= quietStart && hour < quietEnd
            else -> hour >= quietStart || hour < quietEnd
        }
    }

    /** Calendar-based scheduling preserves the selected local hour across DST changes. */
    fun nextGreeting(now: ZonedDateTime, hour: Int, quietStart: Int, quietEnd: Int): ZonedDateTime {
        require(hour in 0..23 && quietStart in 0..23 && quietEnd in 0..23)
        // Yesterday's requested hour may have been postponed into this morning's quiet-end.
        var day = now.toLocalDate().minusDays(1)
        repeat(4) {
            var candidate = day.atTime(hour, 0).atZone(now.zone)
            if (isQuiet(candidate.hour, quietStart, quietEnd)) {
                val endDay = if (quietStart > quietEnd && candidate.hour >= quietStart) {
                    candidate.toLocalDate().plusDays(1)
                } else candidate.toLocalDate()
                candidate = endDay.atTime(quietEnd, 0).atZone(now.zone)
            }
            if (candidate.isAfter(now)) return candidate
            day = day.plusDays(1)
        }
        error("无法计算下次问候时间")
    }
}
