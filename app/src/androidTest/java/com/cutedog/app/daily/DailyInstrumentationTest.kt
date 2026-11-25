package com.cutedog.app.daily

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android SQLite/AlarmManager/NotificationManager. No fake reminder substitutes. */
@RunWith(AndroidJUnit4::class)
class DailyInstrumentationTest {
    @get:Rule val fixture = DailyDeviceFixture()
    private val context get() = fixture.context

    @Test fun favoritesAndUnfavoritesPersistAcrossRepositoryAndIndependentDatabaseReopen() {
        val day = LocalDate.of(2040, 6, 12)
        val first = DailyRepository(context) { day }.today()
        assertFalse(first.favorite)
        fixture.repository.toggleFavorite(first.id)
        val reopened = DailyRepository(context) { day }
        assertTrue(reopened.today().favorite)
        assertEquals(listOf(first.id), reopened.favorites().map { it.id })
        fixture.database(readOnly = true).use { disk ->
            disk.rawQuery("SELECT quote_id FROM favorites", null).use { rows ->
                assertTrue("Favorite committed to an independently opened real database", rows.moveToFirst())
                assertEquals(first.id, rows.getInt(0)); assertFalse(rows.moveToNext())
            }
        }
        reopened.toggleFavorite(first.id)
        assertFalse(DailyRepository(context) { day }.today().favorite)
        assertTrue(DailyRepository(context).favorites().isEmpty())
        fixture.database(readOnly = true).use { disk ->
            disk.rawQuery("SELECT COUNT(*) FROM favorites", null).use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals(0, rows.getInt(0))
            }
        }
    }

    @Test fun controlledDateChangesArchiveOneQuotePerDayIncludingLeapDayAndClockRollback() {
        // The date source is controlled; this is not evidence of changing Android's system clock.
        var day = LocalDate.of(2040, 2, 28)
        val repository = DailyRepository(context) { day }
        val first = repository.today()
        assertEquals(first, repository.today())
        day = day.plusDays(1)
        val leap = repository.today()
        repository.toggleFavorite(leap.id)
        day = day.plusDays(1)
        val march = repository.today()
        day = day.minusDays(2)
        assertEquals(first, repository.today())
        val archive = DailyRepository(context).history()
        assertEquals(listOf("2040-03-01", "2040-02-29", "2040-02-28"), archive.map { it.date })
        assertEquals(3, archive.map { it.id }.distinct().size)
        assertEquals(3, archive.map { it.text }.distinct().size)
        assertEquals(listOf(false, true, false), archive.map { it.favorite })
        assertEquals(leap.text, DailyRepository(context).favorites().single().text)
        assertNotEquals(first.text, march.text)
        fixture.evidence("controlledDateChanges", "scope.json", JSONObject().put("dateSource", "controlled repository date; not OS clock")
            .put("archivedDates", org.json.JSONArray(archive.map { it.date })).toString(2))
    }

    @Test fun enabledHourAndQuietHoursRegisterRealNonWakeupAlarmAndDisablingCancelsIt() {
        val settings = DailySettings(context)
        settings.enabled = true; settings.quietStart = 0; settings.quietEnd = 0; settings.hour = 9
        assertScheduledAtNextHour("09-no-quiet", 9)
        settings.hour = 18
        assertScheduledAtNextHour("18-no-quiet", 18)
        assertEquals(18, DailySettings(context).hour)
        settings.hour = 23; settings.quietStart = 22; settings.quietEnd = 8
        assertScheduledAtNextHour("23-postponed-to-08", 8)
        val reopened = DailySettings(context)
        assertTrue(reopened.enabled); assertEquals(23, reopened.hour)
        assertEquals(22, reopened.quietStart); assertEquals(8, reopened.quietEnd)
        settings.enabled = false
        DailyScheduler(context).schedule()
        assertFalse(DailySettings(context).enabled)
        assertTrue("Disabled daily must have no active system alarm", dailyAlarms("disabled").isEmpty())
    }

    @Test fun realSystemAlarmDeliversSilentDailyQuoteOnceEvenAfterInAppViewingAndRepeatedReceiver() {
        fixture.requireGrantedNotifications()
        val manager = fixture.manager()
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) assertTrue("Host grants exact alarms only for this bounded trigger fixture", alarms.canScheduleExactAlarms())
        var resumed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).isNotEmpty()
        }
        assertFalse("Daily delivery is checked without a resumed activity", resumed)
        val viewed = fixture.repository.today()
        assertFalse("Viewing in-app must not consume today's notification", fixture.repository.wasGreeted(viewed.date))
        DailySettings(context).enabled = true
        val trigger = PendingIntent.getBroadcast(context, 84999,
            Intent(context, DailyReceiver::class.java).setAction(DailyScheduler.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val requestedAt = System.currentTimeMillis() + 3_000
        try {
            // This short exact test alarm exercises the real production receiver. Production
            // DailyScheduler separately remains inexact RTC; no 24-hour wait is claimed here.
            alarms.setExact(AlarmManager.RTC, requestedAt, trigger)
            assertTrue("System alarm did not deliver and persist the daily claim within 20 seconds", waitFor(20_000) {
                dailyNotification() != null && fixture.repository.wasGreeted(viewed.date)
            })
            assertEquals("Test crossed midnight; retry requires a fresh run, not accepting another day's claim", viewed.date, LocalDate.now().toString())
            val actual = dailyNotification()!!.notification
            assertEquals("CuteDog · 给今天的你", actual.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals(viewed.text, actual.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals(viewed.text, actual.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
            assertEquals(Notification.CATEGORY_RECOMMENDATION, actual.category)
            assertNotEquals(0, actual.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            val channel = manager.getNotificationChannel(DailyDeviceFixture.CHANNEL)
            assertNotNull(channel); assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertNull(channel.sound); assertFalse(channel.shouldVibrate())
            fixture.evidence("realDailyAlarm", "notification.json", JSONObject()
                .put("trigger", "real AlarmManager.setExact RTC test fixture; production daily scheduling is inexact")
                .put("requestedAtEpochMillis", requestedAt).put("observedAtEpochMillis", System.currentTimeMillis())
                .put("date", viewed.date).put("text", viewed.text).put("channelImportance", channel.importance)
                .put("silent", channel.sound == null && !channel.shouldVibrate()).toString(2))
            manager.cancel(DailyDeviceFixture.NOTIFICATION_ID)
            assertTrue(waitFor(3_000) { dailyNotification() == null })
            DailyRepository(context).today()
            dispatchAndAwaitCompletion()
            assertNull("A completed second receiver run must not repost today's dismissed greeting", dailyNotification())
            assertTrue(DailyRepository(context).wasGreeted(viewed.date))
            fixture.database(readOnly = true).use { db -> db.rawQuery("SELECT COUNT(*) FROM greetings", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            } }
        } finally { alarms.cancel(trigger); trigger.cancel() }
    }

    @Test fun disabledAndQuietHoursSuppressCompletedReceiverWithoutConsumingTheDay() {
        fixture.requireGrantedNotifications()
        val settings = DailySettings(context)
        val today = fixture.repository.today()
        settings.enabled = false
        dispatchAndAwaitCompletion()
        assertNull(dailyNotification()); assertFalse(fixture.repository.wasGreeted(today.date))
        settings.enabled = true
        val currentHour = LocalDateTime.now().hour
        settings.quietStart = currentHour; settings.quietEnd = (currentHour + 2) % 24
        dispatchAndAwaitCompletion()
        assertNull("Quiet interval receiver completed without notification", dailyNotification())
        assertFalse("Suppression must leave the day eligible after quiet hours", fixture.repository.wasGreeted(today.date))
        assertEquals(today.text, DailyRepository(context).today().text)
        settings.quietStart = 0; settings.quietEnd = 0
        dispatchAndAwaitCompletion()
        assertTrue("A later eligible receiver can still greet today", waitFor(5_000) { dailyNotification() != null })
        assertTrue(fixture.repository.wasGreeted(today.date))
    }

    @Test fun deniedNotificationPermissionRetainsDailyQuoteWithoutClaimingDelivery() {
        assumeTrue("POST_NOTIFICATIONS exists only on API 33+", Build.VERSION.SDK_INT >= 33)
        assumeTrue("Run in the host's separate denied phase; never revoke inside instrumentation",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        DailySettings(context).enabled = true
        val today = fixture.repository.today()
        fixture.repository.toggleFavorite(today.id)
        dispatchAndAwaitCompletion()
        assertNull("Permission denial must prevent the daily notification", dailyNotification())
        assertFalse("Denied delivery must not mark the day sent", fixture.repository.wasGreeted(today.date))
        val reopened = DailyRepository(context)
        assertEquals(today.text, reopened.today().text)
        assertTrue(reopened.today().favorite)
        assertEquals(today.date, reopened.history().single().date)
        fixture.evidence("deniedDailyNotification", "result.json", JSONObject()
            .put("trigger", "explicit ordered broadcast, waited for DailyReceiver.goAsync completion")
            .put("permission", "denied").put("notificationObserved", false).put("deliveryClaimed", false)
            .put("inAppDate", today.date).put("favoriteRetained", true).toString(2))
    }

    private fun dispatchAndAwaitCompletion() {
        val completed = CountDownLatch(1)
        context.sendOrderedBroadcast(Intent(context, DailyReceiver::class.java).setAction(DailyScheduler.ACTION), null,
            object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { completed.countDown() } },
            Handler(Looper.getMainLooper()), 0, null, null)
        assertTrue("DailyReceiver must finish its asynchronous delivery before negative assertions", completed.await(10, TimeUnit.SECONDS))
    }

    private fun dailyNotification() = fixture.manager().activeNotifications.singleOrNull {
        it.id == DailyDeviceFixture.NOTIFICATION_ID && it.tag == null
    }

    private fun assertScheduledAtNextHour(label: String, hour: Int) {
        val before = ZonedDateTime.now()
        DailyScheduler(context).schedule()
        val after = ZonedDateTime.now()
        fun next(now: ZonedDateTime): Long {
            var value = now.toLocalDate().atTime(hour, 0).atZone(now.zone)
            if (!value.isAfter(now)) value = value.plusDays(1)
            return value.toInstant().toEpochMilli()
        }
        val rows = dailyAlarms(label)
        assertEquals("Exactly one actual registered daily alarm", 1, rows.size)
        val (header, details) = rows.single()
        assertTrue("Daily alarm must be RTC, not a wakeup", header.trimStart().startsWith("RTC #"))
        val epoch = Regex("\\b(?:origWhen|when) (\\d+)\\b").find(header)?.groupValues?.get(1)?.toLong()
        assertNotNull("Read requested wall-clock epoch from real alarm dump: $header", epoch)
        assertTrue("$label: requested epoch $epoch must be next local $hour:00", epoch in setOf(next(before), next(after)))
        assertTrue("Daily must not repeat automatically", details.contains("repeatInterval=0"))
        assertTrue("Production daily alarm must stay inexact", Regex("window=\\+[^\\s]+").containsMatchIn(details))
    }

    private fun dailyAlarms(label: String): List<Pair<String, String>> {
        val raw = fixture.shell("dumpsys alarm")
        fixture.evidence("registeredDailyAlarm", "$label-alarm-dump.txt", raw)
        val headerPattern = Regex("^\\s+RTC(?:_WAKEUP)? #\\d+: Alarm\\{.* ${Regex.escape(context.packageName)}\\}$")
        val lines = raw.lines()
        return lines.indices.mapNotNull { index ->
            val header = lines[index]
            if (!headerPattern.matches(header)) return@mapNotNull null
            val following = lines.drop(index + 1).take(6)
            if (following.firstOrNull()?.trim() != "tag=*alarm*:${DailyScheduler.ACTION}") return@mapNotNull null
            header to following.joinToString("\n")
        }
    }

    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }
}
