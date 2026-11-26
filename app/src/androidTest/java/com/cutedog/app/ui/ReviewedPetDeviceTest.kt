package com.cutedog.app.ui

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import com.cutedog.app.pet.OverlayService
import com.cutedog.app.pet.PetSettings
import com.cutedog.app.pet.behavior.GrowthStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.BuildConfig
import com.cutedog.app.MainActivity
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import com.cutedog.app.pet.PetView
import com.cutedog.app.pet.PetPart
import com.cutedog.app.pet.PetTouchRegions
import com.cutedog.app.R
import com.cutedog.app.pet.animation.FrameClipPlayer
import com.cutedog.app.reminder.ReminderScheduler
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class ReviewedPetDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private companion object {
        const val MENU_WINDOW = "CuteDog interaction panel"
        val windowFrame = Regex("""\b(?:mFrame|frame)=\[(-?\d+),\s*(-?\d+)\]\[(-?\d+),\s*(-?\d+)\]""")
        val overlayType = Regex("""\b(?:ty|type)=(?:0x0*7f6|2038|APPLICATION_OVERLAY)\b""", RegexOption.IGNORE_CASE)
    }

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

    private enum class InteractionScene { HOME, BODY_PARTS, DOUBLE_TAP_EDGE, DESKTOP_GESTURES, DESKTOP_CHAT, DESKTOP_DISMISSAL }

    @Test fun floatingDogHandlesTapMenuAndDueCheckboxWithHomeInForeground() = checkInteraction(InteractionScene.HOME)
    @Test fun floatingDogRespondsToHeadPawAndTail() = checkInteraction(InteractionScene.BODY_PARTS)
    @Test fun aSecondTapJustOutsideThePaintedFurStillOpensTheMenu() = checkInteraction(InteractionScene.DOUBLE_TAP_EDGE)
    @Test fun desktopMenuFeedsTheDogAndHonorsTheDragLock() = checkInteraction(InteractionScene.DESKTOP_GESTURES)
    @Test fun desktopMenuOpensAndClosesTheChatWindow() = checkInteraction(InteractionScene.DESKTOP_CHAT)
    @Test fun desktopMenuClosesOnTheDogAndOnTheLauncher() = checkInteraction(InteractionScene.DESKTOP_DISMISSAL)

    private fun checkInteraction(scene: InteractionScene) {
        val scheduler = ReminderScheduler(context)
        if (scene == InteractionScene.HOME) assumeTrue("Host must grant exact alarm access for a bounded delivery time", scheduler.canScheduleExact())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val flags = automation.serviceInfo.flags
        val pkg = context.packageName
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        fun windowStates(): List<String> = shell("dumpsys window windows").split(Regex("(?m)^  Window #\\d+"))
        fun namedMenuWindow(): String? = windowStates().firstOrNull {
            it.lineSequence().firstOrNull()?.contains(MENU_WINDOW) == true
        }
        fun frameOf(state: String): Rect? = windowFrame.find(state)?.destructured?.let { (l, t, r, b) ->
            Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt()).takeUnless { it.isEmpty }
        }
        fun foregroundActivity(): String? = shell("dumpsys activity activities").lineSequence()
            .firstOrNull {
                it.contains("ActivityRecord{") && (it.contains("mResumedActivity") || it.contains("topResumedActivity"))
            }
            ?.substringAfter("ActivityRecord{")
            ?.substringBefore('}')
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
        var lastPress = ""
        fun capture(name: String) {
            val args = InstrumentationRegistry.getArguments()
            if (args.getString("cuteDogDisposable") != "true") return
            val phase = args.getString("cuteDogEvidencePhase")
            val folder = if (name.startsWith("failure-") && phase in setOf("granted","denied")) {
                "ui-test-evidence/$phase/overlay"
            } else "overlay-gesture-evidence"
            val directory = java.io.File(context.getExternalFilesDir(null), folder).apply { mkdirs() }
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
            if (kind == MotionEvent.ACTION_DOWN) lastPress = "$x,$y"
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, sync)) } finally { event.recycle() }
        }
        fun tap(x: Float, y: Float) {
            lastPress = "$x,$y"
            // A button can dismiss or replace its own window. Send a real system tap;
            // the caller verifies the resulting UI, database, activity or rendered clip.
            shell("input tap ${x.toInt()} ${y.toInt()}")
        }
        fun bounds(): Rect {
            val rect = Rect()
            await("Floating dog did not expose its on-screen bounds after the window transition", 8000) {
                dog()?.getBoundsInScreen(rect)
                if (!rect.isEmpty) true else {
                    // Launcher and a non-focusable overlay may publish their accessibility
                    // windows at different times. Ask WindowManager for the actual surface.
                    val petWindow = windowStates().firstOrNull {
                        it.contains(pkg) && !it.contains(MENU_WINDOW) && overlayType.containsMatchIn(it)
                    }
                    petWindow?.let(::frameOf)?.let(rect::set)
                    !rect.isEmpty
                }
            }
            return rect
        }
        fun snapshot(): org.json.JSONObject? {
            var row: org.json.JSONObject? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val views = PetView.diagnosticSnapshots()
                row = (0 until views.length()).map(views::getJSONObject).firstOrNull { it.optString("scope") == "overlay" }
            }
            return row
        }
        fun waitForGrowthDebounce() = await("Previous growth event is still within its two-second debounce", 3500) {
            System.currentTimeMillis() - growthPrefs.getLong("last_interaction", 0) >= 2050
        }
        val touchRegions by lazy { PetTouchRegions(context.resources.openRawResource(R.raw.pet_touch_regions)) }
        fun inputWindowFrame(): Rect? {
            // InputDispatcher exposes the surface origin in the coordinate system
            // UiAutomation injects into, including status bar offsets on older ROMs.
            val dump = shell("dumpsys input")
            for (line in dump.lineSequence()) {
                if (!line.contains(pkg) || line.contains(MENU_WINDOW) || !overlayType.containsMatchIn(line) || line.contains("visible=false") ||
                    line.contains("NOT_TOUCHABLE") || line.contains("NOT_VISIBLE") || line.contains("touchableRegion=<empty>")) continue
                frameOf(line)?.let { return it }
            }
            // Some Android builds group InputDispatcher's metadata over multiple lines.
            // WindowManager exposes the same display-pixel frame for a drawn, touchable surface.
            val surface = windowStates()
                .firstOrNull { window ->
                    window.lineSequence().firstOrNull()?.contains(pkg) == true &&
                        !window.contains(MENU_WINDOW) && overlayType.containsMatchIn(window) && window.contains("Surface: shown=true") &&
                        window.contains("isVisible=true") && !window.contains("NOT_TOUCHABLE")
                }
            return surface?.let(::frameOf)
        }
        fun screenPoint(current: org.json.JSONObject, u: Float, v: Float): Pair<Float,Float> {
            val side = current.getDouble("canvasSide").toFloat()
            val frame = inputWindowFrame() ?: bounds()
            return (frame.left + current.getDouble("canvasLocalLeft").toFloat() + u * side) to
                (frame.top + current.getDouble("canvasLocalTop").toFloat() + v * side)
        }
        // The centre of the overlay window can be clear sky when the dog lies down. For
        // menu/drag smoke tests press interior coat. The region-specific test uses hand-picked
        // artwork coordinates independently of this table and checks the actually played clip.
        fun bodyPoint(preferCenter: Boolean = false): Pair<Float,Float> {
            var point: Pair<Float,Float>? = null
            await("No visible painted body target in the floating window", 8000) {
                val current = snapshot() ?: return@await false
                val frame = current.optInt("displayedFrameIndex", -1)
                val clip = current.optString("displayedClip")
                if (current.optDouble("canvasSide",0.0) <= 0) return@await false
                // The centre of a standing dog is the stable double-tap and drag target.
                if (preferCenter && touchRegions.at(clip,frame,.5f,.5f) != null) {
                    point = screenPoint(current,.5f,.5f)
                    return@await true
                }
                val cell = (5..20).flatMap { y -> (4..20).map { x -> x to y } }.filter { (x,y) ->
                    listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1).all { (dx,dy) ->
                        touchRegions.at(clip,frame,(x+dx)/24f,(y+dy)/24f) == PetPart.BODY
                    }
                }.minByOrNull { (x,y) -> (x-12)*(x-12) + (y-12)*(y-12) } ?: return@await false
                point = screenPoint(current,cell.first/24f,cell.second/24f)
                true
            }
            return requireNotNull(point)
        }
        fun dragVertically() {
            val box = bounds()
            val (x,y) = bodyPoint(preferCenter = true)
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
        fun awaitDogInput() {
            var usableSince = 0L
            var previousFrame: Rect? = null
            await("Dog window did not receive touch ownership after the menu closed", 9000) {
                val frame = inputWindowFrame()
                val now = SystemClock.uptimeMillis()
                if (frame == null) usableSince = 0L
                else if (frame != previousFrame || usableSince == 0L) usableSince = now
                previousFrame = frame
                usableSince != 0L && now - usableSince >= 700
            }
        }
        fun touchDog(twice: Boolean) {
            awaitDogInput()
            // Dispatch each physical event to Android. UiAutomation's shell command API
            // does not provide an interactive shell for a compound "tap; sleep; tap".
            val (x,y) = bodyPoint(preferCenter = twice)
            repeat(if (twice) 2 else 1) { step ->
                val down = SystemClock.uptimeMillis()
                sendTouch(MotionEvent.ACTION_DOWN, down, x, y, sync = false)
                SystemClock.sleep(28)
                sendTouch(MotionEvent.ACTION_UP, down, x, y, sync = false)
                if (twice && step == 0) SystemClock.sleep(68)
            }
        }
        fun menuInputFrame(): Rect? {
            val input = shell("dumpsys input").lineSequence()
                .firstNotNullOfOrNull {
                    if (it.contains(MENU_WINDOW) && overlayType.containsMatchIn(it) &&
                        !it.contains("visible=false") && !it.contains("NOT_VISIBLE") && !it.contains("NOT_TOUCHABLE")) frameOf(it)
                    else null
                }
            if (input != null) return input
            return namedMenuWindow()?.takeIf { overlayType.containsMatchIn(it) && it.contains("Surface: shown=true") }
                ?.let(::frameOf)
        }
        fun menuBounds(text: String? = null): Rect {
            val rect = Rect()
            await("Visible overlay menu control was not laid out: ${text ?: "panel"}", 8000) {
                val frame = menuInputFrame() ?: return@await false
                if (text == null) {
                    rect.set(frame)
                } else {
                    val button = label(text) ?: return@await false
                    button.getBoundsInScreen(rect)
                    // Align the accessibility root with the actual input frame. In particular,
                    // API 26 can report an extra status bar offset in View screen coordinates.
                    button.window?.root?.let { root ->
                        val reported = Rect().also(root::getBoundsInScreen)
                        if (reported.width() == frame.width() && reported.height() == frame.height()) {
                            rect.offset(frame.left - reported.left, frame.top - reported.top)
                        }
                    }
                    if (!rect.intersect(frame)) return@await false
                }
                !rect.isEmpty
            }
            return rect
        }
        fun awaitMenuInput() {
            menuBounds("投球")
        }
        fun choose(text: String, inPetMenu: Boolean = true) {
            val rect = if (inPetMenu) menuBounds(text) else Rect().also { bounds ->
                await("Visible control was not available: $text", 6000) {
                    label(text)?.getBoundsInScreen(bounds)
                    !bounds.isEmpty
                }
            }
            tap(rect.exactCenterX(), rect.exactCenterY())
        }
        fun tapOutsideMenu() {
            val menu = menuBounds()
            val dog = bounds()
            val display = context.resources.displayMetrics
            val point = listOf(.52f, .64f, .38f, .22f, .82f).flatMap { y ->
                listOf(.1f, .5f, .9f).map { x -> x * display.widthPixels to y * display.heightPixels }
            }.firstOrNull { (x, y) ->
                val px = x.toInt()
                val py = y.toInt()
                !menu.contains(px, py) && !dog.contains(px, py) && node(ownPackage = false) { control ->
                    if (!control.isClickable) return@node false
                    val area = Rect().also(control::getBoundsInScreen)
                    area.width().toLong() * area.height() < display.widthPixels.toLong() * display.heightPixels / 2 &&
                        area.contains(px, py)
                } == null
            }
            requireNotNull(point) { "No unobstructed launcher point outside $menu and $dog" }
            tap(point.first, point.second)
        }
        fun assertBoundedOverlay(foreground: String): Rect {
            val panel = menuBounds()
            val dogPanel = bounds()
            val display = context.resources.displayMetrics
            val state = requireNotNull(namedMenuWindow()) { "Missing named interaction overlay" }
            assertTrue("The menu must have TYPE_APPLICATION_OVERLAY", overlayType.containsMatchIn(state))
            assertEquals("Opening the menu changed the resumed activity", foreground, foregroundActivity())
            assertTrue("Menu $panel occupied a full screen", panel.height() < display.heightPixels * .70f)
            assertFalse("Menu $panel covers the dog $dogPanel", Rect.intersects(panel, dogPanel))
            return panel
        }
        fun screenshotForPixelComparison(): Bitmap {
            val screenshot = requireNotNull(automation.takeScreenshot())
            if (screenshot.config != Bitmap.Config.HARDWARE) return screenshot
            return try { requireNotNull(screenshot.copy(Bitmap.Config.ARGB_8888, false)) }
            finally { screenshot.recycle() }
        }
        fun assertLauncherPixelsUnchanged(before: Bitmap, menu: Rect, dogBefore: Rect) {
            val after = screenshotForPixelComparison()
            try {
                assertEquals(before.width, after.width)
                assertEquals(before.height, after.height)
                val dog = Rect(dogBefore).apply { union(bounds()); inset(-24, -24) }
                val panel = Rect(menu).apply { inset(-16, -16) }
                var samples = 0
                var changed = 0
                for (yg in 2..17) for (xg in 1..19) {
                    val x = after.width * xg / 20
                    val y = after.height * yg / 20
                    if (dog.contains(x, y) || panel.contains(x, y)) continue
                    val earlier = before.getPixel(x, y)
                    val later = after.getPixel(x, y)
                    val delta = maxOf(
                        kotlin.math.abs(Color.red(earlier) - Color.red(later)),
                        kotlin.math.abs(Color.green(earlier) - Color.green(later)),
                        kotlin.math.abs(Color.blue(earlier) - Color.blue(later)),
                    )
                    samples++
                    if (delta > 24) changed++
                }
                assertTrue("Too little of Launcher remained outside the panel: $samples samples", samples >= 80)
                assertTrue("Background outside the panel changed at $changed of $samples points", changed <= samples / 10)
            } finally { after.recycle() }
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
                if (scene == InteractionScene.DOUBLE_TAP_EDGE) {
                    awaitDogInput()
                    await("Standing artwork has not been drawn", 20_000) {
                        snapshot()?.let { it.optString("displayedClip") == "stand" && it.optDouble("canvasSide") > 0 && !it.optBoolean("loading") } == true
                    }
                    val current = requireNotNull(snapshot())
                    val initial = growth.snapshot().interactions
                    // Both coordinates remain on the same side of the painted outline in all 96 frames.
                    val cheek = screenPoint(current,110/512f,145/512f)
                    val outside = screenPoint(current,100/512f,145/512f)
                    assertTrue("The second press must be within the real device touch slop",
                        kotlin.math.hypot(cheek.first-outside.first,cheek.second-outside.second) <= ViewConfiguration.get(context).scaledTouchSlop)
                    for ((index,point) in listOf(cheek,outside).withIndex()) {
                        val (x,y) = point
                        val down = SystemClock.uptimeMillis()
                        sendTouch(MotionEvent.ACTION_DOWN,down,x,y,false)
                        SystemClock.sleep(28)
                        sendTouch(MotionEvent.ACTION_UP,down,x,y,false)
                        if (index == 0) SystemClock.sleep(68)
                    }
                    await("Both system presses must reach the floating window; cheek=$cheek, outside=$outside",2500) {
                        (snapshot()?.optInt("touchDownCount") ?: 0) == current.getInt("touchDownCount") + 2
                    }
                    val presses = requireNotNull(snapshot())
                    assertEquals("First press must land on the drawn cheek", "HEAD", presses.optString("previousDownPart"))
                    assertTrue("Second press must land outside the drawn fur", presses.isNull("lastDownPart"))
                    assertTrue("The second press must use the edge tolerance", presses.getBoolean("lastDownUsedEdge"))
                    tapOutsideMenu()
                    await("Outside tap did not close the menu", 5_000) { namedMenuWindow() == null }
                    assertEquals("Opening a menu must not also pet the dog",initial,growth.snapshot().interactions)
                    return@use
                }
                if (scene == InteractionScene.BODY_PARTS) {
                    val initial = growth.snapshot().interactions
                    val cases = listOf(Triple("HEAD", .27f to .31f, "pet_head"),
                        Triple("PAW", .37f to .77f, "wave"), Triple("TAIL", .68f to .30f, "tail_wag"))
                    for ((index, testCase) in cases.withIndex()) {
                        waitForGrowthDebounce()
                        if (index != 0) {
                            OverlayService.stop(context)
                            await("Previous floating view did not close", 8000) { snapshot() == null }
                            activity.onActivity { OverlayService.start(it) }
                        }
                        awaitDogInput()
                        fun tapCanvas(u: Float, v: Float) {
                            var target: Pair<Float,Float>? = null
                            await("Standing artwork has not been drawn", 20_000) {
                                val drawn = snapshot() ?: return@await false
                                if (drawn.optString("displayedClip") != "stand" || drawn.optBoolean("loading") ||
                                    drawn.optInt("displayedFrameIndex", -1) < 0 || drawn.optDouble("canvasSide", 0.0) <= 0) {
                                    return@await false
                                }
                                target = screenPoint(drawn, u, v)
                                true
                            }
                            val (x,y) = requireNotNull(target)
                            tap(x,y)
                        }
                        if (index == 0) {
                            tapCanvas(.05f, .05f)
                            SystemClock.sleep(550)
                            assertEquals("Clear padding must not trigger a pet reaction", initial, growth.snapshot().interactions)
                        }
                        val (part, point, motion) = testCase
                        tapCanvas(point.first, point.second)
                        await("System tap did not hit $part", 5000) { snapshot()?.optString("lastTouchPart") == part }
                        waitForClip(motion)
                        assertEquals("Each single press must count once", initial + index + 1, growth.snapshot().interactions)
                        assertNull("Petting opened a menu", label("投球"))
                        capture("touch-${part.lowercase()}-$motion")
                    }
                    return@use
                }
                SystemClock.sleep(2100)
                if (scene == InteractionScene.HOME) {
                    val initial = growth.snapshot().interactions
                    waitForGrowthDebounce()
                    touchDog(false)
                    await("Single tap did not pet the dog", 5_000) { growth.snapshot().interactions == initial + 1 }
                    assertNull(label("投球"))
                    SystemClock.sleep(2100)
                    val underlying = requireNotNull(foregroundActivity())
                    touchDog(true)
                    await("Double tap did not open the native menu", 10_000) { label("投球") != null }
                    assertBoundedOverlay(underlying)
                    capture("menu-above-home")
                    assertEquals("Opening the menu must not also pet or cuddle", initial + 1, growth.snapshot().interactions)
                    val freshPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                    if (BuildConfig.LEGACY_SPEECH && freshPermission &&
                        InstrumentationRegistry.getArguments().getString("cuteDogDisposable") == "true") {
                        val permissionButton = menuBounds("按住说话")
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
                            label("请重新按住麦克风说话。") != null && label("按住说话") != null
                        }
                        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
                        awaitMenuInput()
                        capture("microphone-permission-granted")
                    }
                    if (BuildConfig.LEGACY_SPEECH && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                        InstrumentationRegistry.getArguments().getString("cuteDogDisposable") == "true") {
                        // The headless AVD supplies silent input. Verify capture, cancellation and submission
                        // without accessing a host microphone or transmitting private speech.
                        val microphone = menuBounds("按住说话")
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
                    waitForClip("play_ball")
                    assertNotNull("The menu should stay visible after selection", label("投球"))
                    tapOutsideMenu()
                    await("Outside tap did not close the menu", 5_000) { namedMenuWindow() == null }
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
                    assertNull("Reminder must supersede the menu", namedMenuWindow())
                    capture("due-checkbox-above-home")
                    choose(title, inPetMenu = false)
                    await("Checkbox did not persist completion", 8000) { repo.get(id)?.completed == true }
                    await("Completed reminder remains visible", 8000) { label(title) == null }
                    assertTrue(repo.deliveryHistory().any { it.reminderId == id && it.token == ReminderScheduler.normalToken(due) })
                    return@use
                }
                shell("input keyevent KEYCODE_HOME")
                await("Launcher was not visible below the dog", 10_000) {
                    automation.windows.any { it.isActive && it.root?.packageName?.toString()?.contains("launcher", ignoreCase = true) == true }
                }
                automation.waitForIdle(250, 5000)
                val launcher = requireNotNull(foregroundActivity())
                assertTrue("Expected Launcher beneath the overlay: $launcher", launcher.contains("launcher", ignoreCase = true))
                if (scene == InteractionScene.DESKTOP_GESTURES) {
                    val lockedBounds = bounds()
                    dragVertically()
                    assertEquals("Position lock must block dragging", lockedBounds, bounds())
                    assertNull(label("投球"))
                    touchDog(true)
                    await("Double tap on launcher did not open menu", 8_000) { label("喂食") != null }
                    assertBoundedOverlay(launcher)
                    capture("menu-above-launcher")
                    choose("喂食")
                    waitForClip("treat")
                    assertNotNull("The menu should stay open after feeding", label("投球"))
                    choose("投球")
                    waitForClip("play_ball")
                    assertNotNull("The menu should accept a second action without reopening", label("喂食"))
                    tapOutsideMenu()
                    await("Outside tap did not dismiss menu", 5000) { namedMenuWindow() == null }
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
                    return@use
                }
                if (scene == InteractionScene.DESKTOP_CHAT) {
                    touchDog(true)
                    choose("聊聊天")
                    await("Desktop chat shortcut did not open its chat window", 10_000) {
                        label("收起聊天") != null && shell("dumpsys activity activities").lineSequence().any {
                            (it.contains("mResumedActivity") || it.contains("topResumedActivity")) && it.contains("PetChatActivity")
                        }
                    }
                    capture("desktop-chat")
                    choose("收起聊天", inPetMenu = false)
                    awaitMenuInput()
                    assertEquals("Closing chat should reveal Launcher behind the menu", launcher, foregroundActivity())
                    assertFalse("Closing desktop chat must restore the open interaction menu", menuBounds("投球").isEmpty)
                    tapOutsideMenu()
                    await("Outside tap did not dismiss the menu after chatting", 5_000) { namedMenuWindow() == null }
                    return@use
                }
                val petOutside = bounds()
                touchDog(true)
                await("Menu missing before touching the dog outside the card", 8000) { label("投球") != null }
                awaitMenuInput()
                tap(petOutside.exactCenterX(), petOutside.exactCenterY())
                await("The floating dog intercepted the menu's outside tap", 5000) { namedMenuWindow() == null }
                val baseline = screenshotForPixelComparison()
                try {
                    val dogBefore = bounds()
                    touchDog(true)
                    awaitMenuInput()
                    val panel = assertBoundedOverlay(launcher)
                    assertLauncherPixelsUnchanged(baseline, panel, dogBefore)
                    capture("menu-does-not-cover-launcher")
                } finally { baseline.recycle() }
                tapOutsideMenu()
                await("Tapping outside did not dismiss the menu and restore Launcher", 7000) {
                    namedMenuWindow() == null && foregroundActivity() == launcher
                }
                capture("outside-touch-restores-launcher")
            }
        } catch (failure: AssertionError) {
            capture("failure-${scene.name.lowercase()}")
            val focused = shell("dumpsys window windows").lineSequence().filter { "mCurrentFocus=" in it }.firstOrNull()
            throw AssertionError("${failure.message}; last system press $lastPress, $focused, pet ${snapshot()}", failure)
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
