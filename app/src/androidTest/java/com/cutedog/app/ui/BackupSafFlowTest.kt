package com.cutedog.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.MainActivity
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import com.cutedog.app.reminder.ReminderScheduler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real CreateDocument/OpenDocument UI, including the system DocumentsUI and its returned grants.
 * No injected ActivityResult, replacement contract, direct import/export/restore call, or storage
 * permission is used. Requires an explicitly disposable emulator with an empty reminder database.
 * Repository access only observes actual state or removes this test's own fixtures during cleanup.
 */
class BackupSafFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val prefix = "SAF-${UUID.randomUUID().toString().take(12)}-"
    private val documentName = "${prefix}事项备份.json"
    private val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var evidence: File
    private val metadata = JSONObject()
    private val events = JSONArray()
    private var stage = "preflight"
    private var fixtureSession = false
    private var hadIntroduction = false
    private var introductionValue = false
    private var serviceFlags: Int? = null
    private var exportedUri: Uri? = null
    private var exportedDocumentDeleted = false
    private var observer: ResultObserver? = null
    private var pickerPackages = emptySet<String>()

    private val isolate = object : ExternalResource() {
        override fun before() {
            val phase = arguments.getString("cuteDogEvidencePhase", "unknown")
            require(phase.matches(Regex("[a-z_]+")))
            evidence = File(context.getExternalFilesDir(null), "ui-test-evidence/$phase/$prefix").apply { mkdirs() }
            metadata.put("schemaVersion", 1).put("fixturePrefix", prefix).put("api", Build.VERSION.SDK_INT)
                .put("sourceCommit", arguments.getString("cuteDogSourceCommit"))
                .put("runId", arguments.getString("cuteDogRunId"))
                .put("runAttempt", arguments.getString("cuteDogRunAttempt"))
                .put("applicationSha256", sha256(File(context.applicationInfo.sourceDir)))
                .put("instrumentationSha256", sha256(File(instrumentation.context.applicationInfo.sourceDir)))
                .put("status", "not_run").put("events", events)
            persist()
            require(arguments.getString("cuteDogDisposable") == "true") {
                "SAF replacement requires the host's explicit disposable-emulator contract"
            }
            require(arguments.getString("cuteDogSourceCommit", "").matches(Regex("[0-9a-f]{40}"))) {
                "SAF evidence requires a full source commit"
            }
            require(shell("getprop ro.kernel.qemu").trim() == "1") { "SAF replacement refuses physical devices" }
            val repository = ReminderRepository(context)
            val count = repository.list().size
            val deliveries = scalar("SELECT COUNT(*) FROM deliveries")
            val snapshots = scalar("SELECT COUNT(*) FROM reminder_backups")
            metadata.put("initialCounts", JSONObject().put("reminders", count).put("deliveries", deliveries).put("snapshots", snapshots))
            if (count != 0 || deliveries != 0L || snapshots != 0L) {
                metadata.put("status", "skipped").put("reason", "Disposable emulator contains reminder data; no mutation or content capture performed")
                persist()
            }
            // Deliberate safety skip; fresh CI must not have data, and its host rejects this
            // unexpected skip instead of reporting the SAF business scenario as passing.
            assumeTrue("SAF requires an empty disposable reminder database; existing data is preserved", count == 0 && deliveries == 0L && snapshots == 0L)
            fixtureSession = true
            val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
            hadIntroduction = prefs.contains("completed")
            introductionValue = prefs.getBoolean("completed", false)
            prefs.edit().putBoolean("completed", true).commit()
            val info = automation.serviceInfo
            serviceFlags = info.flags
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
        }

        override fun after() {
            if (!fixtureSession) return
            val edit = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit()
            if (hadIntroduction) edit.putBoolean("completed", introductionValue) else edit.remove("completed")
            edit.commit()
            serviceFlags?.let { flags -> automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags } }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolate).around(compose)

    @Test fun realSystemDocumentExportMergeReplaceAndUndoPreserveTheSelectedContent() {
        var originalFailure: Throwable? = null
        try {
            metadata.put("status", "running")
            inspectPickers()
            stage = "create-fixtures"
            createReminder("A", "中文、换行\n只有这个隔离测试的内容 A")
            createReminder("B", "待导出再恢复的内容 B")
            val exported = observedRows("seeded")
            assertEquals(2, exported.size)
            openBackup()
            compose.activityRule.scenario.onActivity { activity -> observer = ResultObserver(activity.activityResultRegistry) }

            stage = "export-create-document"
            val beforeExport = requireNotNull(observer).results.size
            compose.onNodeWithTag("reminders_export").performClick()
            awaitPicker()
            capture("create-picker-open")
            chooseDownloads()
            val filename = awaitUncoveredNode("CreateDocument filename field") { node ->
                node.className?.toString() == "android.widget.EditText" && node.isEnabled
            }
            assertTrue("Actual DocumentsUI filename accepts text", filename.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, documentName) }))
            capture("create-picker-local-destination")
            clickPickerControl("DocumentsUI Save button") { node ->
                node.isEnabled && node.text?.toString()?.trim() in setOf("Save", "SAVE", "保存")
            }
            val created = awaitResult(beforeExport, "create")
            exportedUri = requireNotNull(created.uri) { "Real CreateDocument result was cancelled" }
            assertTrue("Returned URI must be a document URI", DocumentsContract.isDocumentUri(context, exportedUri!!))
            assertEquals(documentName, displayName(exportedUri!!))
            val bytes = awaitExportedBytes(exportedUri!!)
            File(evidence, "exported-fixture.json").writeBytes(bytes)
            val document = JSONObject(String(bytes, Charsets.UTF_8))
            assertEquals(setOf("format", "schemaVersion", "exportedAt", "reminders"), document.keys().asSequence().toSet())
            assertEquals("CuteDog.Reminders", document.getString("format"))
            assertEquals(1, document.getInt("schemaVersion"))
            assertEquals(exported.keys, (0 until document.getJSONArray("reminders").length()).map {
                document.getJSONArray("reminders").getJSONObject(it).getString("title")
            }.toSet())
            exported.forEach { (title, expected) ->
                val array = document.getJSONArray("reminders")
                val item = (0 until array.length()).map(array::getJSONObject).single { it.getString("title") == title }
                assertEquals(expected.details, item.getString("details"))
                assertEquals(expected.dueAt, item.getLong("dueAt"))
                assertEquals(expected.repeat, item.getString("repeat"))
                assertEquals(expected.completed, item.getBoolean("completed"))
            }
            metadata.put("exportedDocument", JSONObject().put("uri", exportedUri.toString())
                .put("displayName", documentName).put("bytes", bytes.size).put("sha256", sha256(bytes)))
            capture("export-returned-to-app")
            closeBackup()

            stage = "prepare-merge-via-ui"
            deleteReminder("B")
            createReminder("C", "本机独有事项，合并及撤销替换必须保留")
            val localBeforeMerge = observedRows("before-merge")
            assertEquals(setOf(prefix + "A", prefix + "C"), localBeforeMerge.keys)
            openBackup()
            selectExportedDocument("merge")
            assertEquals(localBeforeMerge, observedRows("merge-preview-no-write"))
            compose.onNodeWithText("合并，保留原有事项（推荐）").performScrollTo().performClick()
            compose.onNodeWithText("确认恢复").performScrollTo().performClick()
            val merged = localBeforeMerge + (prefix + "B" to exported.getValue(prefix + "B"))
            awaitRows(merged)
            observedRows("merged")
            assertEquals("Merge does not create a replacement snapshot", 0L, scalar("SELECT COUNT(*) FROM reminder_backups"))
            capture("merge-complete")

            stage = "replace-through-picker-and-confirmation"
            selectExportedDocument("replace")
            assertEquals(merged, observedRows("replace-preview-no-write"))
            compose.onNodeWithText("替换本机全部事项").performScrollTo().performClick()
            compose.onNodeWithText("确认恢复").performScrollTo().performClick()
            compose.onNodeWithText("替换本机全部事项？").assertIsDisplayed()
            capture("replace-confirmation")
            compose.onNodeWithText("取消").performClick()
            assertEquals(merged, observedRows("replace-cancelled-no-write"))
            compose.onNodeWithText("确认恢复").performScrollTo().performClick()
            compose.onNodeWithText("确认替换").performClick()
            awaitRows(exported)
            observedRows("replaced")
            assertEquals(1L, scalar("SELECT COUNT(*) FROM reminder_backups"))
            capture("replace-complete")

            stage = "undo-replacement-via-ui"
            compose.onNodeWithText("撤销本次替换").performScrollTo().performClick()
            awaitRows(merged)
            observedRows("undo-restored")
            compose.onNodeWithText("已恢复替换前的事项。").assertExists()
            capture("undo-complete")
            // Complete document cleanup before recreating the Activity that owns the temporary
            // URI grant. No durable file grant is required by this one-shot product operation.
            deleteExportedFixture()
            closeBackup()
            observer?.closeOnMainThread()
            observer = null
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("nav_1").performClick()
            assertEquals(merged, observedRows("activity-recreated"))
            compose.onNodeWithTag("reminders_list").performScrollToNode(hasText(prefix + "C"))
            compose.onNodeWithText(prefix + "C").assertIsDisplayed()
            capture("recreated-user-visible-state")
            metadata.put("status", "passed")
        } catch (failure: Throwable) {
            originalFailure = failure
            metadata.put("status", "failed").put("failedStage", stage)
                .put("failureClass", failure.javaClass.name).put("failure", failure.message)
            runCatching { capture("failure-before-cleanup") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            val cleanupErrors = cleanupFixtures()
            metadata.put("cleanupErrors", JSONArray(cleanupErrors)).put("finishedElapsedMs", SystemClock.elapsedRealtime())
            if (cleanupErrors.isNotEmpty()) metadata.put("status", "failed")
            persist()
            if (cleanupErrors.isNotEmpty()) {
                val cleanupFailure = AssertionError("SAF fixture cleanup failed: ${cleanupErrors.joinToString()}")
                if (originalFailure == null) throw cleanupFailure else originalFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun inspectPickers() {
        val resolved = listOf(Intent.ACTION_CREATE_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT).map { action ->
            // Shell inspection avoids adding production <queries> merely for test discovery.
            // The app still launches its unchanged ActivityResultContract through real UI.
            val raw = shell("cmd package resolve-activity --brief -a $action -c android.intent.category.OPENABLE -t application/json")
            val component = raw.lineSequence().map(String::trim).singleOrNull {
                it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]+"))
            } ?: error("No unambiguous system document picker for $action: $raw")
            val packageName = component.substringBefore('/')
            require(packageName.endsWith(".documentsui")) { "Unreviewed system picker: $packageName" }
            require(shell("cmd package list packages -s $packageName").lineSequence().any { it.trim() == "package:$packageName" }) {
                "Document picker must be an installed system app"
            }
            val dump = shell("dumpsys -t 5 package $packageName")
            File(evidence, "picker-${action.substringAfterLast('.')}.txt").writeText(raw + "\n" + dump)
            JSONObject().put("action", action).put("package", packageName).put("component", component)
                .put("versionName", Regex("versionName=([^\\s]+)").find(dump)?.groupValues?.get(1))
                .put("versionCode", Regex("versionCode=(\\d+)").find(dump)?.groupValues?.get(1))
        }
        pickerPackages = resolved.map { it.getString("package") }.toSet()
        metadata.put("systemPickers", JSONArray(resolved))
        persist()
    }

    private fun createReminder(suffix: String, details: String) {
        compose.onNodeWithTag("nav_1").performClick()
        compose.onNodeWithTag("reminder_new").performClick()
        compose.onNodeWithTag("reminder_title").performTextInput(prefix + suffix)
        compose.onNodeWithTag("reminder_details").performTextInput(details)
        compose.onNodeWithTag("reminder_save").performClick()
        compose.waitUntil(10_000) { ReminderRepository(context).list().any { it.title == prefix + suffix } }
        compose.waitForIdle()
    }

    private fun deleteReminder(suffix: String) {
        val item = ReminderRepository(context).list().single { it.title == prefix + suffix }
        compose.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("reminder_${item.id}"))
        compose.onNodeWithTag("reminder_${item.id}").performClick()
        compose.onNodeWithText("删除这件事项").performScrollTo().performClick()
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { ReminderRepository(context).get(item.id) == null }
    }

    private fun openBackup() {
        compose.onNodeWithTag("reminders_list").performScrollToNode(hasTestTag("reminder_backup"))
        compose.onNodeWithTag("reminder_backup").performClick()
        compose.onNodeWithTag("reminders_export").assertIsDisplayed()
    }

    private fun closeBackup() {
        compose.onNode(hasText("完成") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("reminders_export").assertDoesNotExist()
    }

    private fun selectExportedDocument(label: String) {
        compose.onNodeWithTag("reminders_import").performScrollTo()
        compose.waitForIdle()
        // The real Compose launcher re-registers when its newly constructed OpenDocument
        // contract changes during recomposition (for example after the first merge). Observe
        // the current registration for EACH launch, never the previous selection's callback.
        compose.activityRule.scenario.onActivity { activity ->
            observer?.close()
            observer = ResultObserver(activity.activityResultRegistry)
        }
        val before = requireNotNull(observer).results.size
        compose.onNodeWithTag("reminders_import").performClick()
        awaitPicker()
        capture("$label-open-picker")
        chooseDownloads()
        instrumentation.runOnMainSync { requireNotNull(observer).assertAttached("open") }
        touchExportedDocument(label)
        val result = awaitResult(before, "open")
        val selected = requireNotNull(result.uri) { "Real OpenDocument result was cancelled" }
        assertEquals(documentName, displayName(selected))
        val actual = context.contentResolver.openInputStream(selected)!!.use { it.readBytes() }
        assertEquals("The actual selected document must equal the actual exported bytes", metadata.getJSONObject("exportedDocument").getString("sha256"), sha256(actual))
        events.put(JSONObject().put("stage", label).put("selectedUri", selected.toString()).put("selectedDocumentSha256", sha256(actual)))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("准备恢复 2 件事项").fetchSemanticsNodes().isNotEmpty() }
        capture("$label-import-preview")
    }

    private fun chooseDownloads() {
        val downloads = setOf("Downloads", "下载", "下载内容")
        fun isCurrentDirectory(node: AccessibilityNodeInfo) = node.text?.toString() in downloads &&
            (ancestorHasId(node, "toolbar") || ancestorHasId(node, "directory_header"))
        // DocumentsUI restores its last location independently for create and open. Opening
        // the roots drawer again would replace the accessibility rows of an already selected root.
        val observed = nodes()
        val activeWindow = automation.windows.firstOrNull { it.isActive && it.root?.packageName?.toString() in pickerPackages }
        if (observed.any { node ->
                node.windowId == activeWindow?.id && node.isVisibleToUser && isCurrentDirectory(node) &&
                    !behindRootsDrawer(node, observed)
            }) return
        val windowWidth = activeWindow?.let { Rect().also(it::getBoundsInScreen).width() } ?: 0
        val drawerOpen = observed.any { node ->
            node.windowId == activeWindow?.id && node.isVisibleToUser && node.viewIdResourceName?.endsWith("/roots_list") == true &&
                windowWidth > 0 && Rect().also(node::getBoundsInScreen).width() > windowWidth / 3
        }
        if (!drawerOpen) {
            clickPickerControl("DocumentsUI navigation drawer") { node ->
                node.viewIdResourceName == "android:id/home" || node.contentDescription?.toString() in
                    setOf("Show roots", "显示根目录", "显示导航抽屉", "打开导航抽屉")
            }
        }
        clickPickerControl("Local Downloads root") { node ->
            node.text?.toString() in downloads && ancestorHasId(node, "roots_list")
        }
        awaitUncoveredNode("Actual Downloads directory heading", ::isCurrentDirectory)
    }

    private fun touchExportedDocument(label: String) {
        // DocumentsUI reports accessible file rows while its whole window is still sliding
        // in. A cached center can land outside that file when the transition finishes.
        fun settledTitle(): Pair<AccessibilityNodeInfo, Rect> {
            var previous: Pair<Int, Rect>? = null
            var stableSince = 0L
            var result: Pair<AccessibilityNodeInfo, Rect>? = null
            awaitCondition("Stationary exported file title in the real document picker") {
                val active = automation.windows.firstOrNull { it.isActive && it.root?.packageName?.toString() in pickerPackages }
                    ?: return@awaitCondition false
                val tree = nodes()
                val title = tree.firstOrNull { it.windowId == active.id && it.text?.toString() == documentName && it.isVisibleToUser }
                    ?: return@awaitCondition false
                if (!title.refresh() || !title.isVisibleToUser || !title.isEnabled || title.text?.toString() != documentName ||
                    behindRootsDrawer(title, tree)) return@awaitCondition false
                val bounds = Rect().also(title::getBoundsInScreen)
                if (bounds.isEmpty || !Rect().also(active::getBoundsInScreen).contains(bounds.centerX(), bounds.centerY())) return@awaitCondition false
                val key = active.id to bounds
                val now = SystemClock.uptimeMillis()
                if (key != previous) { previous = key; stableSince = now }
                result = title to bounds
                now - stableSince >= 400
            }
            return requireNotNull(result)
        }
        settledTitle()
        capture("$label-before-document-touch")
        val (node, bounds) = settledTitle()
        events.put(JSONObject().put("stage", "$label-document-touch")
            .put("package", node.packageName?.toString()).put("windowId", node.windowId)
            .put("displayName", node.text?.toString()).put("bounds", bounds.toShortString())
            .put("pointerToolType", MotionEvent.TOOL_TYPE_FINGER).put("touchDurationMs", 100)
            .put("elapsedMs", SystemClock.elapsedRealtime()))
        touchBounds(bounds)
    }

    private fun touchBounds(bounds: Rect) {
        // API 26's RecyclerView handles touch while every file-title ancestor reports
        // clickable=false. Touch the observed title, away from API 35's preview-icon button.
        // Android 15 DocumentsUI configures SelectionTracker for FINGER/STYLUS. The short
        // MotionEvent.obtain overload leaves toolType UNKNOWN even with source TOUCHSCREEN,
        // which can be injected successfully without activating a document. Set both fields.
        // This still goes through real DocumentsUI input; no Intent or URI is supplied.
        val downAt = SystemClock.uptimeMillis()
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = bounds.exactCenterX(); y = bounds.exactCenterY(); pressure = 1f; size = 1f
        }
        fun inject(action: Int) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, 1,
                arrayOf(pointer), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                check(event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)
                assertTrue("Actual system file touch $action", automation.injectInputEvent(event, true))
            }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        try { SystemClock.sleep(100) } finally { inject(MotionEvent.ACTION_UP) }
    }

    private fun ancestorHasId(node: AccessibilityNodeInfo, suffix: String): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(10) {
            val value = current ?: return false
            if (value.viewIdResourceName?.endsWith("/$suffix") == true) return true
            current = value.parent
        }
        return false
    }

    private fun awaitPicker() = awaitCondition("Actual system document picker window") {
        automation.windows.any { it.root?.packageName?.toString() in pickerPackages }
    }

    private fun awaitNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        awaitCondition(description) { found = nodes().firstOrNull { it.isVisibleToUser && predicate(it) }; found != null }
        return requireNotNull(found)
    }

    private fun awaitUncoveredNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        awaitCondition("$description with its center outside the roots drawer") {
            val observed = nodes()
            found = observed.firstOrNull { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                node.isVisibleToUser && node.isEnabled && predicate(node) && !bounds.isEmpty && !behindRootsDrawer(node, observed)
            }
            found != null
        }
        return requireNotNull(found)
    }

    private fun behindRootsDrawer(node: AccessibilityNodeInfo, observed: List<AccessibilityNodeInfo>): Boolean {
        val center = Rect().also(node::getBoundsInScreen)
        return observed.any { drawer ->
            drawer.windowId == node.windowId && drawer.isVisibleToUser && drawer.viewIdResourceName?.endsWith("/roots_list") == true &&
                Rect().also(drawer::getBoundsInScreen).contains(center.centerX(), center.centerY())
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || result.size >= 5000) return
            result += node
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() in pickerPackages }.forEach(::walk)
        return result
    }

    private fun clickPickerControl(description: String, matches: (AccessibilityNodeInfo) -> Boolean) {
        var previous: Pair<Int, Rect>? = null
        awaitCondition(description) {
            val window = automation.windows.firstOrNull { it.isActive && it.root?.packageName?.toString() in pickerPackages }
                ?: return@awaitCondition false
            val node = nodes().firstOrNull { it.windowId == window.id && it.isVisibleToUser && it.isEnabled && matches(it) }
                ?: return@awaitCondition false
            if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || !matches(node)) return@awaitCondition false
            val bounds = Rect().also(node::getBoundsInScreen)
            val available = Rect().also(window::getBoundsInScreen)
            if (bounds.isEmpty || !available.contains(bounds.centerX(), bounds.centerY())) return@awaitCondition false
            val location = window.id to bounds
            if (previous != location) { previous = location; return@awaitCondition false }
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@awaitCondition true
                current = current.parent
            }
            // Some system versions expose the row title with no clickable ancestor.
            touchBounds(bounds)
            true
        }
    }

    private fun awaitCondition(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Timed out waiting for $description")
    }

    private fun awaitResult(previous: Int, contract: String): SelectedResult {
        val results = requireNotNull(observer).results
        awaitCondition("real $contract document result") { results.size > previous }
        assertEquals("Exactly one actual result must reach the app", previous + 1, results.size)
        return results[previous].also { assertEquals(contract, it.contract) }
    }

    private fun displayName(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
        assertTrue(it.moveToFirst()); it.getString(0)
    }

    private fun awaitExportedBytes(uri: Uri): ByteArray {
        var bytes = ByteArray(0)
        awaitCondition("app completes writing the real exported URI") {
            bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            runCatching { JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("reminders").length() == 2 }.getOrDefault(false)
        }
        return bytes
    }

    private fun rows(): Map<String, Reminder> {
        val rows = ReminderRepository(context).list()
        check(rows.all { it.title.startsWith(prefix) }) { "Foreign reminder appeared; refuse to replace non-fixture data" }
        return rows.associate { it.title to it.copy(id = 0) }
    }

    private fun observedRows(label: String): Map<String, Reminder> = rows().also { values ->
        events.put(JSONObject().put("stage", label).put("elapsedMs", SystemClock.elapsedRealtime())
            .put("rows", JSONArray(values.values.map { JSONObject().put("title", it.title).put("details", it.details)
                .put("dueAt", it.dueAt).put("completed", it.completed).put("repeat", it.repeat)
                .put("priority", it.priority).put("category", it.category).put("leadMinutes", it.leadMinutes)
                .put("timeZoneId", it.timeZoneId).put("anchorDay", it.anchorDay).put("anchorTime", it.anchorTime)
                .put("snoozedUntil", it.snoozedUntil) })))
        persist()
    }

    private fun awaitRows(expected: Map<String, Reminder>) {
        compose.waitUntil(15_000) { rows() == expected }
        assertEquals(expected, rows())
    }

    private fun scalar(sql: String): Long = ReminderRepository(context).database.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }

    private fun cleanupFixtures(): List<String> {
        val errors = mutableListOf<String>()
        fun attempt(name: String, action: () -> Unit) { try { action() } catch (e: Throwable) { errors += "$name: ${e.javaClass.simpleName}: ${e.message}" } }
        if (exportedUri == null && !exportedDocumentDeleted) exportedUri = observer?.results?.firstOrNull { it.contract == "create" }?.uri
        attempt("restore-test-result-observer") { observer?.closeOnMainThread(); observer = null }
        // Temporary grants suffice. Delete while the original Activity is alive; no persistable
        // permission is acquired or added to production purely to make this test work.
        attempt("delete-exported-fixture-only") { deleteExportedFixture() }
        attempt("remove-only-fixture-reminders") {
            val repository = ReminderRepository(context)
            repository.list().filter { it.title.startsWith(prefix) }.forEach { ReminderScheduler(context).cancel(it.id); repository.delete(it.id) }
            check(repository.list().none { it.title.startsWith(prefix) })
        }
        attempt("remove-only-fixture-snapshots") {
            val db = ReminderRepository(context).database
            val ids = db.rawQuery("SELECT id, payload FROM reminder_backups", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val reminders = JSONObject(cursor.getString(1)).getJSONObject("public").getJSONArray("reminders")
                        if (reminders.length() > 0 && (0 until reminders.length()).all { reminders.getJSONObject(it).getString("title").startsWith(prefix) }) add(cursor.getString(0))
                    }
                }
            }
            ids.forEach { db.delete("reminder_backups", "id=?", arrayOf(it)) }
        }
        return errors
    }

    private fun deleteExportedFixture() {
        val uri = exportedUri ?: return
        check(displayName(uri) == documentName)
        check(DocumentsContract.deleteDocument(context.contentResolver, uri)) { "Document provider did not delete fixture" }
        exportedUri = null
        exportedDocumentDeleted = true
    }

    private fun capture(label: String) {
        val directory = File(evidence, label).apply { mkdirs() }
        val screenshot = requireNotNull(automation.takeScreenshot()) { "System screenshot unavailable" }
        File(directory, "screen.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        val windows = JSONArray()
        fun tree(node: AccessibilityNodeInfo?, depth: Int = 0): JSONObject? {
            if (node == null || depth > 40) return null
            val bounds = Rect().also(node::getBoundsInScreen)
            return JSONObject().put("package", node.packageName?.toString()).put("id", node.viewIdResourceName)
                .put("class", node.className?.toString()).put("text", node.text?.toString())
                .put("description", node.contentDescription?.toString()).put("bounds", bounds.toShortString())
                .put("visible", node.isVisibleToUser).put("clickable", node.isClickable).put("enabled", node.isEnabled)
                .put("children", JSONArray((0 until node.childCount).mapNotNull { tree(node.getChild(it), depth + 1) }))
        }
        automation.windows.forEach { window -> windows.put(JSONObject().put("type", window.type).put("active", window.isActive).put("root", tree(window.root))) }
        File(directory, "windows.json").writeText(windows.toString(2))
        File(directory, "activities.txt").writeText(shell("dumpsys -t 5 activity activities"))
        events.put(JSONObject().put("capture", label).put("stage", stage).put("elapsedMs", SystemClock.elapsedRealtime()))
        persist()
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(16384)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun persist() { File(evidence, "saf-evidence.json").writeText(metadata.toString(2)) }

    private data class SelectedResult(val contract: String, val uri: Uri?)

    /**
     * Test-only observer for the locked androidx.activity 1.10.1 registry. Observe the URI that
     * the real contract parsed from the real system result, then invoke its original callback
     * exactly once. Never dispatch an invented result or replace a contract/launcher/Intent.
     * Fail closed if AndroidX changes its internal structure; restore only our own entries.
     */
    private inner class ResultObserver(private val registry: ActivityResultRegistry) {
        val results = CopyOnWriteArrayList<SelectedResult>()
        private val replacements = mutableMapOf<String, Pair<Any, Any>>()
        private val contractKeys = mutableMapOf<String, String>()
        private val field = ActivityResultRegistry::class.java.getDeclaredField("keyToCallback").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") private val entries get() = field.get(registry) as MutableMap<String, Any>

        init {
            val holder = Class.forName("androidx.activity.result.ActivityResultRegistry\$CallbackAndContract")
            val callbackField = holder.getDeclaredField("callback").apply { isAccessible = true }
            val contractField = holder.getDeclaredField("contract").apply { isAccessible = true }
            val constructor = holder.getDeclaredConstructor(ActivityResultCallback::class.java, ActivityResultContract::class.java).apply { isAccessible = true }
            val documents = entries.toMap().mapNotNull { (key, original) ->
                val contract = contractField.get(original)
                val kind = when (contract) {
                    is ActivityResultContracts.CreateDocument -> "create"
                    is ActivityResultContracts.OpenDocument -> "open"
                    else -> return@mapNotNull null
                }
                Triple(key, original, kind)
            }
            check(documents.map { it.third }.sorted() == listOf("create", "open")) {
                "Expected exactly the backup screen's two real document contracts: ${documents.map { it.third }}"
            }
            for ((key, original, kind) in documents) {
                val contract = contractField.get(original)
                @Suppress("UNCHECKED_CAST") val callback = callbackField.get(original) as ActivityResultCallback<Any?>
                val observed = ActivityResultCallback<Any?> { result ->
                    check(result == null || result is Uri) { "Unexpected document contract result" }
                    results += SelectedResult(kind, result as Uri?)
                    callback.onActivityResult(result)
                }
                val replacement = constructor.newInstance(observed, contract)
                replacements[key] = original to replacement
                contractKeys[kind] = key
                entries[key] = replacement
            }
        }

        fun assertAttached(contract: String) {
            val key = requireNotNull(contractKeys[contract])
            check(entries[key] === replacements.getValue(key).second) {
                "The current real $contract registration changed before system document selection"
            }
        }

        fun close() {
            for ((key, pair) in replacements) if (entries[key] === pair.second) entries[key] = pair.first
            replacements.clear()
        }

        fun closeOnMainThread() { instrumentation.runOnMainSync { close() } }
    }
}
