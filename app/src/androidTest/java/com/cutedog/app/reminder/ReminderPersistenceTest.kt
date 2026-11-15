package com.cutedog.app.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against Android's real SQLite and AlarmManager, never an in-memory repository substitute. */
@RunWith(AndroidJUnit4::class)
class ReminderPersistenceTest {
    private lateinit var context: Context
    private lateinit var repository: ReminderRepository
    private val created = mutableListOf<Long>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = ReminderRepository(context)
    }

    @After fun tearDown() {
        created.forEach { id -> ReminderScheduler(context).cancel(id); repository.delete(id) }
    }

    private fun save(item: Reminder = Reminder(title = "仪器测试事项", dueAt = System.currentTimeMillis() + 3_600_000)) =
        repository.save(item).also { if (it !in created) created += it }

    @Test fun createReadEditCompleteUndoAndDelete() {
        val id = save()
        val persisted = ReminderRepository(context).get(id)!!
        assertEquals("仪器测试事项", persisted.title)
        assertTrue(persisted.anchorDay in 1..31)
        repository.save(persisted.copy(title = "已编辑", details = "完整详情", priority = 3, category = "工作"))
        assertEquals("已编辑", repository.get(id)!!.title)
        assertEquals("完整详情", repository.get(id)!!.details)
        assertEquals(3, repository.get(id)!!.priority)
        repository.setCompleted(id, true)
        assertTrue(ReminderRepository(context).get(id)!!.completed)
        repository.setCompleted(id, false)
        assertFalse(repository.get(id)!!.completed)
        assertTrue(repository.list().any { it.id == id })
        repository.delete(id)
        assertNull(repository.get(id))
    }

    @Test fun deliveryClaimsAreIdempotentAndDeletedWithReminder() {
        val id = save()
        assertTrue(repository.claimDelivery(id, "due:100"))
        assertFalse(repository.claimDelivery(id, "due:100"))
        assertTrue(ReminderRepository(context).wasDelivered(id, "due:100"))
        assertTrue(repository.claimDelivery(id, "due:200"))
        repository.delete(id)
        assertFalse(repository.wasDelivered(id, "due:100"))
        assertFalse(repository.claimDelivery(id, "due:300"))
    }

    @Test fun recurrenceAdvanceDoesNotOverwriteAnEditedDate() {
        val id = save()
        val original = repository.get(id)!!
        val edited = original.copy(dueAt = original.dueAt + 120_000)
        repository.save(edited)
        assertFalse(repository.advance(id, original.dueAt, original.dueAt + 86_400_000))
        assertEquals(edited.dueAt, repository.get(id)!!.dueAt)
    }

    @Test fun snoozePersistsWithoutReplacingNextRecurringDate() {
        val due = System.currentTimeMillis() + 86_400_000
        val id = save(Reminder(title = "每日散步", dueAt = due, repeat = "daily"))
        val snooze = System.currentTimeMillis() + 600_000
        repository.setSnooze(id, snooze)
        val restored = ReminderRepository(context).get(id)!!
        assertEquals(snooze, restored.snoozedUntil)
        assertEquals(due, restored.dueAt)
    }

    @Test fun deniedExactAlarmPermissionUsesInexactSchedulingWithoutSecurityFailure() {
        assumeTrue("Applies to Android 12 and later", Build.VERSION.SDK_INT >= 31)
        val alarms = context.getSystemService(AlarmManager::class.java)
        assumeFalse("Revoke exact-alarm access to exercise this case", alarms.canScheduleExactAlarms())
        val id = save()
        val scheduler = ReminderScheduler(context)
        assertFalse(scheduler.canScheduleExact())
        scheduler.schedule(repository.get(id)!!)
        scheduler.cancel(id)
    }

    /**
     * Run separately with notification and exact-alarm access granted by the host device runner.
     * The host records and restores permission/app-op state after instrumentation exits: revoking
     * either permission inside this process can kill the entire instrumentation runner.
     */
    @Test fun actualAlarmDeliversAndNotificationSnoozePersists() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val scheduler = ReminderScheduler(context)
        assumeTrue("Host must grant POST_NOTIFICATIONS for real notification delivery",
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        assumeTrue("Host must allow exact alarms for the bounded delivery test", scheduler.canScheduleExact())
        assumeTrue("App notifications must be enabled on the test device", manager.areNotificationsEnabled())
        assumeTrue("Reminder channel must not be user-disabled",
            manager.getNotificationChannel(ReminderReceiver.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE)

        var hasVisibleActivity = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            hasVisibleActivity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).isNotEmpty()
        }
        assertFalse("The reminder must fire without a resumed app activity", hasVisibleActivity)

        val title = "真实后台提醒 ${SystemClock.elapsedRealtime()}"
        val due = System.currentTimeMillis() + 3000
        val id = save(Reminder(title = title, details = "由系统闹钟真实触发", dueAt = due, priority = 3))
        try {
            scheduler.schedule(repository.get(id)!!)
            var delivered: Notification? = null
            val appeared = waitFor(20_000) {
                delivered = manager.activeNotifications.firstOrNull {
                    it.tag == id.toString() && it.id == ReminderReceiver.NOTIFICATION_ID
                }?.notification
                delivered != null
            }
            assertTrue("AlarmManager did not deliver the reminder notification within 20 seconds", appeared)
            val notification = delivered!!
            assertEquals("重要事项 · $title", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(title))
            assertEquals(due, notification.`when`)

            val snooze = notification.actions.firstOrNull { it.title.toString() == "10 分钟后" }
            assertNotNull("The real notification must expose its snooze action", snooze)
            val actionSentAt = System.currentTimeMillis()
            snooze!!.actionIntent.send()
            assertTrue("Snooze action did not persist its deadline and dismiss the delivered notification", waitFor(10_000) {
                (repository.get(id)?.snoozedUntil ?: 0) > actionSentAt &&
                    manager.activeNotifications.none { it.tag == id.toString() && it.id == ReminderReceiver.NOTIFICATION_ID }
            })
            val saved = repository.get(id)!!
            assertEquals("Snoozing must retain the original due date", due, saved.dueAt)
            assertTrue("Snooze must schedule approximately ten minutes after the action",
                saved.snoozedUntil in (actionSentAt + 590_000)..(System.currentTimeMillis() + 610_000))
            assertFalse(saved.completed)
        } finally {
            scheduler.cancel(id)
            repository.delete(id)
        }
    }

    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(150)
        }
        return condition()
    }
}
