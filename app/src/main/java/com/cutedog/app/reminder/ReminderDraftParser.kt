package com.cutedog.app.reminder

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class ReminderDraft(val title: String, val dueAt: Long, val explanation: String)

/** Explicit, local date extraction only. A draft always needs user confirmation before being saved. */
object ReminderDraftParser {
    private val relative = Regex("""(今天|明天|后天)\s*(凌晨|早上|上午|中午|下午|晚上)?\s*([零〇一二两三四五六七八九十\d]{1,3})\s*(?:点\s*(半|一刻|三刻|[零〇一二两三四五六七八九十\d]{1,3}分)?|[:：]\s*(\d{1,2}))(?!\d)""")
    private val absolute = Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}""")
    private val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

    fun parse(text: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): ReminderDraft? {
        val clean = text.trim()
        val absoluteMatch = absolute.find(clean)
        if (absoluteMatch != null) {
            val value = try { LocalDateTime.parse(absoluteMatch.value.replace(Regex("\\s+"), " "), formatter) }
            catch (_: DateTimeException) { return null }
            // Reject a nonexistent wall time in a daylight-saving transition, instead of silently shifting it.
            if (zone.rules.getValidOffsets(value).isEmpty()) return null
            return draft(clean, absoluteMatch.range, value.atZone(zone).toInstant().toEpochMilli(), now, zone)
        }
        val match = relative.find(clean) ?: return null
        var hour = number(match.groupValues[3]) ?: return null
        val period = match.groupValues[2]
        if (period.isNotEmpty()) {
            if (hour !in 0..12) return null
            when (period) {
                "下午", "晚上" -> if (hour in 1..11) hour += 12
                "中午" -> if (hour in 1..10) return null else if (hour == 0) hour = 12
                "凌晨", "早上", "上午" -> if (hour == 12) hour = 0
            }
        }
        if (hour !in 0..23) return null
        val minuteText = (match.groupValues[4].ifEmpty { match.groupValues[5] }).removeSuffix("分")
        val minute = when (minuteText) { "" -> 0; "半" -> 30; "一刻" -> 15; "三刻" -> 45; else -> number(minuteText) ?: return null }
        if (minute !in 0..59) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date = today.plusDays(when (match.groupValues[1]) { "明天" -> 1L; "后天" -> 2L; else -> 0L })
        val wallTime = date.atTime(hour, minute)
        if (zone.rules.getValidOffsets(wallTime).isEmpty()) return null
        return draft(clean, match.range, wallTime.atZone(zone).toInstant().toEpochMilli(), now, zone)
    }

    private fun draft(text: String, range: IntRange, due: Long, now: Long, zone: ZoneId): ReminderDraft? {
        if (due <= now) return null
        val title = text.removeRange(range).trim(' ', '，', ',', '。', '！', '!', '：', ':')
            .removePrefix("记得").removePrefix("提醒我").trim().trimEnd('。', '！', '!')
        if (title.isBlank()) return null
        val display = Instant.ofEpochMilli(due).atZone(zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
        return ReminderDraft(title, due, "已识别为 $display，请确认后保存")
    }

    private fun number(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        fun digit(c: Char) = when (c) { '零', '〇' -> 0; '一' -> 1; '二', '两' -> 2; '三' -> 3; '四' -> 4; '五' -> 5; '六' -> 6; '七' -> 7; '八' -> 8; '九' -> 9; else -> null }
        if (raw.length == 1) return if (raw == "十") 10 else digit(raw[0])
        val parts = raw.split('十')
        if (parts.size == 2 && parts.all { it.length <= 1 }) {
            val tens = if (parts[0].isEmpty()) 1 else digit(parts[0][0]) ?: return null
            val ones = if (parts[1].isEmpty()) 0 else digit(parts[1][0]) ?: return null
            return tens * 10 + ones
        }
        return null
    }
}
