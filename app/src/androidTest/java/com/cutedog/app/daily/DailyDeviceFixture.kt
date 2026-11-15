package com.cutedog.app.daily

import android.Manifest
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.File

/** Only this disposable emulator's daily tables/preferences are changed, then restored. */
class DailyDeviceFixture : ExternalResource() {
    val context: Context get() = ApplicationProvider.getApplicationContext()
    val repository: DailyRepository get() = DailyRepository(context)
    private var savedRows: Map<String, List<ContentValues>>? = null
    private var preferences: Map<String, *>? = null
    private var onboarding: Map<String, *>? = null
    private var oldNotification: android.app.Notification? = null

    override fun before() {
        assertEquals("Host must explicitly confirm disposable isolation before Daily data snapshots",
            "true", InstrumentationRegistry.getArguments().getString("cuteDogDisposable"))
        assertEquals("Daily fixtures must run on a disposable emulator", "1", shell("getprop ro.kernel.qemu").trim())
        preferences = context.getSharedPreferences("daily_settings", 0).all.toMap()
        onboarding = context.getSharedPreferences("onboarding", 0).all.toMap()
        oldNotification = manager().activeNotifications.firstOrNull { it.id == NOTIFICATION_ID && it.tag == null }?.notification
        DailySettings(context).enabled = false
        DailyScheduler(context).schedule()
        repository // Ensure the real SQLiteOpenHelper has initialized its schema.
        synchronized(DailyRepository.lock) {
            database().use { db ->
                savedRows = TABLES.associateWith { table ->
                    db.query(table, null, null, null, null, null, null).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(ContentValues().apply {
                                cursor.columnNames.forEachIndexed { index, name ->
                                    if (cursor.getType(index) == android.database.Cursor.FIELD_TYPE_INTEGER) put(name, cursor.getLong(index))
                                    else put(name, cursor.getString(index))
                                }
                            })
                        }
                    }
                }
                db.beginTransaction()
                try { TABLES.forEach { db.delete(it, null, null) }; db.setTransactionSuccessful() }
                finally { db.endTransaction() }
            }
        }
        manager().cancel(NOTIFICATION_ID)
        assertTrue(context.getSharedPreferences("daily_settings", 0).edit().clear()
            .putBoolean("enabled", false).putInt("hour", 9).putInt("quiet_start", 0).putInt("quiet_end", 0).commit())
        assertTrue(context.getSharedPreferences("onboarding", 0).edit().putBoolean("completed", true).commit())
    }

    override fun after() {
        if (preferences == null) return
        DailySettings(context).enabled = false
        DailyScheduler(context).schedule()
        manager().cancel(NOTIFICATION_ID)
        try {
            synchronized(DailyRepository.lock) {
                savedRows?.let { rows -> database().use { db ->
                    db.beginTransaction()
                    try {
                        TABLES.forEach { db.delete(it, null, null) }
                        rows.forEach { (table, values) -> values.forEach { db.insertOrThrow(table, null, it) } }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                } }
            }
        } finally {
            restorePreferences("daily_settings", preferences!!)
            onboarding?.let { restorePreferences("onboarding", it) }
            DailyScheduler(context).schedule()
            oldNotification?.let { manager().notify(NOTIFICATION_ID, it) }
        }
    }

    fun database(readOnly: Boolean = false): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath("daily.db").absolutePath, null,
        if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE)

    fun requireGrantedNotifications() {
        if (Build.VERSION.SDK_INT >= 33) assertEquals("Host must grant notifications before this test process starts",
            android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue("App notifications must be enabled", manager().areNotificationsEnabled())
        assertTrue("Daily channel must not be user-blocked in this disposable fixture",
            manager().getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
    }

    fun manager(): NotificationManager = context.getSystemService(NotificationManager::class.java)
    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    fun evidence(test: String, name: String, value: String) {
        evidenceFile(test, name).writeText(value)
    }

    fun evidenceFile(test: String, name: String): File {
        val phase = InstrumentationRegistry.getArguments().getString("cuteDogEvidencePhase", "manual")
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,24}")) } ?: "manual"
        val root = File(context.getExternalFilesDir(null), "daily-test-evidence/$phase/$test")
        check(root.mkdirs() || root.isDirectory)
        return File(root, name)
    }

    private fun restorePreferences(name: String, values: Map<String, *>) {
        val edit = context.getSharedPreferences(name, 0).edit().clear()
        values.forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
            else -> error("Unexpected preference type for $name/$key")
        } }
        assertTrue("Restore $name preferences", edit.commit())
    }

    companion object {
        const val NOTIFICATION_ID = 84001
        const val CHANNEL = "daily_greeting"
        private val TABLES = listOf("history", "favorites", "greetings")
    }
}
