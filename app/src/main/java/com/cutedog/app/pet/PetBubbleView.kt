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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.Button
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
    private enum class Kind { MENU, REMINDER }
    var onAction: ((String) -> Unit)? = null

    private var kind = Kind.MENU
    private var tailEdge = TailEdge.BOTTOM
    private var tailOffset = Float.NaN
    private var actionColumns = 2
    private var boundTitle = ""
    private var boundMessage = ""
    private var boundName = ""
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
        setPadding(0, 0, 0, dp(7f))
    }
    private val heading = TextView(context).apply {
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        ViewCompat.setAccessibilityHeading(this, true)
        setPadding(0, 0, 0, dp(6f))
    }
    private val message = TextView(context).apply {
        textSize = 14f
        setLineSpacing(dp(3f).toFloat(), 1f)
        maxLines = 7
        ellipsize = TextUtils.TruncateAt.END
    }
    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12f), 0, 0)
    }

    init {
        setWillNotDraw(false)
        // The bounded, transient bubble redraws only when content/layout changes.
        // A software shadow here does not add a full-screen GPU layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        clipChildren = false
        clipToPadding = false
        content.addView(eyebrow)
        content.addView(heading)
        content.addView(message)
        content.addView(actions)
        scroll.addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        applyPadding()
        applyTheme()
    }

    fun showMenu(petName: String, message: String = "我在这里，想一起做点什么？") {
        kind = Kind.MENU
        boundName = petName.trim().ifBlank { "小狗" }
        boundTitle = "$boundName 在身边"
        boundMessage = message
        bind()
    }

    fun showReminder(title: String, message: String = "") {
        kind = Kind.REMINDER
        boundName = ""
        boundTitle = title.ifBlank { "有一件事等着你" }
        boundMessage = message
        bind()
    }

    /** Offset is in this view's local pixels along the selected edge, not a screen coordinate. */
    fun setTail(edge: TailEdge, offsetPx: Float = Float.NaN) {
        val edgeChanged = tailEdge != edge
        val offset = offsetPx.takeIf(Float::isFinite) ?: Float.NaN
        if (!edgeChanged && (tailOffset == offset || (tailOffset.isNaN() && offset.isNaN()))) return
        tailEdge = edge
        tailOffset = offset
        // Following a completed pet step moves only the pointer; menu buttons stay in place.
        if (edgeChanged) { applyPadding(); requestLayout() }
        invalidate()
    }

    /** Call after setting the preferred edge; all content remains scrollable if height is limited. */
    fun measureForBounds(maxWidthPx: Int, maxHeightPx: Int): Size {
        require(maxWidthPx > 0 && maxHeightPx > 0) { "Bubble bounds must be positive" }
        val targetWidth = min(dp(304f), maxWidthPx)
        val columns = if (targetWidth < dp(260f) || resources.configuration.fontScale >= 1.35f) 1 else 2
        if (columns != actionColumns) { actionColumns = columns; bindActions() }
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

    private fun bind() {
        eyebrow.text = if (kind == Kind.REMINDER) "事项提醒" else "小小陪伴"
        heading.text = boundTitle
        message.text = boundMessage
        message.visibility = if (boundMessage.isBlank()) GONE else VISIBLE
        applyTheme()
        bindActions()
        scroll.scrollTo(0, 0)
        requestLayout()
        invalidate()
    }

    private fun bindActions() {
        actions.removeAllViews()
        val items = if (kind == Kind.REMINDER) listOf(ACTION_DETAILS to "查看事项", ACTION_OPEN to "打开应用")
            else listOf(ACTION_CHAT to "聊一会儿", ACTION_PET to "摸摸小狗", ACTION_OPEN to "打开应用", ACTION_HIDE to "临时隐藏")
        items.chunked(actionColumns).forEachIndexed { rowIndex, rowItems ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { index, (action, title) ->
                val primary = action == ACTION_DETAILS || action == ACTION_CHAT
                val button = Button(context).apply {
                    text = title
                    textSize = 14f
                    isAllCaps = false
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = dp(48f)
                    minimumHeight = dp(48f)
                    maxLines = 2
                    setPadding(dp(8f), dp(9f), dp(8f), dp(9f))
                    setTextColor(buttonText(primary))
                    isSoundEffectsEnabled = false
                    background = buttonBackground(primary)
                    contentDescription = title
                    tag = "pet_bubble_$action"
                    setOnClickListener { onAction?.invoke(action) }
                }
                row.addView(button, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = dp(8f) })
            }
            actions.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (rowIndex > 0) topMargin = dp(8f) })
        }
    }

    private fun dark() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun accent() = if (kind == Kind.REMINDER) { if (dark()) Color.rgb(235, 199, 151) else Color.rgb(132, 85, 43) }
        else if (dark()) Color.rgb(163, 208, 181) else Color.rgb(55, 106, 88)
    private fun buttonText(primary: Boolean) = if (primary) { if (dark()) Color.rgb(29, 51, 39) else Color.WHITE }
        else if (dark()) Color.rgb(227, 235, 225) else Color.rgb(49, 77, 59)
    private fun buttonBackground(primary: Boolean): RippleDrawable {
        val fill = if (primary) accent() else if (dark()) Color.rgb(47, 61, 50) else Color.rgb(234, 239, 228)
        val shape = GradientDrawable().apply { setColor(fill); cornerRadius = dp(13f).toFloat() }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(13f).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(if (dark()) Color.argb(35, 255, 255, 255) else Color.argb(25, 20, 50, 35)), shape, mask)
    }

    private fun applyTheme() {
        fillPaint.color = if (dark()) Color.rgb(32, 43, 35) else Color.rgb(255, 252, 243)
        fillPaint.setShadowLayer(dp(7f).toFloat(), 0f, dp(2f).toFloat(), if (dark()) Color.argb(90, 0, 0, 0) else Color.argb(30, 47, 56, 37))
        borderPaint.strokeWidth = resources.displayMetrics.density
        borderPaint.color = if (dark()) Color.rgb(82, 98, 82) else Color.rgb(219, 227, 210)
        eyebrow.setTextColor(accent())
        heading.setTextColor(if (dark()) Color.rgb(239, 242, 232) else Color.rgb(42, 57, 45))
        message.setTextColor(if (dark()) Color.rgb(203, 212, 199) else Color.rgb(84, 97, 79))
    }

    private fun applyPadding() {
        val shadow = dp(10f); val inside = dp(16f); val tail = dp(9f)
        setPadding(shadow + inside + if (tailEdge == TailEdge.LEFT) tail else 0,
            shadow + inside + if (tailEdge == TailEdge.TOP) tail else 0,
            shadow + inside + if (tailEdge == TailEdge.RIGHT) tail else 0,
            shadow + inside + if (tailEdge == TailEdge.BOTTOM) tail else 0)
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
        val radius = min(dp(22f).toFloat(), min(bodyBounds.width(), bodyBounds.height()) / 2)
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
        applyPadding(); applyTheme(); bindActions(); requestLayout(); invalidate()
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()

    companion object {
        const val ACTION_CHAT = "chat"
        const val ACTION_PET = "pet"
        const val ACTION_OPEN = "open"
        const val ACTION_HIDE = "hide"
        const val ACTION_DETAILS = "details"
    }
}
