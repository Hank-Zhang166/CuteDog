package com.cutedog.app.ui

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cutedog.app.pet.OverlayService
import com.cutedog.app.pet.PetSettings
import com.cutedog.app.pet.behavior.GrowthStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.MainActivity
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import com.cutedog.app.pet.PetView
import com.cutedog.app.pet.animation.FrameClipPlayer
import com.cutedog.app.reminder.ReminderScheduler
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class ReviewedPetDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun await(message: String, timeout: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        do {
            if (condition()) return
            SystemClock.sleep(16)
        } while (SystemClock.elapsedRealtime() < until)
        fail(message)
    }

    @Test fun finalCareAndBallClipsDecodeThroughTheInstalledPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val player = FrameClipPlayer(context)
        try {
            // Each tick is a playback sample. The device still performs the real asynchronous
            // AssetManager read, SHA verification and WebP decode before accepting that sample.
            for ((clip, count) in mapOf("scratch" to 83, "yawn" to 64, "groom" to 125, "play_ball" to 146)) {
                val seen = sortedSetOf<Int>()
                instrumentation.runOnMainSync { assertTrue(player.request(clip)) }
                await("Did not play all $count frames of $clip; collected indices $seen", 90_000) {
                    instrumentation.runOnMainSync {
                        val frame = player.tick(1.0 / 24)
                        assertNull(frame.error)
                        if (frame.clipId == clip && !frame.loading) {
                            val bitmap = requireNotNull(frame.bitmap)
                            assertEquals(512, bitmap.width)
                            assertEquals(512, bitmap.height)
                            seen += frame.frameIndex
                        }
                    }
                    seen.size == count
                }
                assertEquals((0 until count).toList(), seen.toList())
                Log.i("CuteDogReviewedPet", "$clip: ${seen.size} displayed frames")
            }
        } finally {
            instrumentation.runOnMainSync { player.close() }
        }
    }

    @Test fun floatingDogHandlesTapMenuAndDueCheckboxWithHomeInForeground() {
        val scheduler = ReminderScheduler(context)
        assumeTrue("Host must grant exact alarm access for a bounded delivery time", scheduler.canScheduleExact())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val flags = automation.serviceInfo.flags
        val pkg = context.packageName
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val originalOp = Regex("SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground)").find(shell("appops get $pkg SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
        val repo = ReminderRepository(context)
        val prefs = context.getSharedPreferences("onboarding", 0)
        val original = if (prefs.contains("completed")) prefs.getBoolean("completed", false) else null
        val settings = PetSettings(context)
        val locked = settings.locked
        val originalX = settings.xFraction
        val originalY = settings.yFraction
        val growth = GrowthStore(context)
        val growthPrefs = context.getSharedPreferences("pet_growth", 0)
        val originalGrowth = listOf("first_met", "last_interaction", "interactions").associateWith {
            if (growthPrefs.contains(it)) growthPrefs.getLong(it, 0) else null
        }
        val title = "Dog checkbox ${UUID.randomUUID()}"
        var id = 0L
        fun capture(name: String) {
            if (InstrumentationRegistry.getArguments().getString("cuteDogDisposable") != "true") return
            val directory = java.io.File(context.getExternalFilesDir(null), "overlay-gesture-evidence").apply { mkdirs() }
            val bitmap = automation.takeScreenshot() ?: return
            try {
                java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        fun node(ownPackage: Boolean = true, check: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            val queue = ArrayDeque(automation.windows.mapNotNull { it.root })
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if ((!ownPackage || current.packageName?.toString() == pkg) && current.isVisibleToUser && check(current)) return current
                for (index in 0 until current.childCount) current.getChild(index)?.let(queue::addLast)
            }
            return null
        }
        fun dog() = node { it.contentDescription?.contains("双击打开互动菜单") == true }
        fun label(value: String) = node { it.text?.toString() == value }
        fun sendTouch(kind: Int, down: Long, x: Float, y: Float, sync: Boolean = true) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, sync)) } finally { event.recycle() }
        }
        fun tap(x: Float, y: Float) {
            val down = SystemClock.uptimeMillis()
            sendTouch(MotionEvent.ACTION_DOWN, down, x, y)
            sendTouch(MotionEvent.ACTION_UP, down, x, y)
        }
        fun bounds() = Rect().also { requireNotNull(dog()).getBoundsInScreen(it) }
        fun dragVertically() {
            val box = bounds()
            val x = box.exactCenterX()
            val y = box.exactCenterY()
            val dy = if (y > context.resources.displayMetrics.heightPixels / 2f) -box.height() * .45f else box.height() * .45f
            val down = SystemClock.uptimeMillis()
            sendTouch(MotionEvent.ACTION_DOWN, down, x, y)
            SystemClock.sleep(settings.dragHoldMs + 100L)
            for (i in 1..6) sendTouch(MotionEvent.ACTION_MOVE, down, x, y + dy * i / 6f)
            sendTouch(MotionEvent.ACTION_UP, down, x, y + dy)
        }
        fun waitForClip(clip: String) {
            await("The installed floating player did not display $clip", 30_000) {
                var visible = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val rows = PetView.diagnosticSnapshots()
                    visible = (0 until rows.length()).map(rows::getJSONObject).any {
                        it.optString("scope") == "overlay" && it.optString("canonicalClipId") == clip && !it.optBoolean("loading")
                    }
                }
                visible
            }
        }
        fun touchDog(twice: Boolean) {
            val box = bounds()
            val x = box.exactCenterX()
            val y = box.exactCenterY()
            // Queue device events on a human time scale. Synchronous injection can wait for
            // slow window transitions between taps and change the gesture being tested.
            repeat(if (twice) 2 else 1) { index ->
                val down = SystemClock.uptimeMillis()
                sendTouch(MotionEvent.ACTION_DOWN, down, x, y, sync = false)
                SystemClock.sleep(24)
                sendTouch(MotionEvent.ACTION_UP, down, x, y, sync = false)
                if (twice && index == 0) SystemClock.sleep(72)
            }
        }
        fun choose(text: String) {
            val rect = Rect()
            requireNotNull(label(text)).getBoundsInScreen(rect)
            tap(rect.exactCenterX(), rect.exactCenterY())
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            prefs.edit().putBoolean("completed", true).commit()
            settings.locked = true
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity {
                    assertTrue(descendants(it.window.decorView).none { view -> view is PetView })
                    OverlayService.start(it)
                }
                await("Floating dog did not attach to WindowManager", 20_000) { dog() != null }
                SystemClock.sleep(2100)
                val initial = growth.snapshot().interactions
                touchDog(false)
                await("Single tap did not pet the dog", 5_000) { growth.snapshot().interactions == initial + 1 }
                assertNull(label("投球"))
                SystemClock.sleep(2100)
                touchDog(true)
                await("Double tap did not open the native menu", 10_000) { label("投球") != null }
                capture("menu-above-home")
                assertEquals("Opening the menu must not also pet or cuddle", initial + 1, growth.snapshot().interactions)
                val freshPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                if (freshPermission && InstrumentationRegistry.getArguments().getString("cuteDogDisposable") == "true") {
                    val permissionButton = Rect()
                    requireNotNull(label("按住说话")).getBoundsInScreen(permissionButton)
                    // Android takes input focus on DOWN when it opens its permission dialog.
                    // The system may cancel the matching UP sent to the former window.
                    shell("input tap ${permissionButton.centerX()} ${permissionButton.centerY()}")
                    fun allowMicrophone() = node(false) {
                        val key = it.viewIdResourceName.orEmpty().substringAfterLast('/')
                        val text = it.text?.toString().orEmpty()
                        val pkgName = it.packageName?.toString().orEmpty()
                        (pkgName.contains("permissioncontroller") || pkgName.contains("packageinstaller")) &&
                            (key in setOf("permission_allow_foreground_only_button", "permission_allow_button")
                                || text in setOf("While using the app", "使用应用时", "仅在使用中允许", "Allow", "允许"))
                    }
                    await("Android microphone permission was not requested from menu", 8000) { allowMicrophone() != null }
                    val permissionBounds = Rect()
                    requireNotNull(allowMicrophone()).getBoundsInScreen(permissionBounds)
                    tap(permissionBounds.exactCenterX(), permissionBounds.exactCenterY())
                    await("Menu did not return after permission grant", 8000) {
                        label("已允许使用麦克风。请再次按住说话。") != null && label("按住说话") != null
                    }
                    assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
                    capture("microphone-permission-granted")
                }
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                    InstrumentationRegistry.getArguments().getString("cuteDogDisposable") == "true") {
                    // The headless AVD supplies silent input. Verify capture, cancellation and submission
                    // without accessing a host microphone or transmitting private speech.
                    val microphone = Rect()
                    requireNotNull(label("按住说话")).getBoundsInScreen(microphone)
                    fun beginRecording(): Long {
                        val start = SystemClock.uptimeMillis()
                        sendTouch(MotionEvent.ACTION_DOWN, start, microphone.exactCenterX(), microphone.exactCenterY())
                        await("Visible menu did not start actual microphone capture", 5000) {
                            node { it.text?.toString()?.startsWith("在听啦") == true } != null
                        }
                        return start
                    }
                    val cancelDown = beginRecording()
                    val outsideY = microphone.bottom + 18f
                    sendTouch(MotionEvent.ACTION_MOVE, cancelDown, microphone.exactCenterX(), outsideY)
                    sendTouch(MotionEvent.ACTION_UP, cancelDown, microphone.exactCenterX(), outsideY)
                    await("Slide-out did not cancel recording", 5000) { label("已取消录音。") != null }
                    val submitDown = beginRecording()
                    SystemClock.sleep(650)
                    sendTouch(MotionEvent.ACTION_UP, submitDown, microphone.exactCenterX(), microphone.exactCenterY())
                    await("Release did not submit audio to the local recognizer", 8000) { label("停止播放") != null }
                    choose("停止播放")
                    await("Stop did not cancel the voice turn", 5000) { label("已停止语音。") != null }
                }
                choose("投球")
                await("Ball selection did not dispatch", 5_000) { growth.snapshot().interactions == initial + 2 }
                await("Menu still visible after selection", 5_000) { label("投球") == null }
                waitForClip("play_ball")
                val dogNode = requireNotNull(dog())
                val action = dogNode.actionList.first { it.label?.toString() == "打开互动菜单" }
                assertTrue(dogNode.performAction(action.id))
                await("Accessible menu did not appear", 5_000) { label("喂食") != null }
                val due = System.currentTimeMillis() + 1800
                id = repo.save(Reminder(title = title, dueAt = due))
                scheduler.schedule(requireNotNull(repo.get(id)))
                await("Due checkbox was not shown by floating dog above home", 25_000) {
                    node { it.isCheckable && it.text?.toString() == title } != null
                }
                assertNull("Reminder must supersede the menu", label("喂食"))
                capture("due-checkbox-above-home")
                choose(title)
                await("Checkbox did not persist completion", 8000) { repo.get(id)?.completed == true }
                await("Completed reminder remains visible", 8000) { label(title) == null }
                assertTrue(repo.deliveryHistory().any { it.reminderId == id && it.token == ReminderScheduler.normalToken(due) })
                shell("input keyevent KEYCODE_HOME")
                await("Launcher was not visible below the dog", 10_000) {
                    automation.windows.any { it.isActive && it.root?.packageName?.toString()?.contains("launcher", ignoreCase = true) == true }
                }
                automation.waitForIdle(250, 5000)
                val lockedBounds = bounds()
                dragVertically()
                assertEquals("Position lock must block dragging", lockedBounds, bounds())
                assertNull(label("投球"))
                touchDog(true)
                await("Double tap on launcher did not open menu", 8_000) { label("喂食") != null }
                capture("menu-above-launcher")
                choose("喂食")
                waitForClip("treat")
                touchDog(true)
                await("Menu not available for dismissal", 8000) { label("取消") != null }
                choose("取消")
                await("Cancel did not dismiss menu", 5000) { label("投球") == null }
                val petOutside = bounds()
                touchDog(true)
                await("Menu missing before touching the dog outside the card", 8000) { label("投球") != null }
                tap(petOutside.exactCenterX(), petOutside.exactCenterY())
                await("The floating dog intercepted the menu's outside tap", 5000) { label("投球") == null }
                settings.locked = false
                SystemClock.sleep(400)
                val unlockedTop = bounds().top
                dragVertically()
                await("Long press and movement did not move the unlocked dog", 4000) {
                    kotlin.math.abs(bounds().top - unlockedTop) >= 24
                }
                assertNull("Dragging opened the interaction menu", label("投球"))
                settings.locked = true
                SystemClock.sleep(400)
                touchDog(true)
                await("Chat entry is missing on desktop", 8000) { label("聊聊天") != null }
                choose("聊聊天")
                await("Desktop chat shortcut did not open its chat window", 10_000) {
                    label("收起聊天") != null && shell("dumpsys activity activities").lineSequence().any {
                        (it.contains("mResumedActivity") || it.contains("topResumedActivity")) && it.contains("PetChatActivity")
                    }
                }
                capture("desktop-chat")
                choose("收起聊天")
                await("Closing desktop chat did not return to Launcher", 8000) {
                    label("收起聊天") == null && automation.windows.any { it.root?.packageName?.toString()?.contains("launcher", ignoreCase = true) == true }
                }
                fun phoneIcon() = node(false) {
                    it.packageName?.toString()?.contains("launcher", ignoreCase = true) == true &&
                        listOf(it.text?.toString(), it.contentDescription?.toString()).any { name -> name in setOf("Phone", "电话", "拨号") }
                }
                await("Launcher phone icon absent on this emulator", 8000) { phoneIcon() != null }
                val phone = Rect()
                requireNotNull(phoneIcon()).getBoundsInScreen(phone)
                val preMenuDog = bounds()
                touchDog(true)
                await("Menu absent before testing outside touch", 8000) { label("投球") != null }
                val menuPanel = Rect()
                requireNotNull(node { it.className == "android.widget.ScrollView" }).getBoundsInScreen(menuPanel)
                assertFalse("Launcher icon must be outside the menu", menuPanel.contains(phone.centerX(), phone.centerY()))
                assertFalse("Launcher icon must be outside the dog", preMenuDog.contains(phone.centerX(), phone.centerY()))
                tap(phone.exactCenterX(), phone.exactCenterY())
                await("Tapping outside did not dismiss the menu and restore Launcher", 7000) {
                    label("投球") == null && shell("dumpsys activity activities").lineSequence().any {
                        (it.contains("mResumedActivity") || it.contains("topResumedActivity")) && it.contains("launcher", ignoreCase = true)
                    }
                }
                tap(phone.exactCenterX(), phone.exactCenterY())
                await("Dismissed menu still blocked the launcher icon", 10_000) {
                    shell("dumpsys activity activities").lineSequence().any {
                        (it.contains("mResumedActivity") || it.contains("topResumedActivity")) &&
                            (it.contains("dialer", ignoreCase = true) || it.contains("Dialtacts"))
                    }
                }
                capture("outside-touch-opens-dialer")
                shell("input keyevent KEYCODE_HOME")


            }
        } finally {
            OverlayService.stop(context)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (id != 0L) { repo.delete(id); scheduler.cancel(id) }
            settings.locked = locked
            settings.xFraction = originalX
            settings.yFraction = originalY
            val growthEdit = growthPrefs.edit()
            originalGrowth.forEach { (key, value) -> if (value == null) growthEdit.remove(key) else growthEdit.putLong(key, value) }
            growthEdit.commit()
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            shell("appops set $pkg SYSTEM_ALERT_WINDOW $originalOp")
            val edit = prefs.edit()
            if (original == null) edit.remove("completed") else edit.putBoolean("completed", original)
            edit.commit()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
