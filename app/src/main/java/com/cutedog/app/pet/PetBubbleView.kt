package com.cutedog.app.pet

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.util.Size
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import kotlin.math.min

/**
 * Content for a small TYPE_APPLICATION_OVERLAY window. The service owns positioning,
 * permission checks, lock-screen visibility, window flags and action handling.
 * No reminder/message text is written to logs or persistent storage here.
 */
class PetBubbleView(context: Context) : FrameLayout(context) {
    enum class TailEdge { TOP, BOTTOM, LEFT, RIGHT }
    var onComplete: (() -> Unit)? = null
    private var tailEdge = TailEdge.BOTTOM
    private var tailOffset = Float.NaN
    private val bodyBounds = RectF()
    private val backgroundPath = Path()
    private val pointerPath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
    }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val eyebrow = TextView(context).apply {
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val check = CheckBox(context).apply {
        tag = "pet_bubble_complete"
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        minHeight = dp(48f)
        minimumHeight = dp(48f)
        setPadding(dp(4f), 0, 0, 0)
        isSoundEffectsEnabled = false
        setOnClickListener { isChecked = true; onComplete?.invoke() }
    }
    init {
        setWillNotDraw(false)
        // The bounded, transient bubble redraws only when content/layout changes.
        // A software shadow here does not add a full-screen GPU layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        clipChildren = false
        clipToPadding = false
        content.addView(eyebrow)
        content.addView(check, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        applyPadding()
        applyTheme()
    }

    fun showReminder(title: String, time: String) {
        eyebrow.text = "待办提醒 · $time"
        check.text = title
        check.contentDescription = "完成本次提醒，$title"
        check.isChecked = false
        scroll.scrollTo(0, 0)
    }

    /** Offset is in this view's local pixels along the selected edge, not a screen coordinate. */
    fun setTail(edge: TailEdge, offsetPx: Float = Float.NaN) {
        val edgeChanged = tailEdge != edge
        val offset = offsetPx.takeIf(Float::isFinite) ?: Float.NaN
        if (!edgeChanged && (tailOffset == offset || (tailOffset.isNaN() && offset.isNaN()))) return
        tailEdge = edge
        tailOffset = offset
        // Following a completed pet step moves only the pointer; the completion control stays in place.
        if (edgeChanged) { applyPadding(); requestLayout() }
        invalidate()
    }

    /** Call after setting the preferred edge; all content remains scrollable if height is limited. */
    fun measureForBounds(maxWidthPx: Int, maxHeightPx: Int): Size {
        require(maxWidthPx > 0 && maxHeightPx > 0) { "Bubble bounds must be positive" }
        val targetWidth = min(dp(272f), maxWidthPx)
        measure(MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
        return Size(measuredWidth, measuredHeight)
    }

    /** Actual clamped tip in local coordinates; useful when aligning it to the pet after measurement. */
    fun tailTip(): PointF {
        updateOutline(if (measuredWidth > 0) measuredWidth else width, if (measuredHeight > 0) measuredHeight else height)
        val extent = dp(9f).toFloat()
        return when (tailEdge) {
            TailEdge.TOP -> PointF(clampedOffset(true), bodyBounds.top - extent)
            TailEdge.BOTTOM -> PointF(clampedOffset(true), bodyBounds.bottom + extent)
            TailEdge.LEFT -> PointF(bodyBounds.left - extent, clampedOffset(false))
            TailEdge.RIGHT -> PointF(bodyBounds.right + extent, clampedOffset(false))
        }
    }

    private fun dark() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun applyTheme() {
        fillPaint.color = if (dark()) Color.rgb(32, 43, 35) else Color.rgb(255, 252, 243)
        fillPaint.setShadowLayer(dp(7f).toFloat(), 0f, dp(2f).toFloat(), if (dark()) Color.argb(90, 0, 0, 0) else Color.argb(30, 47, 56, 37))
        borderPaint.strokeWidth = resources.displayMetrics.density
        borderPaint.color = if (dark()) Color.rgb(82, 98, 82) else Color.rgb(219, 227, 210)
        val accent = if (dark()) Color.rgb(235, 199, 151) else Color.rgb(132, 85, 43)
        eyebrow.setTextColor(accent)
        check.buttonTintList = ColorStateList.valueOf(accent)
        check.setTextColor(if (dark()) Color.rgb(239, 242, 232) else Color.rgb(42, 57, 45))
    }

    private fun applyPadding() {
        val shadow = dp(10f); val horizontal = dp(14f); val vertical = dp(10f); val tail = dp(9f)
        setPadding(shadow + horizontal + if (tailEdge == TailEdge.LEFT) tail else 0,
            shadow + vertical + if (tailEdge == TailEdge.TOP) tail else 0,
            shadow + horizontal + if (tailEdge == TailEdge.RIGHT) tail else 0,
            shadow + vertical + if (tailEdge == TailEdge.BOTTOM) tail else 0)
    }

    private fun updateOutline(w: Int, h: Int) {
        val shadow = dp(10f).toFloat(); val tail = dp(9f).toFloat()
        bodyBounds.set(shadow + if (tailEdge == TailEdge.LEFT) tail else 0f,
            shadow + if (tailEdge == TailEdge.TOP) tail else 0f,
            w - shadow - if (tailEdge == TailEdge.RIGHT) tail else 0f,
            h - shadow - if (tailEdge == TailEdge.BOTTOM) tail else 0f)
    }

    private fun clampedOffset(horizontal: Boolean): Float {
        val start = if (horizontal) bodyBounds.left else bodyBounds.top
        val end = if (horizontal) bodyBounds.right else bodyBounds.bottom
        val margin = min(dp(26f).toFloat(), ((end - start) / 2).coerceAtLeast(0f))
        return (tailOffset.takeIf(Float::isFinite) ?: ((start + end) / 2)).coerceIn(start + margin, (end - margin).coerceAtLeast(start + margin))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateOutline(width, height)
        if (bodyBounds.width() <= 0 || bodyBounds.height() <= 0) return
        val radius = min(dp(18f).toFloat(), min(bodyBounds.width(), bodyBounds.height()) / 2)
        backgroundPath.reset()
        backgroundPath.addRoundRect(bodyBounds, radius, radius, Path.Direction.CW)
        pointerPath.reset()
        val half = dp(9f).toFloat(); val overlap = resources.displayMetrics.density
        when (tailEdge) {
            TailEdge.TOP -> { val x = clampedOffset(true); pointerPath.moveTo(x - half, bodyBounds.top + overlap); pointerPath.lineTo(x, bodyBounds.top - half); pointerPath.lineTo(x + half, bodyBounds.top + overlap) }
            TailEdge.BOTTOM -> { val x = clampedOffset(true); pointerPath.moveTo(x - half, bodyBounds.bottom - overlap); pointerPath.lineTo(x, bodyBounds.bottom + half); pointerPath.lineTo(x + half, bodyBounds.bottom - overlap) }
            TailEdge.LEFT -> { val y = clampedOffset(false); pointerPath.moveTo(bodyBounds.left + overlap, y - half); pointerPath.lineTo(bodyBounds.left - half, y); pointerPath.lineTo(bodyBounds.left + overlap, y + half) }
            TailEdge.RIGHT -> { val y = clampedOffset(false); pointerPath.moveTo(bodyBounds.right - overlap, y - half); pointerPath.lineTo(bodyBounds.right + half, y); pointerPath.lineTo(bodyBounds.right - overlap, y + half) }
        }
        pointerPath.close()
        backgroundPath.op(pointerPath, Path.Op.UNION)
        canvas.drawPath(backgroundPath, fillPaint)
        canvas.drawPath(backgroundPath, borderPaint)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPadding(); applyTheme(); requestLayout(); invalidate()
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()

    fun recommendedTimeout(): Long {
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        return if (android.os.Build.VERSION.SDK_INT >= 29) manager.getRecommendedTimeoutMillis(15_000,
            android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS).toLong() else 15_000L
    }
}
