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
    private var rigSpeed = 0f
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
    val locomotionSpeed: Float get() = if (hasRigAssets) {
        if (rigFrame?.bitmap == null || rigFrame?.loading != false || rigFrame?.error != null) 0f else rigSpeed
    } else abs(behavior.velocity)
    val behavior = PetBehavior()
    var onDrag: ((Float, Float, Boolean) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onAction: ((String) -> Unit)? = null
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
    private var running = false
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var downAt = 0L
    private var dragged = false
    private var stroked = false
    private var lastTap = 0L
    private val singleTap = Runnable { performClick() }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val power = context.getSystemService(PowerManager::class.java)
    private val tick = object : Runnable {
        override fun run() {
            if (!running || !isShown || !power.isInteractive) { lastFrame = 0; return }
            tickCount++
            val now = SystemClock.uptimeMillis()
            behavior.quiet = settings.activity == "quiet" || LocalTime.now().hour in 0..7 || LocalTime.now().hour >= 22
            behavior.active = settings.activity == "lively"
            behavior.reducedMotion = settings.reducedMotion
            val delta = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(.25f)
            if (hasRigAssets) {
                val player = rigPlayer
                if (player != null) {
                    val frame = player.tick(delta.toDouble()); rigFrame = frame
                    val frameKey = frame.clipId to frame.frameIndex
                    if (frame.bitmap != null && frameKey != observedFrame) {
                        actualFrameChanges++; observedFrame = frameKey
                    }
                    interactionAge += delta
                    val battery = context.getSystemService(android.os.BatteryManager::class.java)
                    val quiet = settings.activity == "quiet" || LocalTime.now().hour in 0..7 || LocalTime.now().hour >= 22
                    val situation = PetContext(LocalTime.now().hour, quiet || settings.reducedMotion, settings.activity,
                        battery.isCharging, power.isPowerSaveMode || settings.lowPower, !settings.soundEnabled, interactionAge)
                    val available = player.manifest?.clips?.keys ?: emptySet()
                    brain.update(delta, frame.clipId ?: "stand", available, situation)?.let { if (!player.request(it)) brain.requestRejected(it) }
                    val targetSpeed = when (frame.clipId) {
                        "walk", "walk_start", "walk_to_trot", "walk_stop", "backstep", "follow", "seek" -> .035f
                        "trot", "trot_to_walk", "trot_to_run", "run_to_trot" -> .07f
                        "run", "play_ball" -> .12f
                        else -> 0f
                    }
                    rigSpeed += (targetSpeed - rigSpeed).coerceIn(-.12f * delta, .12f * delta)
                }
            } else if (lastFrame != 0L) {
                var elapsed = delta
                while (elapsed > 0f) {
                    val step = min(elapsed, .05f)
                    behavior.update(step)
                    elapsed -= step
                }
            }
            lastFrame = now
            invalidate()
            postDelayed(this, if (hasRigAssets) { if ((rigPlayer?.requiredTickRate ?: 24) >= 24) 31L else 70L }
                else if (settings.reducedMotion || power.isPowerSaveMode || settings.lowPower) 100L
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
        interactionAge = 0f
        if (hasRigAssets) {
            brain.interact(when (intent) { "pet" -> "head"; "toy" -> "ball"; else -> intent })?.let { if (rigPlayer?.request(it) == false) brain.requestRejected(it) }
        } else behavior.react(if (intent == "nuzzle" || intent == "body") "cuddle" else intent)
        audio.play(if (intent == "jump" || intent == "toy" || intent == "ball") "ball" else if (intent == "pet" || intent == "cuddle") "breath" else "paw")
        resume()
    }
    fun pause() { running = false; removeCallbacks(tick); removeCallbacks(singleTap); rigPlayer?.setActive(false); lastFrame = 0 }
    fun resume() { if (isAttachedToWindow && visibility == VISIBLE && power.isInteractive) { running = true; rigPlayer?.setActive(true); removeCallbacks(tick); post(tick) } }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        attachedViews.removeAll { it.get() == null || it.get() === this }
        attachedViews.add(WeakReference(this))
        if (hasRigAssets && rigPlayer == null) rigPlayer = FrameClipPlayer(context)
        resume()
    }
    override fun onDetachedFromWindow() {
        pause(); audio.release(); rigPlayer?.close(); rigPlayer = null; rigFrame = null
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
            MotionEvent.ACTION_CANCEL -> { if (dragged) onDrag?.invoke(0f, 0f, true); parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); react("wave"); onTap?.invoke(); onAction?.invoke(settings.tapAction); return true }
    /** Shell dumpsys diagnostics contain counters only, never chats, reminders, names or credentials. */
    fun diagnosticSnapshot(): org.json.JSONObject {
        val cache = rigPlayer?.cacheStats()
        return org.json.JSONObject().apply {
            put("viewId", diagnosticId); put("scope", if (onDrag != null) "overlay" else "activity")
            put("renderMode", if (hasRigAssets) "skeletal_frames" else "legacy")
            put("running", running && isShown && power.isInteractive); put("visible", isShown)
            put("tickCount", tickCount); put("drawCount", drawCount)
            put("actualFrameChanges", if (hasRigAssets) actualFrameChanges else org.json.JSONObject.NULL)
            put("clipId", rigFrame?.clipId ?: org.json.JSONObject.NULL)
            put("clipFps", if (hasRigAssets) rigPlayer?.requiredTickRate ?: 0 else 0)
            put("loading", rigFrame?.loading ?: false)
            put("cacheBytes", cache?.allocatedBytes ?: 0L); put("cacheEntries", cache?.cachedFrames ?: 0)
            put("decodedFrames", cache?.decodedFrames ?: 0L); put("reusedBuffers", cache?.reusedBuffers ?: 0L)
            put("pendingFrames", cache?.pendingFrames ?: 0); put("activeDecoder", cache?.activeDecoder ?: false)
            put("uptimeMs", SystemClock.uptimeMillis())
        }
    }
    companion object {
        private var cachedAtlas = WeakReference<Bitmap>(null)
        private val attachedViews = mutableListOf<WeakReference<PetView>>()
        fun diagnosticSnapshots() = org.json.JSONArray().apply {
            attachedViews.mapNotNull { it.get() }.forEach { put(it.diagnosticSnapshot()) }
        }
    }
}
