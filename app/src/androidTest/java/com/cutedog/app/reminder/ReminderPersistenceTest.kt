package com.cutedog.app.reminder

import android.app.AlarmManager
import android.content.Context
import android.os.Build
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
}
