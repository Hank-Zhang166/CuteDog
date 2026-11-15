package com.cutedog.app.reminder

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class BackupPreview(val schemaVersion: Int, val count: Int, val duplicates: Int)
data class ImportResult(val count: Int, val duplicates: Int, val backupId: String? = null)
data class BackupSnapshot(val id: String, val createdAt: Long)

/** Strict JSON data transfer. Import validates everything before any database write or alarm change. */
class ReminderBackup(context: Context) {
    private val context = context.applicationContext
    private val repository = ReminderRepository(context)
    private val scheduler = ReminderScheduler(context)

    fun exportJson(): String = ReminderBackupCodec.encode(repository.list())

    fun previewJson(json: String): BackupPreview {
        val incoming = ReminderBackupCodec.decode(json)
        val seen = repository.list().map { canonical(it) }.toHashSet()
        val duplicates = incoming.count { !seen.add(canonical(it)) }
        return BackupPreview(1, incoming.size, duplicates)
    }

    fun importJson(json: String, mode: String = "merge"): ImportResult {
        require(mode == "merge" || mode == "replace") { "导入方式只能是 merge 或 replace" }
        val items = ReminderBackupCodec.decode(json)
        return apply(items, replace = mode == "replace")
    }

    fun availableBackups(): List<BackupSnapshot> = repository.database.query(
        "reminder_backups", arrayOf("id", "created_at"), null, null, null, null, "created_at DESC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(BackupSnapshot(cursor.getString(0), cursor.getLong(1))) } }

    fun deleteBackup(backupId: String) {
        require(runCatching { UUID.fromString(backupId) }.isSuccess) { "无效的备份编号" }
        repository.database.delete("reminder_backups", "id=?", arrayOf(backupId))
    }

    fun clearBackups() { repository.database.delete("reminder_backups", null, null) }

    /** Restoring creates another private snapshot first, so restoring can itself be undone. */
    fun restoreBackup(backupId: String): ImportResult {
        require(runCatching { UUID.fromString(backupId) }.isSuccess) { "无效的备份编号" }
        val payload = repository.database.query("reminder_backups", arrayOf("payload"), "id=?", arrayOf(backupId), null, null, null)
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: error("找不到这份备份")
        val snapshot = JSONObject(payload)
        val items = ReminderBackupCodec.decode(snapshot.getJSONObject("public").toString())
        return apply(items, replace = true, deliveries = snapshot.getJSONArray("deliveries"))
    }

    private fun apply(items: List<Reminder>, replace: Boolean, deliveries: JSONArray? = null): ImportResult {
        val db = repository.database
        var backupId: String? = null
        var imported = 0
        var duplicates = 0
        var oldIds: List<Long> = emptyList()
        db.beginTransaction()
        try {
            val existing = repository.list()
            if (replace) {
                // Snapshot is inserted in the same transaction as replacement. A crash cannot leave half an import.
                val publicJson = ReminderBackupCodec.encode(existing)
                ReminderBackupCodec.decode(publicJson) // The safety snapshot must be restorable before deleting anything.
                val public = JSONObject(publicJson)
                val history = JSONArray()
                db.query("deliveries", null, null, null, null, null, null).use { cursor ->
                    while (cursor.moveToNext()) history.put(cursor.jsonRow())
                }
                backupId = UUID.randomUUID().toString()
                val payload = JSONObject().put("public", public).put("deliveries", history).toString()
                db.insertOrThrow("reminder_backups", null, ContentValues().apply {
                    put("id", backupId); put("payload", payload); put("created_at", System.currentTimeMillis())
                })
                oldIds = existing.map { it.id }
                db.delete("reminders", null, null)
            }
            val seen = (if (replace) emptyList() else existing).map { canonical(it) }.toHashSet()
            val remapped = mutableMapOf<Long, Long>()
            for (item in items) {
                if (deliveries == null && !seen.add(canonical(item))) { duplicates++; continue }
                val id = repository.save(item.copy(id = 0))
                remapped[item.id] = id
                imported++
            }
            if (deliveries != null) {
                for (index in 0 until deliveries.length()) {
                    val row = deliveries.getJSONObject(index)
                    val id = remapped[row.getLong("reminder_id")] ?: continue
                    val values = ContentValues().apply {
                        row.keys().forEach { key ->
                            when (val value = row.get(key)) {
                                is Number -> put(key, value.toLong())
                                is String -> put(key, value)
                            }
                        }
                        put("reminder_id", id)
                    }
                    db.insertOrThrow("deliveries", null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        oldIds.forEach(scheduler::cancel)
        scheduler.restoreAll()
        return ImportResult(imported, duplicates, backupId)
    }

    private fun canonical(item: Reminder): Reminder {
        val wall = Instant.ofEpochMilli(item.dueAt).atZone(ZoneId.of(item.timeZoneId))
        return item.copy(id = 0, anchorDay = item.anchorDay.takeIf { it > 0 } ?: wall.dayOfMonth,
            anchorTime = item.anchorTime.ifEmpty { wall.toLocalTime().toString() })
    }

    private fun Cursor.jsonRow() = JSONObject().also { row ->
        columnNames.forEachIndexed { index, name ->
            when (getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> row.put(name, getLong(index))
                Cursor.FIELD_TYPE_STRING -> row.put(name, getString(index))
            }
        }
    }
}

/** Pure codec shared with JVM tests; unknown fields and coercive JSON types are rejected. */
object ReminderBackupCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ITEMS = 5000
    private const val MAX_DATE = 7289654399000L // 2200-12-31T23:59:59Z
    private val fields = setOf("sourceId", "title", "details", "dueAt", "leadMinutes", "repeat", "priority", "category",
        "completed", "snoozedUntil", "timeZoneId", "anchorDay", "anchorTime")

    fun encode(items: List<Reminder>): String {
        require(items.size <= MAX_ITEMS) { "单份备份最多支持 $MAX_ITEMS 件事项" }
        val list = JSONArray()
        items.forEach { item ->
            item.validated()
            require(item.dueAt in 1..MAX_DATE && item.snoozedUntil in 0..MAX_DATE) { "事项日期超出备份支持范围（1970—2200）" }
            list.put(JSONObject().apply {
            put("sourceId", item.id); put("title", item.title); put("details", item.details); put("dueAt", item.dueAt)
            put("leadMinutes", item.leadMinutes); put("repeat", item.repeat); put("priority", item.priority)
            put("category", item.category); put("completed", item.completed); put("snoozedUntil", item.snoozedUntil)
            put("timeZoneId", item.timeZoneId); put("anchorDay", item.anchorDay); put("anchorTime", item.anchorTime)
        }) }
        val json = JSONObject().put("format", "CuteDog.Reminders").put("schemaVersion", 1)
            .put("exportedAt", System.currentTimeMillis()).put("reminders", list).toString(2)
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份超过 2 MiB，请分批整理事项后导出" }
        return json
    }

    fun decode(json: String): List<Reminder> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份文件不得超过 2 MiB" }
        checkNesting(json)
        try {
            val tokener = JSONTokener(json)
            val root = tokener.nextValue() as? JSONObject ?: error("备份必须是 JSON 对象")
            require(tokener.nextClean() == '\u0000') { "JSON 后存在多余内容" }
            require(root.keys().asSequence().toSet() == setOf("format", "schemaVersion", "exportedAt", "reminders")) { "备份顶层字段不匹配" }
            require(root.get("format") == "CuteDog.Reminders") { "这不是 CuteDog 事项备份" }
            require(integer(root, "schemaVersion") == 1L) { "不支持此备份格式版本" }
            require(integer(root, "exportedAt") in 1..MAX_DATE) { "备份生成时间无效" }
            val items = root.get("reminders") as? JSONArray ?: error("事项列表格式无效")
            require(items.length() <= MAX_ITEMS) { "单次最多导入 $MAX_ITEMS 件事项" }
            return (0 until items.length()).map { index ->
                val obj = items.get(index) as? JSONObject ?: error("第 ${index + 1} 件事项格式无效")
                require(obj.keys().asSequence().toSet() == fields) { "第 ${index + 1} 件事项字段不完整或包含未知字段" }
                val due = integer(obj, "dueAt")
                val snooze = integer(obj, "snoozedUntil")
                require(due in 1..MAX_DATE && snooze in 0..MAX_DATE) { "事项日期超出支持范围（1970—2200）" }
                val id = integer(obj, "sourceId")
                require(id >= 0) { "事项编号不能为负数" }
                val zone = ZoneId.of(string(obj, "timeZoneId"))
                Instant.ofEpochMilli(due).atZone(zone) // Reject unknown or malformed timezone before any mutation.
                val item = Reminder(id = id, title = string(obj, "title"), details = string(obj, "details"), dueAt = due,
                    leadMinutes = smallInt(obj, "leadMinutes"), repeat = string(obj, "repeat"), priority = smallInt(obj, "priority"),
                    category = string(obj, "category"), completed = obj.get("completed") as? Boolean ?: error("完成状态必须是布尔值"),
                    snoozedUntil = snooze, timeZoneId = zone.id, anchorDay = smallInt(obj, "anchorDay"), anchorTime = string(obj, "anchorTime"))
                item.validated()
            }
        } catch (error: IllegalArgumentException) { throw error }
        catch (_: Exception) { throw IllegalArgumentException("备份 JSON 无效，未导入任何数据") }
    }

    private fun integer(obj: JSONObject, key: String): Long = when (val value = obj.get(key)) {
        is Int -> value.toLong(); is Long -> value; else -> throw IllegalArgumentException("$key 必须是整数，不能是文本或小数")
    }
    private fun checkNesting(json: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in json) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) { "备份 JSON 嵌套过深" } }
                '}', ']' -> { depth--; require(depth >= 0) { "备份 JSON 结构无效" } }
            }
        }
        require(depth == 0 && !quoted) { "备份 JSON 不完整" }
    }
    private fun smallInt(obj: JSONObject, key: String): Int {
        val value = integer(obj, key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "$key 超出范围" }
        return value.toInt()
    }
    private fun string(obj: JSONObject, key: String): String = obj.get(key) as? String ?: throw IllegalArgumentException("$key 必须是文本")
}
