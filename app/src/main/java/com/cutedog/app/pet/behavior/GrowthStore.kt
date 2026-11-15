package com.cutedog.app.pet.behavior

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Local, optional memories of time together. Toys and functional capabilities are always available. */
data class GrowthSnapshot(
    val firstMet: Long,
    val interactions: Long,
    val toysUnlocked: Set<String>,
    val daysTogether: Int,
    val milestones: List<String>,
)

class GrowthStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pet_growth", Context.MODE_PRIVATE)

    fun snapshot(now: Long = System.currentTimeMillis()): GrowthSnapshot = synchronized(lock) {
        val first = preferences.getLong("first_met", 0).takeIf { it > 0 } ?: now.coerceAtLeast(1).also {
            preferences.edit().putLong("first_met", it).apply()
        }
        GrowthProgress.snapshot(first, preferences.getLong("interactions", 0).coerceAtLeast(0), now)
    }

    /** Debounces dense stroke events; there are no missed-day penalties or care obligations. */
    fun recordInteraction(now: Long = System.currentTimeMillis()): GrowthSnapshot = synchronized(lock) {
        val before = snapshot(now)
        val last = preferences.getLong("last_interaction", 0)
        val count = GrowthProgress.interactionCount(before.interactions, last, now)
        if (count != before.interactions) preferences.edit().putLong("interactions", count)
            .putLong("last_interaction", now.coerceAtLeast(1)).apply()
        GrowthProgress.snapshot(before.firstMet, count, now)
    }

    fun clear(now: Long = System.currentTimeMillis()): GrowthSnapshot = synchronized(lock) {
        preferences.edit().clear().putLong("first_met", now.coerceAtLeast(1)).apply()
        GrowthProgress.snapshot(now.coerceAtLeast(1), 0, now)
    }

    companion object { private val lock = Any() }
}

/** Pure calendar/count policy; kept separate so milestone and debounce semantics are testable. */
object GrowthProgress {
    fun snapshot(firstMet: Long, interactions: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): GrowthSnapshot {
        val first = firstMet.coerceAtLeast(1)
        val begin = Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
        val current = Instant.ofEpochMilli(now.coerceAtLeast(1)).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(begin, current).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val count = interactions.coerceAtLeast(0)
        val memories = buildList {
            add("初次相遇")
            if (count >= 10) add("熟悉的问候")
            if (days >= 7) add("一周的日常")
            if (count >= 50) add("小小默契")
            if (days >= 30) add("一起看月亮")
            if (days >= 365) add("一起过四季")
        }
        return GrowthSnapshot(first, count, setOf("ball", "treat"), days, memories)
    }

    fun interactionCount(previous: Long, lastAt: Long, now: Long): Long {
        val safe = previous.coerceAtLeast(0)
        if (lastAt > 0 && now >= lastAt && now - lastAt < 2000) return safe
        return if (safe == Long.MAX_VALUE) safe else safe + 1
    }
}
