package com.cutedog.app.pet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.cutedog.app.PetChatActivity
import com.cutedog.app.voice.VoiceConversation
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** The floating menu owns the microphone and dismisses on the first outside tap. */
class PetMenuActivity : ComponentActivity() {
    private var sessionId = 0L
    private lateinit var speech: VoiceConversation
    private lateinit var mic: Button
    private lateinit var status: TextView
    private lateinit var stop: Button
    private lateinit var permissionSettings: Button
    private var holding = false
    private var permissionPending = false

    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (isFinishing || !::permissionSettings.isInitialized) return@registerForActivityResult
        permissionPending = false
        val blocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        permissionSettings.visibility = if (blocked) View.VISIBLE else View.GONE
        updateStatus(when {
            granted -> "已允许使用麦克风。请再次按住说话。"
            blocked -> "麦克风权限已关闭，可在应用权限设置中开启。"
            else -> "未开启麦克风。可以再次按住申请权限。"
        })
    }
    private val openSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isFinishing || !::permissionSettings.isInitialized) return@registerForActivityResult
        permissionPending = false
        if (hasMicrophonePermission()) {
            permissionSettings.visibility = View.GONE
            updateStatus("已允许使用麦克风。请再次按住说话。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getLongExtra(SESSION, 0)
        if (sessionId != PetInteractionMenu.session.value) { finish(); return }
        permissionPending = savedInstanceState?.getBoolean("permissionPending") == true
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setFinishOnTouchOutside(true)
        setContentView(menu())
        positionMenu()
        speech = VoiceConversation(this, ::updateStatus, onSetup = { openChat(voiceSetup = true) })
        lifecycleScope.launch {
            PetInteractionMenu.session.collect { if (it != sessionId && !isFinishing) finish() }
        }
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()

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
            button.setCompoundDrawablesWithIntrinsicBounds(com.cutedog.app.R.drawable.ic_pet_microphone, 0, 0, 0)
            button.setTypeface(button.typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun menu(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        panel.addView(TextView(this).apply {
            text = "和${PetSettings(this@PetMenuActivity).name}互动"
            textSize = 20f
            setTextColor(Color.rgb(61, 45, 36))
            setPadding(dp(4), dp(3), dp(4), dp(9))
        })
        mic = Button(this).apply {
            text = "按住说话"
            styleAction(this, primary = true)
            contentDescription = "麦克风，按住说话，松手发送，滑出取消"
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        view.isPressed = true
                        beginVoice()
                    }
                    MotionEvent.ACTION_MOVE -> if (holding &&
                        (event.x < 0f || event.y < 0f || event.x >= view.width || event.y >= view.height)) {
                        cancelHold()
                        view.isPressed = false
                        updateStatus("已取消录音。")
                    }
                    MotionEvent.ACTION_UP -> {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        view.isPressed = false
                        if (holding) {
                            if (event.x >= 0f && event.y >= 0f && event.x < view.width && event.y < view.height) submitVoice()
                            else { cancelHold(); updateStatus("已取消录音。") }
                        }
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        view.isPressed = false
                        cancelHold()
                    }
                }
                true
            }
            // Screen readers and keyboard users can start and finish a recording without holding a pointer.
            setOnClickListener { if (holding) submitVoice() else beginVoice() }
        }
        panel.addView(mic)
        status = TextView(this).apply {
            text = "松手发送，滑出取消"
            setTextColor(Color.rgb(94, 81, 70))
            textSize = 14f
            setPadding(dp(6), dp(3), dp(6), dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        panel.addView(status)
        permissionSettings = Button(this).apply {
            text = "打开麦克风权限设置"
            visibility = View.GONE
            setOnClickListener {
                permissionPending = true
                openSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        panel.addView(permissionSettings)
        stop = Button(this).apply {
            text = "停止播放"
            visibility = View.GONE
            setOnClickListener { speech.cancel(); visibility = View.GONE; updateStatus("已停止语音。") }
        }
        panel.addView(stop)
        for (pair in PetInteractionMenu.actions.chunked(2)) {
            val row = LinearLayout(this)
            for (action in pair) {
                row.addView(Button(this).apply {
                    text = action.label
                    styleAction(this)
                    setOnClickListener { choose(action.intent) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            panel.addView(row)
        }
        return ScrollView(this).apply {
            isClickable = true
            background = GradientDrawable().apply { setColor(Color.rgb(255, 249, 241)); cornerRadius = dp(20).toFloat() }
            clipToOutline = true
            addView(panel)
        }
    }

    private fun choose(intentId: String) {
        if (PetInteractionMenu.session.value != sessionId) { finish(); return }
        if (intentId == PetInteractionMenu.CHAT) {
            openChat()
        } else {
            PetInteractionMenu.select(sessionId, intentId)
        }
    }

    private fun openChat(voiceSetup: Boolean = false) {
        startActivity(Intent(this, PetChatActivity::class.java)
            .putExtra(PetChatActivity.VOICE_SETUP, voiceSetup)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun hasMicrophonePermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun beginVoice() {
        if (holding || permissionPending || !hasWindowFocus() || !::speech.isInitialized) return
        if (!hasMicrophonePermission()) {
            permissionPending = true
            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (stop.visibility == View.VISIBLE) speech.cancel()
        stop.visibility = View.GONE
        holding = speech.start()
        if (holding) {
            mic.text = "松手发送"
            mic.contentDescription = "结束并发送录音"
        }
    }

    private fun submitVoice() {
        holding = false
        resetMic()
        stop.visibility = View.VISIBLE
        speech.submit()
    }

    private fun cancelHold() {
        if (!holding) return
        holding = false
        resetMic()
        speech.cancel()
    }

    private fun resetMic() {
        mic.isPressed = false
        mic.text = "按住说话"
        mic.contentDescription = "麦克风，按住说话，松手发送，滑出取消"
    }

    private fun updateStatus(message: String) {
        if (!isDestroyed && ::status.isInitialized) status.text = message
    }

    private fun positionMenu() {
        val viewport = intent.getIntArrayExtra(VIEWPORT)
        val width = viewport?.getOrNull(0) ?: dp(resources.configuration.screenWidthDp)
        val height = viewport?.getOrNull(1) ?: dp(resources.configuration.screenHeightDp)
        val margin = dp(8)
        val safe = Rect(margin, margin, width - margin, height - margin)
        val dog = intent.getFloatArrayExtra(DOG_BOUNDS) ?: floatArrayOf(.4f, .5f, .6f, .75f)
        val anchor = Rect((dog[0] * width).toInt(), (dog[1] * height).toInt(),
            (dog[2] * width).toInt(), (dog[3] * height).toInt())
        val areas = listOf(
            Gravity.TOP to Rect(safe.left, safe.top, safe.right, min(safe.bottom, anchor.top - margin)),
            Gravity.BOTTOM to Rect(safe.left, maxOf(safe.top, anchor.bottom + margin), safe.right, safe.bottom),
            Gravity.LEFT to Rect(safe.left, safe.top, min(safe.right, anchor.left - margin), safe.bottom),
            Gravity.RIGHT to Rect(maxOf(safe.left, anchor.right + margin), safe.top, safe.right, safe.bottom),
        )
        val maxWidth = dp(284)
        val maxHeight = dp(332)
        val (side, area) = areas.filterNot { it.second.isEmpty }.maxByOrNull { (_, bounds) ->
            min(bounds.width(), maxWidth).toLong() * min(bounds.height(), maxHeight)
        } ?: (Gravity.TOP to safe)
        val w = min(area.width(), maxWidth).coerceAtLeast(1)
        val h = min(area.height(), maxHeight).coerceAtLeast(1)
        val centeredX = (anchor.centerX() - w / 2).coerceIn(area.left, maxOf(area.left, area.right - w))
        val centeredY = (anchor.centerY() - h / 2).coerceIn(area.top, maxOf(area.top, area.bottom - h))
        val left = when (side) { Gravity.LEFT -> area.right - w; Gravity.RIGHT -> area.left; else -> centeredX }
        val top = when (side) { Gravity.TOP -> area.bottom - h; Gravity.BOTTOM -> area.top; else -> centeredY }
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = left
            y = top
            this.width = w
            this.height = h
        }
        // Floating window offsets use the WindowManager's fitted frame. Its origin can
        // differ from the service's safe-area origin, particularly on older Android.
        if (android.os.Build.VERSION.SDK_INT < 30 && viewport?.size == 4) {
            window.decorView.doOnPreDraw { decor ->
                val actual = IntArray(2).also(decor::getLocationOnScreen)
                val dx = left + viewport[2] - actual[0]
                val dy = top + viewport[3] - actual[1]
                if (dx != 0 || dy != 0) {
                    window.attributes = window.attributes.apply { x += dx; y += dy }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { cancelHold(); if (::mic.isInitialized) mic.isPressed = false }
    }
    override fun onStop() {
        cancelHold()
        if (::speech.isInitialized) speech.cancel()
        if (!permissionPending && !isChangingConfigurations && !isFinishing) finish()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permissionPending", permissionPending)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (::speech.isInitialized) speech.close()
        if (!isChangingConfigurations) PetInteractionMenu.close(sessionId)
        super.onDestroy()
    }
    companion object {
        internal const val SESSION = "session"
        internal const val DOG_BOUNDS = "dog_bounds"
        internal const val VIEWPORT = "viewport"
    }
}
