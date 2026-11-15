package com.cutedog.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.ZoneId

/** Small synchronous repository. Call from Dispatchers.IO in UI and from goAsync in receivers. */
class ReminderRepository(context: Context) {
    private val helper = ReminderDatabase.get(context.applicationContext)

    fun list(): List<Reminder> = helper.readableDatabase.query(
        "reminders", null, null, null, null, null, "completed ASC, due_at ASC, priority DESC, id ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.reminder()) } }

    fun get(id: Long): Reminder? = helper.readableDatabase.query(
        "reminders", null, "id=?", arrayOf(id.toString()), null, null, null
    ).use { if (it.moveToFirst()) it.reminder() else null }

    fun save(item: Reminder): Long {
        val value = item.validated()
        val day = value.anchorDay.takeIf { it > 0 }
            ?: Instant.ofEpochMilli(value.dueAt).atZone(ZoneId.of(value.timeZoneId)).dayOfMonth
        val fields = ContentValues().apply {
            put("title", value.title); put("details", value.details); put("due_at", value.dueAt)
            put("lead_minutes", value.leadMinutes); put("repeat_rule", value.repeat)
            put("priority", value.priority); put("category", value.category)
            put("completed", if (value.completed) 1 else 0); put("snoozed_until", value.snoozedUntil)
            put("time_zone_id", value.timeZoneId); put("anchor_day", day)
        }
        val db = helper.writableDatabase
        if (value.id == 0L) return db.insertOrThrow("reminders", null, fields)
        require(db.update("reminders", fields, "id=?", arrayOf(value.id.toString())) == 1) {
            "事项不存在，可能已被删除"
        }
        return value.id
    }

    fun delete(id: Long) {
        helper.writableDatabase.delete("reminders", "id=?", arrayOf(id.toString()))
    }

    fun setCompleted(id: Long, completed: Boolean) {
        helper.writableDatabase.update("reminders", ContentValues().apply {
            put("completed", if (completed) 1 else 0)
            if (completed) put("snoozed_until", 0)
        }, "id=?", arrayOf(id.toString()))
    }

    internal fun setSnooze(id: Long, timestamp: Long) {
        helper.writableDatabase.update("reminders", ContentValues().apply { put("snoozed_until", timestamp) },
            "id=?", arrayOf(id.toString()))
    }

    internal fun wasDelivered(id: Long, token: String): Boolean = helper.readableDatabase.rawQuery(
        "SELECT 1 FROM deliveries WHERE reminder_id=? AND token=? LIMIT 1", arrayOf(id.toString(), token)
    ).use { it.moveToFirst() }

    /** Unique constraint makes repeated broadcasts and reboot restoration idempotent. */
    internal fun claimDelivery(id: Long, token: String): Boolean = helper.writableDatabase.insertWithOnConflict(
        "deliveries", null, ContentValues().apply {
            put("reminder_id", id); put("token", token); put("delivered_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE
    ) != -1L

    internal fun advance(id: Long, expectedDue: Long, nextDue: Long): Boolean =
        helper.writableDatabase.update("reminders", ContentValues().apply { put("due_at", nextDue) },
            "id=? AND due_at=? AND completed=0", arrayOf(id.toString(), expectedDue.toString())) == 1

    private fun Cursor.reminder() = Reminder(
        id = getLong(getColumnIndexOrThrow("id")), title = getString(getColumnIndexOrThrow("title")),
        details = getString(getColumnIndexOrThrow("details")), dueAt = getLong(getColumnIndexOrThrow("due_at")),
        leadMinutes = getInt(getColumnIndexOrThrow("lead_minutes")), repeat = getString(getColumnIndexOrThrow("repeat_rule")),
        priority = getInt(getColumnIndexOrThrow("priority")), category = getString(getColumnIndexOrThrow("category")),
        completed = getInt(getColumnIndexOrThrow("completed")) != 0,
        snoozedUntil = getLong(getColumnIndexOrThrow("snoozed_until")),
        timeZoneId = getString(getColumnIndexOrThrow("time_zone_id")), anchorDay = getInt(getColumnIndexOrThrow("anchor_day")),
    )
}

private class ReminderDatabase(context: Context) : SQLiteOpenHelper(context, "cutedog-reminders.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE reminders (
            id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, details TEXT NOT NULL,
            due_at INTEGER NOT NULL, lead_minutes INTEGER NOT NULL DEFAULT 0,
            repeat_rule TEXT NOT NULL DEFAULT 'none', priority INTEGER NOT NULL DEFAULT 1,
            category TEXT NOT NULL DEFAULT '生活', completed INTEGER NOT NULL DEFAULT 0,
            snoozed_until INTEGER NOT NULL DEFAULT 0, time_zone_id TEXT NOT NULL,
            anchor_day INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX reminders_due ON reminders(completed, due_at)")
        db.execSQL("""CREATE TABLE deliveries (
            reminder_id INTEGER NOT NULL REFERENCES reminders(id) ON DELETE CASCADE,
            token TEXT NOT NULL, delivered_at INTEGER NOT NULL,
            PRIMARY KEY(reminder_id, token))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 has no migrations. Never destroy personal data to resolve a schema mismatch.
        error("Unsupported reminders database migration: $oldVersion → $newVersion")
    }
    companion object {
        @Volatile private var instance: ReminderDatabase? = null
        fun get(context: Context): ReminderDatabase = instance ?: synchronized(this) {
            instance ?: ReminderDatabase(context).also { instance = it }
        }
    }
}
