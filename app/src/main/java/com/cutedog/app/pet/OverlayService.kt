package com.cutedog.app.pet

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.*
import android.provider.Settings
import android.view.*
import com.cutedog.app.MainActivity
import com.cutedog.app.PetChatActivity
import com.cutedog.app.pet.animation.AuthoredStepApproval
import com.cutedog.app.pet.animation.OverlayTravelPolicy
import com.cutedog.app.reminder.DogReminder
import com.cutedog.app.reminder.completeFromDog
import com.cutedog.app.reminder.dogReminders
import com.cutedog.app.reminder.clock
import com.cutedog.app.voice.doubao.DoubaoSettings
import kotlinx.coroutines.*
import kotlin.math.*

/** Owns the small pet, reminder and interaction windows drawn above other apps. */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var pet: PetView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: PetSettings
    private val main = Handler(Looper.getMainLooper())
    private val bubbleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var attached = false
    private var hidden = false
    private var menu: PetMenuView? = null
    private var menuOutsideTouch: View? = null
    private var microphoneReady = false
    private val menuOpen get() = menu != null
    private var menuMovementUntil = 0L
    private val menuAllowsTravel get() = !menuOpen || SystemClock.uptimeMillis() < menuMovementUntil
    private var bubble: PetBubbleView? = null
    private var bubbleTimeout: Runnable? = null
    private var bubbleWindow: WindowManager.LayoutParams? = null
    private var bubbleTailEdge = PetBubbleView.TailEdge.BOTTOM
    private val stepApproval = AuthoredStepApproval()
    private var snapTask: Runnable? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var legacyInsets: List<Int>? = null
    private var floatingX = 0f
    private var seenPosition = .5f
    private var dragging = false
    private var spaceArea = PetSpace.Box(0f, 0f, 1f, 1f)
    private var obstacles = emptyList<PetSpace.Box>()
    private var route = mutableListOf<PetSpace.Point>()
    private var position = PetSpace.Point(0f, 0f)
    private var spaceAvailable = true
    private var lastMotion = 0L
    private val overlayPermissionListener = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == this.packageName)
            main.post { if (!Settings.canDrawOverlays(this)) stopSelf() }
    }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "x" && key != "y") main.post { configure(); if (attached) safelyUpdate() }
    }
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> reconcileVisibleState()
                PetEvents.MODEL_ACTION -> if (!hidden && spaceAvailable && getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                    PetEvents.action(intent)?.let(pet::react)
                }
            }
        }
    }
    private val motion = object : Runnable {
        override fun run() {
            if (attached && !hidden && menuAllowsTravel && (bubble == null || pet.usesAuthoredFrames) && snapTask == null && pet.isShown && !dragging && !prefs.locked && !prefs.reducedMotion) {
                val now = SystemClock.uptimeMillis()
                val dt = if (lastMotion == 0L) .05f else ((now - lastMotion) / 1000f).coerceAtMost(.1f)
                lastMotion = now
                if (pet.usesAuthoredFrames) moveAuthoredFrames() else moveLegacyPreview(dt)
            } else {
                stepApproval.clear()
                pet.setAuthoredTravelAllowed(false)
                lastMotion = 0
            }
            seenPosition = pet.behavior.position
            if (attached && !hidden && pet.isShown && getSystemService(PowerManager::class.java).isInteractive)
                main.postDelayed(this, if (prefs.locked || prefs.reducedMotion) 1000
                    else if ((pet.usesAuthoredFrames && (pet.authoredTravelRequested || pet.authoredTravelInProgress)) || pet.locomotionSpeed > .001f) 50 else 250)
        }
    }

    /** The current authored contract measures horizontal motion only, never a guessed 2-D speed. */
    private fun moveAuthoredFrames() {
        if (!spaceAvailable) {
            route.clear(); pet.setAuthoredTravelAllowed(false); return
        }
        if (pet.authoredGestureTravel && bubble == null) {
            // One-shot gestures have a signed, measured path. Entry and step approvals
            // below still check its full stopping envelope and actual window obstacles.
            route.clear()
            pet.setAuthoredTravelAllowed(true)
            return
        }
        if (bubble != null) {
            route.clear()
            // Keep a reminder's wave/interaction route. Only a roaming target needs a stop request.
            if (OverlayTravelPolicy.shouldStop(true, pet.authoredTravelRequested)) pet.stopAuthoredTravel()
            pet.setAuthoredTravelAllowed(pet.authoredTravelInProgress)
            return
        }
        if (!pet.authoredTravelRequested) {
            // An interaction/stop request must not erase the old heading's measured deceleration.
            pet.setAuthoredTravelAllowed(pet.authoredTravelInProgress)
            if (!pet.authoredTravelInProgress) route.clear()
            return
        }
        if (route.isEmpty()) {
            // Independent horizontal candidates preserve the measured projected foot displacement.
            // A future authored projectedTravelVector contract is required for sloped/vertical paths.
            repeat(16) {
                if (route.isEmpty()) {
                    val candidate = PetSpace.Point(kotlin.random.Random.nextFloat() * (spaceArea.right - spaceArea.left) + spaceArea.left, position.y)
                    val continuingHeading = if (pet.authoredTravelInProgress) pet.authoredActualHeading else 0
                    val candidateHeading = if (candidate.x > position.x) 1 else -1
                    if (pet.canPlanAuthoredHeading(candidateHeading) &&
                        (continuingHeading == 0 || (candidate.x - position.x) * continuingHeading > 0f) &&
                        abs(candidate.x - position.x) > params.width * .6f && PetSpace.valid(candidate, spaceArea, obstacles) &&
                        PetSpace.clearSegment(position, candidate, obstacles)) route.add(candidate)
                }
            }
        }
        val target = route.firstOrNull()
        if (target == null || abs(target.y - position.y) > .01f) {
            route.clear(); pet.stopAuthoredTravel(); pet.setAuthoredTravelAllowed(pet.authoredTravelInProgress); return
        }
        val remaining = target.x - position.x
        if (abs(remaining) < .5f) {
            route.clear(); pet.stopAuthoredTravel(); pet.setAuthoredTravelAllowed(pet.authoredTravelInProgress); return
        }
        val heading = if (remaining > 0) 1 else -1
        val actualHeading = pet.authoredActualHeading
        val stopping = pet.authoredStoppingDistancePixels
        if (pet.authoredTravelInProgress && stopping != null &&
            ((actualHeading == heading && abs(remaining) <= stopping + pet.authoredPlanningMarginPixels) ||
                (pet.authoredDesiredHeading == actualHeading && remaining * actualHeading < 0f))) {
            pet.stopAuthoredTravel()
            pet.setAuthoredTravelAllowed(true)
            return
        }
        if (!pet.requestAuthoredHeading(heading)) {
            route.clear()
            if (pet.authoredTravelRequested) pet.stopAuthoredTravel()
            pet.setAuthoredTravelAllowed(pet.authoredTravelInProgress)
            return
        }
        pet.setAuthoredTravelAllowed(true)
    }

    /** Checks common window/permission state. A bubble itself does not invalidate a committed step. */
    private fun authoredMovementHostReady(): Boolean = attached && !hidden && snapTask == null && pet.isShown &&
        !dragging && menuAllowsTravel && !prefs.locked && !prefs.reducedMotion && spaceAvailable

    private fun authoredSpaceClear(pixels: Float): Boolean {
        if (!pixels.isFinite()) return false
        val next = PetSpace.Point(position.x + pixels, position.y)
        return PetSpace.valid(next, spaceArea, obstacles) && PetSpace.clearSegment(position, next, obstacles)
    }

    /** Whole-route geometry check: deliberately cannot issue a real-step approval. */
    private fun allowAuthoredEntry(pixels: Float): Boolean =
        OverlayTravelPolicy.mayReserve(authoredMovementHostReady(), bubble != null,
            pet.authoredTravelInProgress, pet.authoredTravelRequested) && authoredSpaceClear(pixels)

    private fun allowAuthoredStep(pixels: Float, sequence: Long): Boolean = stepApproval.approve(sequence, pixels,
        SystemClock.uptimeMillis(), OverlayTravelPolicy.mayAdvance(authoredMovementHostReady(), bubble != null,
            pet.authoredTravelInProgress) && authoredSpaceClear(pixels))

    private fun consumeAuthoredMovement(heading: Int, sequence: Long) {
        // The stop's last positive interval may already have committed stand. Use the approval
        // from that exact frame, not the new clip's travelInProgress value, to consume it once.
        val submittedPixels = pet.consumeAuthoredTravelPixels(heading)
        val allowed = stepApproval.consume(sequence, submittedPixels, SystemClock.uptimeMillis(),
            authoredMovementHostReady(), authoredSpaceClear(submittedPixels))
        if (!allowed || submittedPixels * heading <= 0f) {
            route.clear(); pet.setAuthoredTravelAllowed(false); return
        }
        position = PetSpace.Point(position.x + submittedPixels, position.y)
        params.x = (position.x - params.width / 2f).toInt()
        pet.movementDirection = heading.toFloat()
        safelyUpdate()
        updateBubbleTail()
    }

    private fun updateBubbleTail() {
        val view = bubble ?: return
        val window = bubbleWindow ?: return
        val horizontal = bubbleTailEdge == PetBubbleView.TailEdge.TOP || bubbleTailEdge == PetBubbleView.TailEdge.BOTTOM
        val target = if (horizontal) params.x + params.width / 2f - window.x else params.y + params.height / 2f - window.y
        view.setTail(bubbleTailEdge, target)
    }

    private fun moveLegacyPreview(dt: Float) {
        val speed = pet.locomotionSpeed * screenWidth
        if (route.isEmpty() && speed > 2 && spaceAvailable) {
            repeat(16) {
                if (route.isEmpty()) {
                    val candidate = PetSpace.Point(kotlin.random.Random.nextFloat() * (spaceArea.right - spaceArea.left) + spaceArea.left,
                        kotlin.random.Random.nextFloat() * (spaceArea.bottom - spaceArea.top) + spaceArea.top)
                    if (PetSpace.valid(candidate, spaceArea, obstacles) && candidate.distance(position) > params.width * .6f)
                        route = PetSpace.route(position, candidate, spaceArea, obstacles).toMutableList()
                }
            }
        }
        route.firstOrNull()?.let { target ->
            val distance = target.distance(position)
            if (distance < 1f) route.removeAt(0)
            else {
                val travel = min(distance, speed * dt)
                val next = PetSpace.Point(position.x + (target.x - position.x) / distance * travel, position.y + (target.y - position.y) / distance * travel)
                if (PetSpace.valid(next, spaceArea, obstacles) && PetSpace.clearSegment(position, next, obstacles)) {
                    pet.movementDirection = if (next.x >= position.x) 1f else -1f
                    position = next; params.x = (position.x - params.width / 2f).toInt(); params.y = (position.y - params.height / 2f).toInt()
                    pet.spatialDepth = .88f + .2f * (position.y / screenHeight)
                    safelyUpdate()
                } else route.clear()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        prefs = PetSettings(this); wm = getSystemService(WindowManager::class.java)
        pet = PetView(this)
        pet.onInteractionMenu = ::showInteractionMenu
        androidx.core.view.ViewCompat.addAccessibilityAction(pet, "打开互动菜单") { _, _ -> showInteractionMenu() }
        pet.onAuthoredFrameCommitted = ::consumeAuthoredMovement
        pet.onAuthoredStepAllowed = ::allowAuthoredStep
        pet.onAuthoredEntryAllowed = ::allowAuthoredEntry
        pet.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT < 30) {
                val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.let {
                    listOf(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom)
                } else null
                @Suppress("DEPRECATION") val measured = listOf(
                    max(max(insets.stableInsetLeft, insets.systemWindowInsetLeft), cutout?.get(0) ?: 0),
                    max(max(insets.stableInsetTop, insets.systemWindowInsetTop), cutout?.get(1) ?: 0),
                    max(max(insets.stableInsetRight, insets.systemWindowInsetRight), cutout?.get(2) ?: 0),
                    max(max(insets.stableInsetBottom, insets.systemWindowInsetBottom), cutout?.get(3) ?: 0),
                )
                if (measured != legacyInsets) { legacyInsets = measured; main.post { if (attached) { configure(); safelyUpdate() } } }
            }
            insets
        }
        params = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
        configure()
        bubbleScope.launch {
            PetInteractionMenu.chatVisible.collect { visible ->
                val visibility = if (visible) View.INVISIBLE else View.VISIBLE
                menu?.let {
                    if (visible) it.stopVoice()
                    it.visibility = visibility
                }
                menuOutsideTouch?.visibility = visibility
            }
        }
        bubbleScope.launch {
            dogReminders.display.collect { updateReminder(it) }
        }
        pet.onDrag = { dx, dy, finished ->
            cancelSnap()
            pet.setAuthoredTravelAllowed(false)
            dragging = !finished
            route.clear()
            val candidate = spaceArea.clamp(PetSpace.Point(position.x + dx, position.y + dy))
            if (PetSpace.valid(candidate, spaceArea, obstacles) && PetSpace.clearSegment(position, candidate, obstacles)) position = candidate
            params.x = (position.x - params.width / 2f).toInt()
            params.y = (position.y - params.height / 2f).toInt()
            floatingX = params.x.toFloat()
            if (pet.usesAuthoredFrames) pet.spatialDepth = .88f + .2f * (position.y / max(1, screenHeight))
            if (!finished) {
                dismissBubble()
                safelyUpdate()
            } else {
                val reminder = dogReminders.display.value
                if (reminder == null) snapToEdge() else updateReminder(reminder)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            addAction(PetEvents.MODEL_ACTION)
        }
        androidx.core.content.ContextCompat.registerReceiver(this, events, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        getSharedPreferences("pet", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener)
        getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, overlayPermissionListener)
    }
    private fun configure(resetPosition: Boolean = false) {
        dismissInteractionMenu()
        pet.setAuthoredTravelAllowed(false)
        cancelSnap()
        dismissBubble()
        val xFraction = if (attached && !resetPosition) params.x.toFloat() / max(1, screenWidth - params.width) else prefs.xFraction
        val yFraction = if (attached && !resetPosition) (params.y - insetTop).toFloat() / max(1, screenHeight - insetTop - insetBottom - params.height) else prefs.yFraction
        val density = resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.maximumWindowMetrics
            screenWidth = metrics.bounds.width(); screenHeight = metrics.bounds.height()
            val safe = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = safe.top; insetBottom = safe.bottom; insetLeft = safe.left; insetRight = safe.right
        } else {
            @Suppress("DEPRECATION") val display = wm.defaultDisplay
            val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION") display.getRealMetrics(m)
            screenWidth = m.widthPixels; screenHeight = m.heightPixels
            val measured = legacyInsets
            insetLeft = measured?.get(0) ?: 0; insetTop = measured?.get(1) ?: (32 * density).toInt()
            insetRight = measured?.get(2) ?: 0; insetBottom = measured?.get(3) ?: (48 * density).toInt()
        }
        params.width = (prefs.size * density).toInt().coerceAtMost(screenWidth)
        params.height = params.width
        params.alpha = prefs.opacity
        params.x = ((screenWidth - params.width).coerceAtLeast(0) * xFraction.coerceIn(0f, 1f)).toInt()
        params.y = insetTop + ((screenHeight - insetTop - insetBottom - params.height).coerceAtLeast(0) * yFraction.coerceIn(0f, 1f)).toInt()
        floatingX = params.x.toFloat()
        val bounds = if (prefs.confinement == "region") prefs.activityRect else android.graphics.RectF(0f, 0f, 1f, 1f)
        val half = params.width / 2f
        spaceArea = PetSpace.Box(max(insetLeft.toFloat(), bounds.left * screenWidth) + half,
            max(insetTop.toFloat(), bounds.top * screenHeight) + half,
            min((screenWidth - insetRight).toFloat(), bounds.right * screenWidth) - half,
            min((screenHeight - insetBottom).toFloat(), bounds.bottom * screenHeight) - half)
        obstacles = prefs.forbiddenRects.map { PetSpace.Box(it.left * screenWidth, it.top * screenHeight, it.right * screenWidth, it.bottom * screenHeight).inflate(half + 2, half + 2) }
        val free = if (spaceArea.right >= spaceArea.left && spaceArea.bottom >= spaceArea.top)
            PetSpace.nearestFree(PetSpace.Point(params.x + half, params.y + half), spaceArea, obstacles) else null
        spaceAvailable = free != null
        free?.let { position = it; params.x = (it.x - half).toInt(); params.y = (it.y - half).toInt() }
        if (pet.usesAuthoredFrames) pet.spatialDepth = .88f + .2f * (position.y / max(1, screenHeight))
        route.clear()
        if (!spaceAvailable) {
            android.widget.Toast.makeText(this, "活动区没有足够空间，请扩大活动区、缩小桌宠或调整禁区。", android.widget.Toast.LENGTH_LONG).show()
        }
        reconcileVisibleState()
        if (attached) updateReminder(dogReminders.display.value)
    }
    private fun reconcileVisibleState() {
        val visible = attached && !hidden && spaceAvailable && getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        dogReminders.setVisible(visible)
        pet.visibility = if (visible) View.VISIBLE else View.GONE
        main.removeCallbacks(motion)
        if (visible) { pet.resume(); lastMotion = 0; main.post(motion) }
        else { dismissInteractionMenu(); pet.pause(); cancelSnap(); dismissBubble(); lastMotion = 0 }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); legacyInsets = null; dismissBubble(); configure(); safelyUpdate(); pet.requestApplyInsets() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == STOP || (action == PREPARE_VOICE && !attached)) { stopSelf(); return START_NOT_STICKY }
        if (action == RESET) { prefs.xFraction = prefs.defaultX; prefs.yFraction = prefs.defaultY; configure(resetPosition = true) }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "桌宠运行状态", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        val open = PendingIntent.getActivity(this, 200, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 201, Intent(this, OverlayService::class.java).setAction(TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 202, Intent(this, OverlayService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun notification(voice: Boolean) = Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.cutedog.app.R.drawable.ic_notification)
            .setContentTitle("${prefs.name}在身边")
            .setContentText(if (voice) "桌宠语音已就绪 · 按住麦克风时录音" else "轻抚互动 · 双击选动作 · 点此通知打开应用")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "显示 / 隐藏", toggle).build())
            .addAction(Notification.Action.Builder(null, "结束陪伴", close).build()).build()
        val wantsVoice = (microphoneReady || action == START_FROM_APP || action == PREPARE_VOICE) && canPrepareVoice(this)
        microphoneReady = wantsVoice && runCatching {
            showForeground(notification(true), microphone = true)
        }.isSuccess
        if (!microphoneReady) showForeground(notification(false), microphone = false)
        hidden = when (action) { HIDE -> true; TOGGLE -> !hidden; PREPARE_VOICE -> hidden; else -> false }
        if (!attached) {
            try { wm.addView(pet, params); attached = true } catch (_: SecurityException) { stopSelf(); return START_NOT_STICKY }
            catch (_: WindowManager.BadTokenException) { stopSelf(); return START_NOT_STICKY }
            catch (_: WindowManager.InvalidDisplayException) { stopSelf(); return START_NOT_STICKY }
        }
        reconcileVisibleState()
        activeOverlay = this
        pet.requestApplyInsets()
        return START_NOT_STICKY // The user starts again; no endless restart after revoking permission.
    }

    private fun showForeground(notification: Notification, microphone: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            var type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            if (Build.VERSION.SDK_INT >= 30 && microphone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(200, notification, type)
        } else startForeground(200, notification)
    }
    private fun safelyUpdate() {
        if (!attached) return
        try { wm.updateViewLayout(pet, params) } catch (_: SecurityException) { stopSelf() } catch (_: IllegalArgumentException) { attached = false; stopSelf() }
    }
    private fun snapToEdge() {
        pet.setAuthoredTravelAllowed(false)
        cancelSnap()
        val start = params.x
        val edge = if (position.x < (spaceArea.left + spaceArea.right) / 2) spaceArea.left else spaceArea.right
        val target = PetSpace.Point(edge, position.y)
        if (!PetSpace.valid(target, spaceArea, obstacles) || !PetSpace.clearSegment(position, target, obstacles)) { savePosition(); return }
        val end = (edge - params.width / 2).toInt()
        val began = SystemClock.uptimeMillis()
        val task = object : Runnable {
            override fun run() {
                if (!attached || dragging || hidden || !pet.isShown || !getSystemService(PowerManager::class.java).isInteractive) { snapTask = null; return }
                val t = ((SystemClock.uptimeMillis() - began) / 260f).coerceIn(0f, 1f)
                params.x = (start + (end - start) * (1 - (1 - t).pow(3))).toInt(); floatingX = params.x.toFloat(); position = position.copy(x = params.x + params.width / 2f); safelyUpdate()
                if (t < 1) main.postDelayed(this, 16) else { snapTask = null; savePosition() }
            }
        }
        snapTask = task
        main.post(task)
    }
    private fun cancelSnap() { snapTask?.let(main::removeCallbacks); snapTask = null }
    private fun savePosition() {
        prefs.xFraction = params.x.toFloat() / max(1, screenWidth - params.width)
        prefs.yFraction = (params.y - insetTop).toFloat() / max(1, screenHeight - insetTop - insetBottom - params.height)
    }
    private fun showInteractionMenu(): Boolean {
        if (!attached || !pet.isShown || bubble != null || dogReminders.display.value != null) return false
        if (menuOpen) return true
        if (PetInteractionMenu.chatVisible.value) return false
        val origin = IntArray(2).also(pet::getLocationOnScreen)
        val dog = Rect(origin[0], origin[1], origin[0] + pet.width, origin[1] + pet.height)
        val viewport = Rect(insetLeft, insetTop, screenWidth - insetRight, screenHeight - insetBottom)
        val themed = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_NoActionBar)
        val panel = PetMenuView(themed, prefs.name, onAction = ::selectMenuAction,
            voiceReady = { microphoneReady })
        val bounds = panel.place(viewport, dog) ?: return false
        val window = WindowManager.LayoutParams(bounds.width(), bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            setTitle("CuteDog interaction panel")
            gravity = Gravity.TOP or Gravity.LEFT
            x = bounds.left
            y = bounds.top
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        cancelSnap()
        route.clear()
        menuMovementUntil = 0L
        pet.setAuthoredTravelAllowed(false)
        // Some Android skins don't send ACTION_OUTSIDE to application overlays.
        // A separate invisible touch target below the panel receives outside taps.
        val outside = View(themed).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) dismissInteractionMenu()
                true
            }
        }
        val outsideWindow = WindowManager.LayoutParams(screenWidth, screenHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, window.flags, PixelFormat.TRANSLUCENT).apply {
            setTitle("CuteDog outside touch")
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        return try {
            wm.addView(outside, outsideWindow)
            menuOutsideTouch = outside
            wm.addView(panel, window)
            menu = panel
            true
        } catch (_: SecurityException) {
            dismissInteractionMenu()
            false
        } catch (_: WindowManager.BadTokenException) {
            dismissInteractionMenu()
            false
        } catch (_: WindowManager.InvalidDisplayException) {
            dismissInteractionMenu()
            false
        }
    }

    private fun selectMenuAction(action: String) {
        if (!menuOpen || !pet.isShown || dogReminders.display.value != null) return
        if (action == PetInteractionMenu.CHAT) {
            openPanelChat()
            return
        }
        if (action == "trot" || action == "jump") menuMovementUntil = SystemClock.uptimeMillis() + 14_000L
        pet.interact(action)
    }

    private fun openPanelChat() {
        val intent = Intent(this, PetChatActivity::class.java)
            .putExtra(PetChatActivity.FROM_PET_PANEL, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { startActivity(intent) }
    }

    private fun dismissInteractionMenu() {
        val panel = menu
        val outside = menuOutsideTouch
        menu = null
        menuOutsideTouch = null
        if (panel != null) {
            panel.stopVoice()
            runCatching { wm.removeViewImmediate(panel) }
        }
        if (outside != null) runCatching { wm.removeViewImmediate(outside) }
    }

    private fun updateReminder(display: DogReminder?) {
        if (display == null || dragging) {
            dismissBubble()
            return
        }
        dismissInteractionMenu()
        val task = display
        val view = PetBubbleView(this).apply {
            showReminder(task.title, task.clock())
            onComplete = { completeFromDog(this@OverlayService, task) }
        }
        pet.remind()
        attachBubble(view, display)
    }
    private fun attachBubble(view: PetBubbleView, display: DogReminder) {
        if (hidden || !spaceAvailable || !getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        cancelSnap()
        dismissBubble()
        route.clear()
        if (pet.usesAuthoredFrames) {
            if (OverlayTravelPolicy.shouldStop(true, pet.authoredTravelRequested)) pet.stopAuthoredTravel()
            pet.setAuthoredTravelAllowed(pet.authoredTravelInProgress)
        }
        val dp = resources.displayMetrics.density
        val margin = (8 * dp).toInt()
        val safe = android.graphics.Rect(insetLeft + margin, insetTop + margin, screenWidth - insetRight - margin, screenHeight - insetBottom - margin)
        if (safe.width() <= 0 || safe.height() <= 0) return
        val petRect = android.graphics.Rect(params.x, params.y, params.x + params.width, params.y + params.height)
        val areas = listOf(
            PetBubbleView.TailEdge.BOTTOM to android.graphics.Rect(safe.left, safe.top, safe.right, min(safe.bottom, petRect.top)),
            PetBubbleView.TailEdge.TOP to android.graphics.Rect(safe.left, max(safe.top, petRect.bottom), safe.right, safe.bottom),
            PetBubbleView.TailEdge.RIGHT to android.graphics.Rect(safe.left, safe.top, min(safe.right, petRect.left), safe.bottom),
            PetBubbleView.TailEdge.LEFT to android.graphics.Rect(max(safe.left, petRect.right), safe.top, safe.right, safe.bottom),
        )
        val chosen = areas.filter { it.second.width() >= 120 * dp && it.second.height() >= 100 * dp }
            .maxByOrNull { min(it.second.width().toFloat(), 272 * dp) * min(it.second.height().toFloat(), 400 * dp) }
            ?: (PetBubbleView.TailEdge.BOTTOM to safe)
        val edge = chosen.first; val area = chosen.second
        view.setTail(edge)
        val size = view.measureForBounds(area.width(), area.height())
        val centeredX = (petRect.centerX() - size.width / 2).coerceIn(area.left, max(area.left, area.right - size.width))
        val centeredY = (petRect.centerY() - size.height / 2).coerceIn(area.top, max(area.top, area.bottom - size.height))
        val x = when (edge) { PetBubbleView.TailEdge.RIGHT -> area.right - size.width; PetBubbleView.TailEdge.LEFT -> area.left; else -> centeredX }
        val y = when (edge) { PetBubbleView.TailEdge.BOTTOM -> area.bottom - size.height; PetBubbleView.TailEdge.TOP -> area.top; else -> centeredY }
        view.setTail(edge, if (edge == PetBubbleView.TailEdge.BOTTOM || edge == PetBubbleView.TailEdge.TOP) (petRect.centerX() - x).toFloat() else (petRect.centerY() - y).toFloat())
        val window = WindowManager.LayoutParams(size.width, size.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; this.x = x; this.y = y
        }
        try {
            wm.addView(view, window); bubble = view; bubbleWindow = window; bubbleTailEdge = edge
            if (!prefs.reducedMotion) { view.alpha = 0f; view.animate().alpha(1f).setDuration(180).start() }
            val timeout = Runnable { if (bubble === view) dogReminders.timeout(display) }
            bubbleTimeout = timeout
            main.postDelayed(timeout, view.recommendedTimeout())
        } catch (_: SecurityException) { stopSelf() }
          catch (_: WindowManager.BadTokenException) { stopSelf() }
          catch (_: WindowManager.InvalidDisplayException) { stopSelf() }
    }
    private fun dismissBubble(animated: Boolean = false) {
        bubbleTimeout?.let(main::removeCallbacks); bubbleTimeout = null
        bubbleWindow = null
        val view = bubble ?: return
        view.animate().cancel()
        if (animated && !prefs.reducedMotion && view.isAttachedToWindow)
            view.animate().alpha(0f).setDuration(130).withEndAction { if (bubble === view) bubble = null; runCatching { wm.removeView(view) } }.start()
        else { bubble = null; runCatching { wm.removeView(view) } }
    }
    override fun onDestroy() {
        if (activeOverlay === this) activeOverlay = null
        bubbleScope.cancel()
        dogReminders.setVisible(false)
        dismissInteractionMenu()
        main.removeCallbacksAndMessages(null); stepApproval.clear(); cancelSnap(); dismissBubble(); pet.pause()
        if (attached) { savePosition(); runCatching { wm.removeView(pet) }; attached = false }
        unregisterReceiver(events)
        getSharedPreferences("pet", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(settingsListener)
        getSystemService(AppOpsManager::class.java).stopWatchingMode(overlayPermissionListener)
        super.onDestroy()
    }
    override fun dump(fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        super.dump(fd, writer, args)
        if (::pet.isInitialized) writer.println("CUTEDOG_METRICS_JSON:${pet.diagnosticSnapshot()}")
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private var activeOverlay: OverlayService? = null
        internal val voiceReady get() = activeOverlay?.microphoneReady == true
        private const val START_FROM_APP = "pet.start_from_app"
        private const val PREPARE_VOICE = "pet.prepare_voice"
        private const val CHANNEL = "pet_status"
        private const val STOP = "pet.stop"; private const val HIDE = "pet.hide"; private const val TOGGLE = "pet.toggle"; private const val RESET = "pet.reset"
        fun start(context: Context) { context.startForegroundService(Intent(context, OverlayService::class.java)) }
        internal fun startFromActivity(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java).setAction(START_FROM_APP))
        }
        internal fun prepareVoiceIfRunning(context: Context) {
            if (activeOverlay == null || voiceReady || !canPrepareVoice(context)) return
            runCatching { context.startService(Intent(context, OverlayService::class.java).setAction(PREPARE_VOICE)) }
        }
        private fun canPrepareVoice(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                DoubaoSettings(context).microphoneUploadAccepted
        fun stop(context: Context) { val request = Intent(context, OverlayService::class.java); context.stopService(request) }
        fun hide(context: Context) { context.startService(Intent(context, OverlayService::class.java).setAction(HIDE)) }
        fun show(context: Context) = start(context)
        fun resetPosition(context: Context) { val p = PetSettings(context); p.xFraction = p.defaultX; p.yFraction = p.defaultY; if (Settings.canDrawOverlays(context)) context.startForegroundService(Intent(context, OverlayService::class.java).setAction(RESET)) }
    }
}
