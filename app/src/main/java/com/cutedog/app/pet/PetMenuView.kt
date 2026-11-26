package com.cutedog.app.pet

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cutedog.app.R
import com.cutedog.app.voice.OverlayVoiceController
import kotlin.math.roundToInt

internal class PetMenuView(
    context: Context,
    name: String,
    onAction: (String) -> Unit,
    voiceReady: () -> Boolean,
) : ScrollView(context) {
    private val mic = Button(context)
    private val status = TextView(context)
    private val stop = Button(context)
    private var holding = false
    private val voice = OverlayVoiceController(context, onStatus = { status.text = it },
        voiceReady = voiceReady, onTurnEnd = {
            holding = false
            resetMic()
            stop.visibility = View.GONE
        })

    init {
        isClickable = true
        background = GradientDrawable().apply {
            setColor(Color.rgb(255, 249, 241))
            cornerRadius = dp(20).toFloat()
        }
        clipToOutline = true
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        panel.addView(TextView(context).apply {
            text = "和${name}互动"
            textSize = 20f
            setTextColor(Color.rgb(61, 45, 36))
            setPadding(dp(4), dp(3), dp(4), dp(9))
        })
        mic.apply {
            styleAction(this, primary = true)
            setOnClickListener { if (holding) releaseMic(true) else pressMic() }
            setOnTouchListener { view, event ->
                val inside = event.x in 0f..<view.width.toFloat() && event.y in 0f..<view.height.toFloat()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        pressMic()
                    }
                    MotionEvent.ACTION_MOVE -> if (!inside) releaseMic(false)
                    MotionEvent.ACTION_UP -> {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        releaseMic(inside)
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        releaseMic(false)
                    }
                }
                true
            }
        }
        resetMic()
        panel.addView(mic)
        status.apply {
            text = "松手发送，滑出取消"
            setTextColor(Color.rgb(94, 81, 70))
            textSize = 14f
            setPadding(dp(6), dp(3), dp(6), dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        panel.addView(status)
        stop.apply {
            text = "停止播放"
            visibility = View.GONE
            styleAction(this)
            setOnClickListener { stopVoice(); status.text = "已停止语音。" }
        }
        panel.addView(stop)
        for (pair in PetInteractionMenu.actions.chunked(2)) {
            val row = LinearLayout(context)
            for (action in pair) {
                row.addView(Button(context).apply {
                    text = action.label
                    styleAction(this)
                    setOnClickListener { onAction(action.intent) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
            }
            if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            panel.addView(row)
        }
        addView(panel)
    }

    fun stopVoice() {
        holding = false
        resetMic()
        stop.visibility = View.GONE
        voice.cancel()
    }

    override fun onDetachedFromWindow() {
        voice.close()
        super.onDetachedFromWindow()
    }

    private fun pressMic() {
        if (holding) return
        stop.visibility = View.GONE
        holding = voice.start()
        if (holding) {
            mic.isPressed = true
            mic.text = "松手发送"
            mic.contentDescription = "结束并发送录音"
        }
    }

    private fun releaseMic(submit: Boolean) {
        if (!holding) return
        holding = false
        resetMic()
        if (submit) {
            stop.visibility = View.VISIBLE
            voice.submit()
        } else {
            voice.cancel()
            status.text = "已取消录音。"
        }
    }

    private fun resetMic() {
        mic.isPressed = false
        mic.text = "按住说话"
        mic.contentDescription = "麦克风，按住说话，松手发送，滑出取消"
    }

    private fun styleAction(button: Button, primary: Boolean = false) {
        val fill = if (primary) 0xffffcfad.toInt() else 0xffffeddc.toInt()
        val ink = if (primary) 0xff542d22.toInt() else 0xff4f382c.toInt()
        val shape = GradientDrawable().apply { setColor(fill); cornerRadius = dp(16).toFloat() }
        button.backgroundTintList = null
        button.background = RippleDrawable(ColorStateList.valueOf(0x417f4934), shape, null)
        button.setTextColor(ink)
        button.minHeight = dp(48)
        button.isAllCaps = false
        button.setPadding(dp(10), dp(4), dp(10), dp(4))
        if (primary) {
            button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pet_microphone, 0, 0, 0)
            button.setTypeface(button.typeface, Typeface.BOLD)
        }
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()

    fun place(viewport: Rect, dog: Rect): Rect? {
        val margin = dp(8)
        val safe = Rect(viewport).apply { inset(margin, margin) }
        val areas = listOf(
            Gravity.TOP to Rect(safe.left, safe.top, safe.right, minOf(safe.bottom, dog.top - margin)),
            Gravity.BOTTOM to Rect(safe.left, maxOf(safe.top, dog.bottom + margin), safe.right, safe.bottom),
            Gravity.LEFT to Rect(safe.left, safe.top, minOf(safe.right, dog.left - margin), safe.bottom),
            Gravity.RIGHT to Rect(maxOf(safe.left, dog.right + margin), safe.top, safe.right, safe.bottom),
        )
        val widthLimit = dp(284)
        val heightLimit = dp(332)
        val (side, area) = areas.filter { (_, r) -> r.width() >= dp(144) && r.height() >= dp(108) }
            .maxByOrNull { (_, r) ->
                minOf(r.width(), widthLimit).toLong() * minOf(r.height(), heightLimit)
            } ?: return null
        val width = minOf(area.width(), widthLimit)
        val height = minOf(area.height(), heightLimit)
        val left = when (side) {
            Gravity.LEFT -> area.right - width
            Gravity.RIGHT -> area.left
            else -> (dog.centerX() - width / 2).coerceIn(area.left, area.right - width)
        }
        val top = when (side) {
            Gravity.TOP -> area.bottom - height
            Gravity.BOTTOM -> area.top
            else -> (dog.centerY() - height / 2).coerceIn(area.top, area.bottom - height)
        }
        return Rect(left, top, left + width, top + height)
    }
}
