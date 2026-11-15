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
    internal val helper = ReminderDatabase.get(context.applicationContext)
    internal val database: SQLiteDatabase get() = helper.writableDatabase

    fun list(): List<Reminder> = helper.readableDatabase.query(
        "reminders", null, null, null, null, null, "completed ASC, due_at ASC, priority DESC, id ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.reminder()) } }

    fun get(id: Long): Reminder? = helper.readableDatabase.query(
        "reminders", null, "id=?", arrayOf(id.toString()), null, null, null
    ).use { if (it.moveToFirst()) it.reminder() else null }

    fun save(item: Reminder): Long {
        val value = item.validated()
        val wallTime = Instant.ofEpochMilli(value.dueAt).atZone(ZoneId.of(value.timeZoneId))
        val existing = if (value.id > 0) get(value.id) else null
        val editedTime = existing != null && (existing.dueAt != value.dueAt || existing.timeZoneId != value.timeZoneId)
        val day = if (editedTime) wallTime.dayOfMonth else value.anchorDay.takeIf { it > 0 } ?: wallTime.dayOfMonth
        val anchorTime = if (editedTime || value.anchorTime.isEmpty()) wallTime.toLocalTime().toString() else value.anchorTime
        val fields = ContentValues().apply {
            put("title", value.title); put("details", value.details); put("due_at", value.dueAt)
            put("lead_minutes", value.leadMinutes); put("repeat_rule", value.repeat)
            put("priority", value.priority); put("category", value.category)
            put("completed", if (value.completed) 1 else 0); put("snoozed_until", value.snoozedUntil)
            put("time_zone_id", value.timeZoneId); put("anchor_day", day); put("anchor_time", anchorTime)
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
        if (completed) database.execSQL("UPDATE deliveries SET status='acknowledged', reason='' WHERE reminder_id=? AND status IN ('pending','blocked','failed','summarized')", arrayOf(id))
    }

    internal fun setSnooze(id: Long, timestamp: Long) {
        helper.writableDatabase.update("reminders", ContentValues().apply { put("snoozed_until", timestamp) },
            "id=?", arrayOf(id.toString()))
    }

    internal fun wasDelivered(id: Long, token: String): Boolean = helper.readableDatabase.rawQuery(
        "SELECT 1 FROM deliveries WHERE reminder_id=? AND token=? LIMIT 1", arrayOf(id.toString(), token)
    ).use { it.moveToFirst() }

    /** Capture the occurrence before attempting system delivery. Existing tokens are idempotent. */
    internal fun claimDelivery(id: Long, token: String, expectedDue: Long? = null, expectedSnooze: Long? = null): Boolean {
        val db = database
        db.beginTransaction()
        try {
            val item = get(id)
            if (item == null || item.completed || (expectedDue != null && item.dueAt != expectedDue) ||
                (expectedSnooze != null && item.snoozedUntil != expectedSnooze)) {
                db.setTransactionSuccessful(); return false
            }
            val timestamp = System.currentTimeMillis()
            val inserted = db.insertWithOnConflict("deliveries", null, ContentValues().apply {
                put("reminder_id", id); put("token", token); put("delivered_at", 0)
                put("due_at", expectedDue ?: item.dueAt); put("title", item.title); put("details", item.details)
                put("priority", item.priority); put("is_snooze", if (expectedSnooze != null) 1 else 0)
                put("status", "pending"); put("created_at", timestamp); put("last_attempt_at", 0)
                put("attempts", 0); put("reason", "")
            }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            db.setTransactionSuccessful()
            return inserted
        } finally { db.endTransaction() }
    }

    fun pendingDeliveries(): List<DeliveryRecord> = deliveries("status IN ('pending','blocked','failed','summarized')", 5000)
    internal fun retryableDeliveries(): List<DeliveryRecord> = deliveries("status IN ('pending','blocked','failed')", 5000)
    fun deliveryHistory(limit: Int = 100): List<DeliveryRecord> = deliveries(null, limit.coerceIn(1, 5000))

    private fun deliveries(selection: String?, limit: Int): List<DeliveryRecord> = database.query(
        "deliveries", null, selection, null, null, null, "created_at DESC", limit.toString()
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.delivery()) } }

    fun acknowledgeDelivery(reminderId: Long, token: String) {
        updateDelivery(reminderId, token, "acknowledged", "")
    }

    internal fun updateDelivery(reminderId: Long, token: String, status: String, reason: String = "") {
        database.update("deliveries", ContentValues().apply {
            put("status", status); put("reason", reason.take(160))
            if (status == "sent" || status == "summarized") put("delivered_at", System.currentTimeMillis())
        }, "reminder_id=? AND token=?" + if (status == "acknowledged") "" else " AND status!='acknowledged'", arrayOf(reminderId.toString(), token))
    }

    internal fun beginDeliveryAttempt(record: DeliveryRecord) {
        database.execSQL("UPDATE deliveries SET attempts=attempts+1,last_attempt_at=? WHERE reminder_id=? AND token=?",
            arrayOf(System.currentTimeMillis(), record.reminderId, record.token))
    }

    private fun Cursor.delivery() = DeliveryRecord(
        reminderId = getLong(getColumnIndexOrThrow("reminder_id")), token = getString(getColumnIndexOrThrow("token")),
        title = getString(getColumnIndexOrThrow("title")), details = getString(getColumnIndexOrThrow("details")),
        dueAt = getLong(getColumnIndexOrThrow("due_at")), status = getString(getColumnIndexOrThrow("status")),
        reason = getString(getColumnIndexOrThrow("reason")), attempts = getInt(getColumnIndexOrThrow("attempts")),
        lastAttemptAt = getLong(getColumnIndexOrThrow("last_attempt_at")), createdAt = getLong(getColumnIndexOrThrow("created_at")),
        priority = getInt(getColumnIndexOrThrow("priority")), isSnooze = getInt(getColumnIndexOrThrow("is_snooze")) != 0,
    )

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
        anchorTime = getString(getColumnIndexOrThrow("anchor_time")),
    )
}

internal class ReminderDatabase(context: Context, name: String = "cutedog-reminders.db") : SQLiteOpenHelper(context, name, null, 2) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE reminders (
            id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, details TEXT NOT NULL,
            due_at INTEGER NOT NULL, lead_minutes INTEGER NOT NULL DEFAULT 0,
            repeat_rule TEXT NOT NULL DEFAULT 'none', priority INTEGER NOT NULL DEFAULT 1,
            category TEXT NOT NULL DEFAULT '生活', completed INTEGER NOT NULL DEFAULT 0,
            snoozed_until INTEGER NOT NULL DEFAULT 0, time_zone_id TEXT NOT NULL,
            anchor_day INTEGER NOT NULL DEFAULT 0, anchor_time TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE INDEX reminders_due ON reminders(completed, due_at)")
        db.execSQL("""CREATE TABLE deliveries (
            reminder_id INTEGER NOT NULL REFERENCES reminders(id) ON DELETE CASCADE,
            token TEXT NOT NULL, delivered_at INTEGER NOT NULL DEFAULT 0,
            due_at INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL DEFAULT '', details TEXT NOT NULL DEFAULT '',
            priority INTEGER NOT NULL DEFAULT 1, is_snooze INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'pending', reason TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL DEFAULT 0, last_attempt_at INTEGER NOT NULL DEFAULT 0,
            attempts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(reminder_id, token))""")
        createAuxiliaryTables(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..newVersion && newVersion <= 2) { "Unsupported reminders database migration" }
        if (oldVersion < 2) {
            listOf(
                "due_at INTEGER NOT NULL DEFAULT 0", "title TEXT NOT NULL DEFAULT ''", "details TEXT NOT NULL DEFAULT ''",
                "priority INTEGER NOT NULL DEFAULT 1", "is_snooze INTEGER NOT NULL DEFAULT 0",
                "status TEXT NOT NULL DEFAULT 'legacy'", "reason TEXT NOT NULL DEFAULT ''",
                "created_at INTEGER NOT NULL DEFAULT 0", "last_attempt_at INTEGER NOT NULL DEFAULT 0", "attempts INTEGER NOT NULL DEFAULT 0",
            ).forEach { db.execSQL("ALTER TABLE deliveries ADD COLUMN $it") }
            db.execSQL("""UPDATE deliveries SET created_at=delivered_at,
                due_at=CASE WHEN token LIKE 'due:%' THEN CAST(substr(token,5) AS INTEGER) ELSE 0 END,
                title=COALESCE((SELECT title FROM reminders WHERE reminders.id=deliveries.reminder_id),''),
                details=COALESCE((SELECT details FROM reminders WHERE reminders.id=deliveries.reminder_id),'')""")
            createAuxiliaryTables(db)
        }
    }
    private fun createAuxiliaryTables(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS deliveries_status ON deliveries(status,created_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_backups (id TEXT PRIMARY KEY, payload TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    companion object {
        @Volatile private var instance: ReminderDatabase? = null
        fun get(context: Context): ReminderDatabase = instance ?: synchronized(this) {
            instance ?: ReminderDatabase(context).also { instance = it }
        }
    }
}
