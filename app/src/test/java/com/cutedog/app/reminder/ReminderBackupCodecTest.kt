package com.cutedog.app.reminder

import com.cutedog.app.data.Reminder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReminderBackupCodecTest {
    private val note = Reminder(id = 7, title = "记得看海", details = "一份只有本地保存的记录", dueAt = 1_800_000_000_000,
        repeat = "monthly", timeZoneId = "Asia/Shanghai", anchorDay = 31, anchorTime = "15:30", priority = 3)
    private fun json() = ReminderBackupCodec.encode(listOf(note))
    private fun mutate(block: (JSONObject) -> Unit): String = JSONObject(json()).also(block).toString()

    @Test fun completeMetadataRoundTrips() { assertEquals(listOf(note), ReminderBackupCodec.decode(json())) }
    @Test fun emptyBackupIsValid() { assertTrue(ReminderBackupCodec.decode(ReminderBackupCodec.encode(emptyList())).isEmpty()) }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsupportedSchemaWithoutGuessing() {
        ReminderBackupCodec.decode(mutate { it.put("schemaVersion", 99) })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsWrongFormat() {
        ReminderBackupCodec.decode(mutate { it.put("format", "other-app") })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsStringAsNumericDate() {
        ReminderBackupCodec.decode(mutate { it.getJSONArray("reminders").getJSONObject(0).put("dueAt", "1800000000000") })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsFloatingPointPriority() {
        ReminderBackupCodec.decode(mutate { it.getJSONArray("reminders").getJSONObject(0).put("priority", 1.2) })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidTimezone() {
        ReminderBackupCodec.decode(mutate { it.getJSONArray("reminders").getJSONObject(0).put("timeZoneId", "Mars/Nowhere") })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOutOfRangeCalendarDate() {
        ReminderBackupCodec.decode(mutate { it.getJSONArray("reminders").getJSONObject(0).put("dueAt", Long.MAX_VALUE) })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownFields() {
        ReminderBackupCodec.decode(mutate { it.getJSONArray("reminders").getJSONObject(0).put("executeCommand", "anything") })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTrailingSecondDocument() { ReminderBackupCodec.decode(json() + "{}") }
    @Test(expected = IllegalArgumentException::class) fun rejectsStringAsBoolean() {
        ReminderBackupCodec.decode(mutate { it.getJSONArray("reminders").getJSONObject(0).put("completed", "true") })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizePayloadBeforeParsing() {
        ReminderBackupCodec.decode(" ".repeat(ReminderBackupCodec.MAX_BYTES + 1))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTooManyItems() {
        ReminderBackupCodec.encode(List(ReminderBackupCodec.MAX_ITEMS + 1) { note })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDeeplyNestedInputBeforeRecursiveParsing() {
        ReminderBackupCodec.decode("[".repeat(2000) + "0" + "]".repeat(2000))
    }
    @Test fun unicodeAndJsonEscapesRemainData() {
        val source = note.copy(title = "引号\"与换行\n在这里", details = "\uD83D\uDC36 \"字段\":null\\与中文")
        assertEquals(source, ReminderBackupCodec.decode(ReminderBackupCodec.encode(listOf(source))).single())
    }
}
