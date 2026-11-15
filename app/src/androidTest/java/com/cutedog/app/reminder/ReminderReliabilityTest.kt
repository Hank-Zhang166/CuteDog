package com.cutedog.app.reminder

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderDatabase
import com.cutedog.app.data.ReminderRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderReliabilityTest {
    private lateinit var context: Context
    private lateinit var repository: ReminderRepository
    private val created = mutableListOf<Long>()
    private val snapshots = mutableListOf<String>()
    private val prefix = "可靠性测试-${UUID.randomUUID()}"

    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); repository = ReminderRepository(context) }
    @After fun cleanup() {
        val scheduler = ReminderScheduler(context)
        (created + repository.list().filter { it.title.startsWith(prefix) }.map { it.id }).distinct().forEach {
            scheduler.cancel(it); repository.delete(it)
        }
        snapshots.forEach { repository.database.delete("reminder_backups", "id=?", arrayOf(it)) }
        context.getSystemService(NotificationManager::class.java).cancel("missed-summary", ReminderReceiver.NOTIFICATION_ID)
    }
    private fun note(offset: Long = 3_600_000) = Reminder(title = "$prefix 事项", dueAt = System.currentTimeMillis() + offset)
    private fun save(item: Reminder = note()) = repository.save(item).also { created += it }
    private fun requireNotifications() {
        assumeTrue("Run in host-granted notification test phase", Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        assumeTrue(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
    }

    @Test fun deniedNotificationPermissionRetainsDurablePendingOccurrence() {
        assumeTrue("Notification permission introduced on API33", Build.VERSION.SDK_INT >= 33)
        assumeTrue("Run in host-denied notification test phase", context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        val id = save(note(-60_000))
        ReminderScheduler(context).restoreAll()
        val record = repository.pendingDeliveries().single { it.reminderId == id }
        assertEquals("blocked", record.status)
        assertTrue(record.reason.contains("授权"))
        assertEquals(0, record.attempts)
        assertFalse(repository.get(id)!!.completed)
        // A new repository instance sees the durable outbox, even though no UI or overlay was started.
        assertEquals(record, ReminderRepository(context).pendingDeliveries().single { it.reminderId == id })
    }

    @Test fun persistedBlockedOutboxReplaysOnceAfterGrant() {
        requireNotifications()
        val id = save(note(-60_000))
        val due = repository.get(id)!!.dueAt
        val token = ReminderScheduler.normalToken(due)
        repository.claimDelivery(id, token, expectedDue = due)
        repository.updateDelivery(id, token, "blocked", "通知权限尚未授予，授权后会补发")
        val scheduler = ReminderScheduler(context)
        assertEquals(1, scheduler.retryPending())
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.activeNotifications.any { it.tag == id.toString() })
        assertEquals("sent", repository.deliveryHistory().single { it.reminderId == id }.status)
        assertEquals(1, repository.deliveryHistory().single { it.reminderId == id }.attempts)
        assertEquals(0, scheduler.retryPending())
        assertEquals(1, manager.activeNotifications.count { it.tag == id.toString() })
    }

    @Test fun multipleOverdueRemindersBecomeOneSummary() {
        requireNotifications()
        val ids = (1..6).map { save(note(-it * 60_000L).copy(title = "$prefix 事项 $it")) }
        ReminderScheduler(context).restoreAll()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.activeNotifications.any { it.tag == "missed-summary" })
        assertTrue(manager.activeNotifications.none { it.tag in ids.map(Long::toString) })
        val rows = repository.pendingDeliveries().filter { it.reminderId in ids }
        assertEquals(6, rows.size)
        assertTrue(rows.all { it.status == "summarized" })
        assertEquals(0, ReminderScheduler(context).retryPending())
    }

    @Test fun importIsValidatedBeforeAnyDataChangeAndMergeRemapsIds() {
        val id = save()
        val original = repository.get(id)!!
        val backup = ReminderBackup(context)
        val json = ReminderBackupCodec.encode(listOf(original))
        assertEquals(1, backup.previewJson(json).duplicates)
        assertEquals(0, backup.importJson(json).count)
        val invalid = JSONObject(json).apply { getJSONArray("reminders").getJSONObject(0).put("dueAt", "tomorrow") }.toString()
        try { backup.importJson(invalid, "replace"); fail("Invalid input must not be partially applied") }
        catch (_: IllegalArgumentException) { }
        assertEquals(original, repository.get(id))
        val incoming = original.copy(title = "$prefix 新事项")
        val result = backup.importJson(ReminderBackupCodec.encode(listOf(incoming)))
        assertEquals(1, result.count)
        val imported = repository.list().single { it.title == incoming.title }
        assertNotEquals(id, imported.id)
        assertEquals(original, repository.get(id))
    }

    @Test fun replacingAndRestoringPreservesDuplicateItemsAndDeliveryHistory() {
        assumeTrue("Destructive-mode test uses a clean test installation", repository.list().isEmpty())
        val item = note()
        val first = save(item)
        save(item)
        val token = ReminderScheduler.normalToken(item.dueAt)
        repository.claimDelivery(first, token, expectedDue = item.dueAt)
        repository.updateDelivery(first, token, "acknowledged")
        val backup = ReminderBackup(context)
        val replacement = backup.importJson(ReminderBackupCodec.encode(listOf(note().copy(title = "$prefix 替换内容"))), "replace")
        val archive = replacement.backupId!!; snapshots += archive
        assertEquals(1, repository.list().size)
        val restored = backup.restoreBackup(archive)
        restored.backupId?.let(snapshots::add)
        assertEquals(2, restored.count)
        assertEquals(2, repository.list().size)
        assertTrue(repository.list().all { it.title == item.title })
        val receipt = repository.deliveryHistory().single()
        assertEquals("acknowledged", receipt.status)
        assertTrue(repository.list().any { it.id == receipt.reminderId })
    }

    @Test fun versionOneMigratesWithoutLosingNotesOrReceipts() {
        val name = "reminder-migration-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,details TEXT NOT NULL,
                due_at INTEGER NOT NULL,lead_minutes INTEGER NOT NULL DEFAULT 0,repeat_rule TEXT NOT NULL DEFAULT 'none',
                priority INTEGER NOT NULL DEFAULT 1,category TEXT NOT NULL DEFAULT '生活',completed INTEGER NOT NULL DEFAULT 0,
                snoozed_until INTEGER NOT NULL DEFAULT 0,time_zone_id TEXT NOT NULL,anchor_day INTEGER NOT NULL DEFAULT 0,
                anchor_time TEXT NOT NULL DEFAULT '')""")
            db.execSQL("CREATE INDEX reminders_due ON reminders(completed,due_at)")
            db.execSQL("CREATE TABLE deliveries(reminder_id INTEGER NOT NULL REFERENCES reminders(id) ON DELETE CASCADE,token TEXT NOT NULL,delivered_at INTEGER NOT NULL,PRIMARY KEY(reminder_id,token))")
            db.execSQL("INSERT INTO reminders(id,title,details,due_at,time_zone_id,anchor_day,anchor_time) VALUES(42,'保留的事项','不能丢失',1800000000000,'Asia/Shanghai',31,'15:30')")
            db.execSQL("INSERT INTO deliveries VALUES(42,'due:1800000000000',1799999999000)")
            db.version = 1
        }
        val migrated = ReminderDatabase(context, name)
        try {
            val db = migrated.writableDatabase
            assertEquals(2, db.version)
            db.rawQuery("SELECT title,details,anchor_day,anchor_time FROM reminders WHERE id=42", null).use {
                assertTrue(it.moveToFirst()); assertEquals("保留的事项", it.getString(0)); assertEquals("不能丢失", it.getString(1))
                assertEquals(31, it.getInt(2)); assertEquals("15:30", it.getString(3))
            }
            db.rawQuery("SELECT status,due_at,delivered_at FROM deliveries WHERE reminder_id=42", null).use {
                assertTrue(it.moveToFirst()); assertEquals("legacy", it.getString(0)); assertEquals(1800000000000, it.getLong(1))
                assertEquals(1799999999000, it.getLong(2))
            }
            db.rawQuery("SELECT count(*) FROM reminder_backups", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun oneThousandNativeSQLiteItemsRemainIndexedAndResponsive() {
        val started = SystemClock.elapsedRealtime()
        repository.database.beginTransaction()
        try {
            repeat(1000) { save(note().copy(title = "$prefix 性能 $it", category = if (it % 2 == 0) "工作" else "生活")) }
            repository.database.setTransactionSuccessful()
        } finally { repository.database.endTransaction() }
        val insertMs = SystemClock.elapsedRealtime() - started
        val readStarted = SystemClock.elapsedRealtime()
        val rows = repository.list().filter { it.id in created }
        val readMs = SystemClock.elapsedRealtime() - readStarted
        assertEquals(1000, rows.size)
        assertEquals(500, rows.count { it.category == "工作" })
        assertTrue("1000 transactional writes took ${insertMs}ms", insertMs < 30_000)
        assertTrue("Indexed list read took ${readMs}ms", readMs < 10_000)
        repository.database.rawQuery("EXPLAIN QUERY PLAN SELECT id FROM reminders WHERE completed=0 ORDER BY due_at", null).use {
            val details = buildList { while (it.moveToNext()) add(it.getString(3)) }.joinToString(" ")
            assertTrue(details.contains("reminders_due"))
        }
        Log.i("CuteDogPerformance", "nativeSQLite count=1000 insertMs=$insertMs readMs=$readMs")
    }
}
