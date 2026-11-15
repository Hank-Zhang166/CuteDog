package com.cutedog.app.pet

import android.content.Context
import android.graphics.*
import android.os.PowerManager
import android.os.SystemClock
import android.view.*
import java.lang.ref.WeakReference
import java.time.LocalTime
import kotlin.math.*

/** Small, cached texture renderer shared by the app and the bounded overlay window. */
class PetView(context: Context) : View(context) {
    private val settings = PetSettings(context)
    val behavior = PetBehavior()
    var onDrag: ((Float, Float, Boolean) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(23, 66, 45, 29) }
    private val source = Rect()
    private val dest = RectF()
    private val atlas = cachedAtlas.get() ?: context.assets.open("pet/samoyed.png").use {
        BitmapFactory.decodeStream(it).also { b -> cachedAtlas = WeakReference(b) }
    }
    private val walk = runCatching { context.assets.open("pet/walk.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    private var lastFrame = 0L
    private var running = false
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var downAt = 0L
    private var dragged = false
    private var stroked = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val power = context.getSystemService(PowerManager::class.java)
    private val tick = object : Runnable {
        override fun run() {
            if (!running || !isShown || !power.isInteractive) { lastFrame = 0; return }
            val now = SystemClock.uptimeMillis()
            behavior.quiet = settings.activity == "quiet" || LocalTime.now().hour in 0..7 || LocalTime.now().hour >= 22
            behavior.active = settings.activity == "lively"
            behavior.reducedMotion = settings.reducedMotion
            if (lastFrame != 0L) {
                var elapsed = ((now - lastFrame) / 1000f).coerceAtMost(.25f)
                while (elapsed > 0f) {
                    val step = min(elapsed, .05f)
                    behavior.update(step)
                    elapsed -= step
                }
            }
            lastFrame = now
            invalidate()
            postDelayed(this, if (settings.reducedMotion || power.isPowerSaveMode || settings.lowPower) 100L
                else if (behavior.state == PetBehavior.State.SLEEP) 83L else 33L)
        }
    }
    init {
        contentDescription = "${settings.name}，轻抚互动，长按拖动，点击打开应用"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
    fun react(intent: String) { behavior.react(intent); resume() }
    fun pause() { running = false; removeCallbacks(tick); lastFrame = 0 }
    fun resume() { if (isAttachedToWindow) { running = true; removeCallbacks(tick); post(tick) } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); resume() }
    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) resume() else pause()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat() * .91f
        val breath = if (settings.reducedMotion) 1f else 1f + sin(behavior.clock * 2.2f) * .007f
        val offset = if (onDrag == null && !settings.reducedMotion) (behavior.position - .5f) * width * .12f else 0f
        val x = width / 2f + offset
        val floor = height * .94f
        canvas.drawOval(x - side * .29f, floor - side * .035f, x + side * .29f, floor + side * .015f, shadow)
        canvas.save()
        canvas.translate(x, floor - behavior.jump * side * .4f)
        val facing = if (behavior.velocity > .001f) -1f else 1f
        canvas.scale(facing * behavior.depth, breath * behavior.depth)
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
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = hypot(event.rawX - downX, event.rawY - downY)
                if (distance > slop && SystemClock.uptimeMillis() - downAt >= 450 && !stroked && !settings.locked && onDrag != null) {
                    if (!dragged && settings.haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    dragged = true
                    onDrag?.invoke(event.rawX - lastX, event.rawY - lastY, false)
                } else if (!dragged && distance > slop && SystemClock.uptimeMillis() - downAt < 450) {
                    if (!stroked) { react(if (event.y < height * .6f) "pet" else "cuddle"); if (settings.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                    stroked = true
                }
                lastX = event.rawX; lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragged) onDrag?.invoke(0f, 0f, true) else if (!stroked) performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { if (dragged) onDrag?.invoke(0f, 0f, true); parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); react("wave"); onTap?.invoke(); return true }
    companion object { private var cachedAtlas = WeakReference<Bitmap>(null) }
}
