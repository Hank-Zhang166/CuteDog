package com.cutedog.app.pet.behavior

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class GrowthProgressTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    @Test fun allToysAreAvailableFromFirstMeeting() {
        val stats = GrowthProgress.snapshot(1000, 0, 1000, zone)
        assertEquals(setOf("ball", "treat"), stats.toysUnlocked)
        assertEquals(listOf("初次相遇"), stats.milestones)
    }
    @Test fun longAbsenceNeverRemovesProgressOrToys() {
        val first = at("2025-01-01T12:00+08:00[Asia/Shanghai]")
        val before = GrowthProgress.snapshot(first, 60, first + 31 * 86_400_000L, zone)
        val after = GrowthProgress.snapshot(first, 60, first + 500 * 86_400_000L, zone)
        assertTrue(after.milestones.containsAll(before.milestones))
        assertEquals(before.toysUnlocked, after.toysUnlocked)
        assertEquals(60L, after.interactions)
    }
    @Test fun daysUseCalendarAcrossDaylightSavingRatherThan24HourBuckets() {
        val first = at("2026-03-07T23:30-05:00[America/New_York]")
        val now = at("2026-03-08T23:00-04:00[America/New_York]")
        assertEquals(1, GrowthProgress.snapshot(first, 0, now, ZoneId.of("America/New_York")).daysTogether)
    }
    @Test fun clockRollbackCannotProduceNegativeDaysOrRemoveInteractionCount() {
        val stats = GrowthProgress.snapshot(100_000, 20, 1_000, zone)
        assertEquals(0, stats.daysTogether)
        assertEquals(20L, stats.interactions)
        assertTrue(stats.milestones.contains("熟悉的问候"))
    }
    @Test fun repeatedTouchMoveEventsCountAsOneInteraction() {
        assertEquals(11L, GrowthProgress.interactionCount(10, 1000, 4000))
        assertEquals(11L, GrowthProgress.interactionCount(11, 4000, 4100))
        assertEquals(12L, GrowthProgress.interactionCount(11, 4000, 6000))
    }
    @Test fun counterCannotOverflowOrBecomeNegative() {
        assertEquals(Long.MAX_VALUE, GrowthProgress.interactionCount(Long.MAX_VALUE, 0, 1000))
        assertEquals(1L, GrowthProgress.interactionCount(-1, 0, 1000))
    }
    @Test fun clearingEquivalentCountersPreservesCoreCapabilities() {
        val cleared = GrowthProgress.snapshot(1000, 0, 1000, zone)
        val advanced = GrowthProgress.snapshot(1000, 1000, 400L * 86_400_000, zone)
        assertEquals(advanced.toysUnlocked, cleared.toysUnlocked)
        assertEquals(0, cleared.daysTogether)
    }
}
