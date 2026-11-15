package com.cutedog.app.reminder

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

/**
 * Invoked only by scripts/system-recovery-validation.py, never by AndroidJUnitRunner.
 * No production component is exported for this probe. During a system event the host
 * observes system_server; it must not restart this instrumentation until observation ends.
 */
class SystemRecoveryProbeRunner : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        try {
            val run = argument("recoveryRunId").also { require(it.matches(Regex("[0-9a-f]{32}"))) }
            val scenario = argument("recoveryScenario").also { require(it in setOf("kill", "reboot", "doze", "timezone")) }
            val operation = argument("recoveryOperation")
            val result = when (operation) {
                "seed" -> seed(run, scenario)
                "inspect" -> inspect(run, scenario)
                "cleanup" -> cleanup(run, scenario)
                else -> error("Unknown recovery operation")
            }.put("operation", operation).put("runId", run).put("scenario", scenario).put("status", "ok")
            finish(Activity.RESULT_OK, Bundle().apply { putString("recovery", result.toString()) })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("recovery", JSONObject().put("status", "error")
                    .put("error", "${error.javaClass.simpleName}: ${error.message}").toString())
            })
        }
    }

    private fun argument(name: String): String = requireNotNull(arguments.getString(name)) { "Missing $name" }
    private fun title(run: String, scenario: String) = "Recovery $scenario $run"
    private fun category(run: String) = "system-recovery-$run"
    private fun file(run: String) = File(targetContext.filesDir, "system-recovery/$run.json")

    private fun seed(run: String, scenario: String): JSONObject {
        val repository = ReminderRepository(targetContext)
        // Never clear a real database to make the test fit. The host requires a disposable emulator.
        require(repository.list().isEmpty()) { "Recovery probe requires an empty reminder database" }
        require(!file(run).exists()) { "Fixture run ID already exists" }
        val manager = targetContext.getSystemService(NotificationManager::class.java)
        require(Build.VERSION.SDK_INT < 33 || targetContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            "Host must grant notification permission before seeding"
        }
        require(manager.areNotificationsEnabled()) { "Notifications are disabled" }
        require(manager.getNotificationChannel(ReminderReceiver.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE) {
            "Reminder notification channel is disabled"
        }
        val scheduler = ReminderScheduler(targetContext)
        require(scheduler.canScheduleExact()) { "Bounded system recovery verification requires exact alarm access" }
        val source = argument("recoverySourceCommit").also { require(it.matches(Regex("[0-9a-f]{40}"))) }
        val appHash = argument("recoveryApplicationSha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val testHash = argument("recoveryInstrumentationSha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val delay = argument("recoveryDelayMillis").toLong().also { require(it in 20_000..180_000) }
        val now = System.currentTimeMillis()
        val due = if (scenario == "timezone") now - 15_000 else now + delay
        val item = Reminder(title = title(run, scenario), details = "Isolated host recovery fixture $run",
            dueAt = due, category = category(run), priority = 3, timeZoneId = TimeZone.getDefault().id)
        val id = repository.save(item)
        val stored = requireNotNull(repository.get(id))
        if (scenario != "timezone") scheduler.schedule(stored)
        // The timezone fixture intentionally has no alarm: only a real system restore event can deliver it.
        val result = JSONObject().put("runId", run).put("scenario", scenario).put("reminderId", id)
            .put("title", stored.title).put("dueAt", due).put("timeZoneId", stored.timeZoneId)
            .put("scheduled", scenario != "timezone").put("pid", Process.myPid())
            .put("createdAt", now).put("elapsedRealtime", SystemClock.elapsedRealtime())
            .put("sourceCommit", source).put("applicationSha256", appHash).put("instrumentationSha256", testHash)
        val destination = file(run)
        destination.parentFile!!.mkdirs()
        val staging = File(destination.parentFile, "$run.tmp")
        FileOutputStream(staging).use { output -> output.write(result.toString().toByteArray()); output.fd.sync() }
        check(staging.renameTo(destination)) { "Cannot publish ready fixture metadata" }
        if (scenario == "kill") {
            // Host kills this actual PID after reading the durable ready file. This deliberately
            // terminates instrumentation; its exit is not a JUnit pass/fail result.
            val timeout = SystemClock.elapsedRealtime() + 120_000
            while (SystemClock.elapsedRealtime() < timeout) SystemClock.sleep(200)
            error("Host did not kill the ready probe process within 120 seconds")
        }
        return result
    }

    private fun fixture(run: String, scenario: String): JSONObject {
        val saved = JSONObject(file(run).readText())
        require(saved.getString("runId") == run && saved.getString("scenario") == scenario)
        require(saved.getString("sourceCommit") == argument("recoverySourceCommit"))
        require(saved.getString("applicationSha256") == argument("recoveryApplicationSha256"))
        require(saved.getString("instrumentationSha256") == argument("recoveryInstrumentationSha256"))
        return saved
    }

    private fun inspect(run: String, scenario: String): JSONObject {
        val saved = fixture(run, scenario)
        val repository = ReminderRepository(targetContext)
        val id = saved.getLong("reminderId")
        val item = repository.get(id)
        if (item != null) require(item.title == title(run, scenario) && item.category == category(run)) {
            "Fixture identity does not match database row"
        }
        val records = JSONArray()
        repository.deliveryHistory(5000).filter { it.reminderId == id }.forEach { record ->
            records.put(JSONObject().put("reminderId", record.reminderId).put("token", record.token)
                .put("status", record.status).put("attempts", record.attempts).put("dueAt", record.dueAt)
                .put("lastAttemptAt", record.lastAttemptAt).put("createdAt", record.createdAt)
                .put("isSnooze", record.isSnooze))
        }
        // Deliberately no restoreAll(), retryPending(), Activity launch, or notification action.
        return JSONObject().put("readOnly", true).put("fixture", saved).put("deliveries", records)
            .put("item", if (item == null) JSONObject.NULL else JSONObject().put("id", item.id)
                .put("dueAt", item.dueAt).put("completed", item.completed).put("timeZoneId", item.timeZoneId)
                .put("title", item.title).put("category", item.category))
    }

    private fun cleanup(run: String, scenario: String): JSONObject {
        val repository = ReminderRepository(targetContext)
        // Exact random-run title/category identifies only our rows, even if seed failed before ready-file write.
        val ids = repository.list().filter { it.title == title(run, scenario) && it.category == category(run) }.map { it.id }
        val scheduler = ReminderScheduler(targetContext)
        ids.forEach { id -> repository.delete(id); scheduler.cancel(id) }
        file(run).delete()
        File(file(run).parentFile, "$run.tmp").delete()
        return JSONObject().put("deletedFixtureIds", JSONArray(ids)).put("fixtureFileRemoved", !file(run).exists())
    }
}
