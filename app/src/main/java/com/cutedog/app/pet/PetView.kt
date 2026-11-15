package com.cutedog.app.pet

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.*
import android.os.PowerManager
import android.os.SystemClock
import android.view.*
import java.lang.ref.WeakReference
import java.time.LocalTime
import kotlin.math.*
import com.cutedog.app.pet.animation.FrameClipPlayer
import com.cutedog.app.pet.animation.ClipPlaybackFrame
import com.cutedog.app.pet.animation.AuthoredTravelAccumulator
import com.cutedog.app.pet.animation.FrameScheduleGate
import com.cutedog.app.pet.behavior.PetBrain
import com.cutedog.app.pet.behavior.PetContext
import com.cutedog.app.pet.behavior.GrowthStore

/** Small, cached texture renderer shared by the app and the bounded overlay window. */
class PetView(context: Context) : View(context) {
    private val settings = PetSettings(context)
    private val audio = PetAudio(context.applicationContext)
    private val brain = PetBrain()
    private val growth = GrowthStore(context.applicationContext)
    private val hasRigAssets = context.assets.list("pet3d")?.contains("manifest.json") == true
    private var rigPlayer: FrameClipPlayer? = null
    private var rigFrame: ClipPlaybackFrame? = null
    private var interactionAge = 0f
    private val authoredTravel = AuthoredTravelAccumulator()
    private var authoredTravelAllowed = false
    private var desiredHeading = 0
    private var touchActive = false
    private var stoppingForSpace = false
    private var tickCount = 0L
    private var drawCount = 0L
    private var actualFrameChanges = 0L
    private var observedFrame: Pair<String?, Int>? = null
    private val diagnosticId = java.util.UUID.randomUUID().toString()
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() { rigPlayer?.trimMemory() }
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) rigPlayer?.trimMemory()
        }
    }
    val usesAuthoredFrames: Boolean get() = hasRigAssets
    /** Legacy preview only. Authored animation never derives a speed from an action name. */
    val locomotionSpeed: Float get() = if (hasRigAssets) 0f else abs(behavior.velocity)
    val authoredTravelRequested: Boolean get() = rigPlayer?.let { player ->
        val target = player.requestedClip ?: return@let false
        val canonical = player.manifest?.canonicalClipId(target)
        canonical !in STATIONARY_PROP_ACTIONS && player.manifest?.travelReady(target) == true
    } ?: false
    val authoredTravelInProgress: Boolean get() = rigPlayer?.let { it.manifest?.travelReady(it.currentClip) == true && it.canonicalClipId !in STATIONARY_PROP_ACTIONS } ?: false
    val authoredStoppingDistancePixels: Float? get() = rigPlayer?.stoppingTravelFraction?.let { (it * min(width, height) * spatialDepth).toFloat() }
    val authoredPlanningMarginPixels: Float get() = ((rigPlayer?.maximumStepFraction ?: 0.0) * min(width, height) * spatialDepth * 3).toFloat()
    val authoredActualHeading: Int get() = rigPlayer?.let { player ->
        player.manifest?.clips?.get(player.currentClip)?.let { if (it.heading == it.exitHeading) it.heading?.sign ?: 0 else 0 }
    } ?: 0
    val authoredDesiredHeading: Int get() = desiredHeading
    private val hostAllowsTravel: Boolean get() = authoredTravelAllowed && !touchActive && running && isShown && power.isInteractive && !settings.reducedMotion
    private val canAuthoredTravel: Boolean get() = hostAllowsTravel && authoredTravelInProgress && rigPlayer?.canTravel == true
    val behavior = PetBehavior()
    var onDrag: ((Float, Float, Boolean) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onAction: ((String) -> Unit)? = null
    var onAuthoredFrameCommitted: ((Int) -> Unit)? = null
    var onAuthoredStepAllowed: ((Float) -> Boolean)? = null
    var movementDirection = 0f
    var spatialDepth = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(23, 66, 45, 29) }
    private val source = Rect()
    private val dest = RectF()
    private val atlas by lazy { cachedAtlas.get() ?: context.assets.open("pet/samoyed.png").use {
        BitmapFactory.decodeStream(it).also { b -> cachedAtlas = WeakReference(b) }
    } }
    private val walk by lazy { runCatching { context.assets.open("pet/walk.png").use { BitmapFactory.decodeStream(it) } }.getOrNull() }
    private var lastFrame = 0L
    private var lastAuthoredFrameNanos = 0L
    private var deferredBrainSeconds = 0f
    private val frameLifecycle = FrameScheduleGate()
    private val running: Boolean get() = frameLifecycle.running
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var downAt = 0L
    private var dragged = false
    private var stroked = false
    private var lastTap = 0L
    private val singleTap = Runnable { performClick() }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val power = context.getSystemService(PowerManager::class.java)
    private val frameClock = Choreographer.getInstance()
    private var authoredTick: Choreographer.FrameCallback? = null
    private val tick = Runnable { advanceFrame(SystemClock.uptimeMillis(), null) }
    private val approveAuthoredTravel: (Double) -> Boolean = { fraction ->
        // The app's in-place animation preview has no moving system window. Overlay playback
        // requires permission for the complete measured step before either frame or window advances.
        onDrag == null || (hostAllowsTravel && authoredTravelInProgress && onAuthoredStepAllowed?.invoke((fraction * min(width, height) * spatialDepth).toFloat()) == true)
    }
    private val approveAuthoredEntry: (Int, Double) -> Boolean = { heading, fraction ->
        onDrag == null || (hostAllowsTravel && onAuthoredStepAllowed?.invoke(
            (heading * fraction * min(width, height) * spatialDepth).toFloat()) == true)
    }
    private fun advanceFrame(now: Long, frameTimeNanos: Long?) {
        if (!running || !isShown || !power.isInteractive) { pause(); return }
        val scheduleToken = frameLifecycle.token()
        tickCount++
        var needsInvalidate = !hasRigAssets
        behavior.quiet = settings.activity == "quiet" || LocalTime.now().hour in 0..7 || LocalTime.now().hour >= 22
        behavior.active = settings.activity == "lively"
        behavior.reducedMotion = settings.reducedMotion
        val seconds = if (frameTimeNanos != null) {
            if (lastAuthoredFrameNanos == 0L) 0.0 else ((frameTimeNanos - lastAuthoredFrameNanos) / 1_000_000_000.0).coerceIn(0.0, .25)
        } else if (lastFrame == 0L) 0.0 else ((now - lastFrame) / 1000.0).coerceIn(0.0, .25)
        val delta = seconds.toFloat()
        if (hasRigAssets) {
            val player = rigPlayer
            if (player != null) {
                val frame = player.tick(seconds, approveAuthoredTravel, approveAuthoredEntry)
                frame.rejectedTravelTarget?.let(brain::requestRejected)
                if (frame.error != rigFrame?.error) needsInvalidate = true
                rigFrame = frame
                val frameKey = frame.clipId to frame.frameIndex
                if (frame.bitmap != null && frameKey != observedFrame) {
                    actualFrameChanges++; observedFrame = frameKey; needsInvalidate = true
                }
                interactionAge += delta
                deferredBrainSeconds += delta
                val travel = player.authoredTravelDeltaFraction
                val stepHeading = if (travel > 0.0) 1 else if (travel < 0.0) -1 else player.travelHeading
                authoredTravel.record(travel, min(width, height).toDouble() * spatialDepth,
                    stepHeading, onDrag != null && hostAllowsTravel && (travel != 0.0 || canAuthoredTravel), now)
                if (travel != 0.0 && onDrag != null && hostAllowsTravel) onAuthoredFrameCommitted?.invoke(stepHeading)
                if (!frameLifecycle.isCurrent(scheduleToken)) { if (needsInvalidate) invalidate(); return }
                if (stoppingForSpace && player.canonicalClipId == "stand") stoppingForSpace = false
                // Waiting checks only touch the ready-frame cache; no battery query/behavior
                // selection and no redundant drawing at every vsync while a decoder is busy.
                if (!frame.loading && !frame.travelBlocked && frame.bitmap != null && frame.error == null && !stoppingForSpace) {
                    val battery = context.getSystemService(android.os.BatteryManager::class.java)
                    val quiet = settings.activity == "quiet" || LocalTime.now().hour in 0..7 || LocalTime.now().hour >= 22
                    val situation = PetContext(LocalTime.now().hour, quiet || settings.reducedMotion, settings.activity,
                        battery.isCharging, power.isPowerSaveMode || settings.lowPower, !settings.soundEnabled, interactionAge)
                    val available = player.manifest?.clips?.keys ?: emptySet()
                    brain.update(deferredBrainSeconds, player.canonicalClipId ?: "stand", available, situation)?.let {
                        if (!player.request(it)) brain.requestRejected(it)
                    }
                    deferredBrainSeconds = 0f
                }
                if (stoppingForSpace) deferredBrainSeconds = 0f
            }
        } else if (lastFrame != 0L) {
            var elapsed = delta
            while (elapsed > 0f) {
                val step = min(elapsed, .05f)
                behavior.update(step)
                elapsed -= step
            }
        }
        // Window translation above and bitmap invalidation share this main-thread animation phase.
        if (needsInvalidate) invalidate()
        if (frameLifecycle.isCurrent(scheduleToken)) {
            lastFrame = now
            if (frameTimeNanos != null) lastAuthoredFrameNanos = frameTimeNanos
            if (hasRigAssets) {
                // Choreographer's timestamp and System.nanoTime share the monotonic timebase.
                // Account for work already spent in this callback instead of drifting each deadline.
                val elapsedSinceVsync = frameTimeNanos?.let { (System.nanoTime() - it).coerceAtLeast(0L) } ?: 0L
                authoredTick?.let { frameClock.postFrameCallbackDelayed(it, rigPlayer?.nextFrameDelayMillis(elapsedSinceVsync) ?: 250L) }
            }
            else postDelayed(tick, if (settings.reducedMotion || power.isPowerSaveMode || settings.lowPower) 100L
                else if (behavior.state == PetBehavior.State.SLEEP) 83L else 33L)
        }
    }
    init {
        contentDescription = "${settings.name}，轻抚互动，长按拖动，点击打开应用"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
    fun interact(intent: String) {
        growth.recordInteraction()
        react(intent)
    }
    fun react(intent: String) {
        stoppingForSpace = false
        interactionAge = 0f
        if (hasRigAssets) {
            brain.interact(when (intent) { "pet" -> "head"; "toy" -> "ball"; else -> intent })?.let { if (rigPlayer?.request(it) == false) brain.requestRejected(it) }
        } else behavior.react(if (intent == "nuzzle" || intent == "body") "cuddle" else intent)
        audio.play(if (intent == "jump" || intent == "toy" || intent == "ball") "ball" else if (intent == "pet" || intent == "cuddle") "breath" else "paw")
        resume()
    }
    fun setAuthoredTravelAllowed(allowed: Boolean) {
        authoredTravelAllowed = allowed
        if (!allowed) authoredTravel.clear()
    }
    fun requestAuthoredHeading(sign: Int): Boolean {
        if (!hasRigAssets || sign !in -1..1 || sign == 0) return false
        val accepted = rigPlayer?.requestHeading(sign) == true
        if (accepted) desiredHeading = sign
        return accepted
    }
    fun stopAuthoredTravel(): Boolean {
        val accepted = rigPlayer?.requestStop() == true
        if (accepted) {
            brain.pendingTarget?.let(brain::requestRejected)
            stoppingForSpace = true
        }
        return accepted
    }
    /** Called once by the successful-frame callback, before this frame is invalidated. */
    fun consumeAuthoredTravelPixels(sign: Int): Float = authoredTravel.consume(sign,
        hostAllowsTravel, SystemClock.uptimeMillis())
    fun pause() {
        frameLifecycle.pause(); touchActive = false; authoredTravel.clear(); authoredTravelAllowed = false
        removeCallbacks(tick); authoredTick?.let(frameClock::removeFrameCallback); authoredTick = null; removeCallbacks(singleTap)
        rigPlayer?.setActive(false); lastFrame = 0; lastAuthoredFrameNanos = 0; deferredBrainSeconds = 0f
    }
    fun resume() {
        if (isAttachedToWindow && visibility == VISIBLE && power.isInteractive) {
            if (!frameLifecycle.resume()) {
                // Preserve legacy immediate feedback; authored callbacks keep their single deadline.
                if (!hasRigAssets) { removeCallbacks(tick); post(tick) }
                return
            }
            rigPlayer?.setActive(true); removeCallbacks(tick); authoredTick?.let(frameClock::removeFrameCallback)
            if (hasRigAssets) {
                val registeredEpoch = frameLifecycle.token()
                // One object per real resume, not per frame. A stale callback already extracted
                // by Choreographer still carries its old epoch and cannot enter the restarted loop.
                val callback = Choreographer.FrameCallback { frameTimeNanos ->
                    if (frameLifecycle.isCurrent(registeredEpoch)) advanceFrame(SystemClock.uptimeMillis(), frameTimeNanos)
                }
                authoredTick = callback; frameClock.postFrameCallback(callback)
            } else post(tick)
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        attachedViews.removeAll { it.get() == null || it.get() === this }
        attachedViews.add(WeakReference(this))
        if (hasRigAssets && rigPlayer == null) rigPlayer = FrameClipPlayer(context).also { player ->
            if (desiredHeading != 0) player.requestHeading(desiredHeading)
            brain.pendingTarget?.let(player::request)
        }
        resume()
    }
    override fun onDetachedFromWindow() {
        pause(); audio.release(); rigPlayer?.close(); rigPlayer = null; rigFrame = null; observedFrame = null
        context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
        attachedViews.removeAll { it.get() == null || it.get() === this }
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) resume() else pause()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCount++
        if (hasRigAssets) {
            val frame = rigFrame
            val pixels = frame?.bitmap
            if (pixels != null) {
                val side = min(width, height).toFloat() * spatialDepth
                source.set(0, 0, pixels.width, pixels.height)
                dest.set((width - side) / 2, height - side, (width + side) / 2, height.toFloat())
                paint.alpha = 255
                canvas.drawBitmap(pixels, source, dest, paint)
            } else if (frame?.error != null) {
                paint.alpha = 255; paint.color = Color.rgb(77, 85, 75)
                paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("角色资源未能加载，请检查安装包", width / 2f, height / 2f, paint)
                contentDescription = "角色资源未能加载，请重新安装完整版本"
                paint.textAlign = Paint.Align.LEFT
            }
            return
        }
        val side = min(width, height).toFloat() * .91f
        val breath = if (settings.reducedMotion) 1f else 1f + sin(behavior.clock * 2.2f) * .007f
        val offset = if (onDrag == null && !settings.reducedMotion) (behavior.position - .5f) * width * .12f else 0f
        val x = width / 2f + offset
        val floor = height * .94f
        canvas.drawOval(x - side * .29f, floor - side * .035f, x + side * .29f, floor + side * .015f, shadow)
        canvas.save()
        canvas.translate(x, floor - behavior.jump * side * .4f)
        val direction = if (movementDirection != 0f) movementDirection else behavior.velocity
        val facing = if (direction > .001f) -1f else 1f
        canvas.scale(facing * behavior.depth * spatialDepth, breath * behavior.depth * spatialDepth)
        val bob = if (settings.reducedMotion) 0f else abs(sin(behavior.clock * 7f)) * min(abs(behavior.velocity) * 20, 1f) * side * .012f
        dest.set(-side / 2, -side - bob, side / 2, -bob)
        val mix = behavior.blend.let { it * it * (3 - 2 * it) }
        if (mix < 1f) drawPose(canvas, behavior.previous.pose, 1 - mix)
        drawPose(canvas, behavior.state.pose, mix)
        canvas.restore()
    }
    private fun drawPose(canvas: Canvas, pose: Int, alpha: Float) {
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        val moving = pose == 1 && walk != null && abs(behavior.velocity) > .005f
        if (moving) {
            val bitmap = walk!!
            val frame = ((behavior.clock * (6 + abs(behavior.velocity) * 45)).toInt() % 4)
            val w = bitmap.width / 4
            source.set(frame * w, 0, (frame + 1) * w, bitmap.height)
            canvas.drawBitmap(bitmap, source, dest, paint)
        } else {
            val cellW = atlas.width / 3; val cellH = atlas.height / 2
            source.set(pose % 3 * cellW, pose / 3 * cellH, (pose % 3 + 1) * cellW, (pose / 3 + 1) * cellH)
            canvas.drawBitmap(atlas, source, dest, paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true; authoredTravel.clear()
                downX = event.rawX; downY = event.rawY; lastX = downX; lastY = downY
                downAt = SystemClock.uptimeMillis(); dragged = false; stroked = false
                growth.recordInteraction()
                brain.setAttentionTarget((event.x / max(1, width)).coerceIn(0f, 1f), (event.y / max(1, height)).coerceIn(0f, 1f))
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = hypot(event.rawX - downX, event.rawY - downY)
                if (distance > slop && SystemClock.uptimeMillis() - downAt >= settings.dragHoldMs && !stroked && !settings.locked && onDrag != null) {
                    if (!dragged && settings.haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    dragged = true
                    onDrag?.invoke(event.rawX - lastX, event.rawY - lastY, false)
                } else if (!dragged && settings.pettingEnabled && distance > slop && SystemClock.uptimeMillis() - downAt < settings.dragHoldMs) {
                    if (!stroked) { react(if (event.y < height * .6f) "pet" else "body"); if (settings.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                    stroked = true
                }
                lastX = event.rawX; lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                touchActive = false; authoredTravel.clear()
                if (dragged) onDrag?.invoke(0f, 0f, true) else if (!stroked) {
                    val now = SystemClock.uptimeMillis()
                    if (settings.doubleTapAction == "none") performClick()
                    else if (lastTap != 0L && now - lastTap <= ViewConfiguration.getDoubleTapTimeout()) {
                        removeCallbacks(singleTap); lastTap = 0; react(settings.doubleTapAction)
                    } else { lastTap = now; removeCallbacks(singleTap); postDelayed(singleTap, ViewConfiguration.getDoubleTapTimeout().toLong()) }
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { touchActive = false; authoredTravel.clear(); if (dragged) onDrag?.invoke(0f, 0f, true); parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); react("wave"); onTap?.invoke(); onAction?.invoke(settings.tapAction); return true }
    /** Shell dumpsys diagnostics contain counters only, never chats, reminders, names or credentials. */
    fun diagnosticSnapshot(): org.json.JSONObject {
        val cache = rigPlayer?.cacheStats()
        return org.json.JSONObject().apply {
            put("viewId", diagnosticId); put("scope", if (onDrag != null) "overlay" else "activity")
            put("renderMode", if (hasRigAssets) "authored_frames" else "legacy")
            put("running", running && isShown && power.isInteractive); put("visible", isShown)
            put("tickCount", tickCount); put("drawCount", drawCount)
            put("actualFrameChanges", if (hasRigAssets) actualFrameChanges else org.json.JSONObject.NULL)
            put("clipId", rigFrame?.clipId ?: org.json.JSONObject.NULL)
            put("canonicalClipId", rigPlayer?.canonicalClipId ?: org.json.JSONObject.NULL)
            put("canTravel", canAuthoredTravel); put("travelHeading", if (canAuthoredTravel) rigPlayer?.travelHeading ?: 0 else 0)
            put("clipFps", if (hasRigAssets) rigPlayer?.requiredTickRate ?: 0 else 0)
            put("loading", rigFrame?.loading ?: false)
            put("travelBlocked", rigFrame?.travelBlocked ?: false)
            put("travelBlockedReason", rigFrame?.travelBlockedReason ?: org.json.JSONObject.NULL)
            put("cacheBytes", cache?.allocatedBytes ?: 0L); put("cacheEntries", cache?.cachedFrames ?: 0)
            put("decodedFrames", cache?.decodedFrames ?: 0L); put("reusedBuffers", cache?.reusedBuffers ?: 0L)
            put("pendingFrames", cache?.pendingFrames ?: 0); put("activeDecoder", cache?.activeDecoder ?: false)
            put("uptimeMs", SystemClock.uptimeMillis())
        }
    }
    companion object {
        private val STATIONARY_PROP_ACTIONS = setOf("play_ball", "treat")
        private var cachedAtlas = WeakReference<Bitmap>(null)
        private val attachedViews = mutableListOf<WeakReference<PetView>>()
        fun diagnosticSnapshots() = org.json.JSONArray().apply {
            attachedViews.mapNotNull { it.get() }.forEach { put(it.diagnosticSnapshot()) }
        }
    }
}
