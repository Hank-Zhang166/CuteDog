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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.cutedog.app.PetChatActivity
import com.cutedog.app.voice.VoiceConversation
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** The visible menu owns the microphone. Its transparent backdrop dismisses on the first outside tap. */
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
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val surface = FrameLayout(this)
        val backdrop = View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { finish() }
        }
        surface.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        val panel = menu()
        surface.addView(panel, FrameLayout.LayoutParams(1, 1))
        ViewCompat.setOnApplyWindowInsetsListener(surface) { root, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            positionMenu(surface, panel)
            insets
        }
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionMenu(surface, panel) }
        setContentView(surface)
        speech = VoiceConversation(this, ::updateStatus)
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
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "和${PetSettings(this@PetMenuActivity).name}互动"
            textSize = 20f
            setTextColor(Color.rgb(61, 45, 36))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "取消"
            setOnClickListener { finish() }
        })
        panel.addView(header)
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
            PetInteractionMenu.close(sessionId)
            finish()
            startActivity(Intent(this, PetChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } else {
            PetInteractionMenu.select(sessionId, intentId)
            finish()
        }
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

    private fun positionMenu(surface: FrameLayout, panel: View) {
        val width = surface.width - surface.paddingLeft - surface.paddingRight
        val height = surface.height - surface.paddingTop - surface.paddingBottom
        if (width <= 0 || height <= 0) return
        val margin = dp(6)
        val safe = Rect(margin, margin, width - margin, height - margin)
        val dog = intent.getFloatArrayExtra(DOG_BOUNDS) ?: floatArrayOf(.4f, .5f, .6f, .75f)
        val areas = listOf(
            Rect(safe.left, safe.top, safe.right, (dog[1] * height).toInt() - margin),
            Rect(safe.left, (dog[3] * height).toInt() + margin, safe.right, safe.bottom),
            Rect(safe.left, safe.top, (dog[0] * width).toInt() - margin, safe.bottom),
            Rect((dog[2] * width).toInt() + margin, safe.top, safe.right, safe.bottom),
        )
        val area = areas.filter { it.width() >= dp(175) && it.height() >= dp(152) }.maxByOrNull {
            min(it.width(), dp(320)).toLong() * min(it.height(), dp(446))
        } ?: safe
        val w = min(area.width(), dp(320)).coerceAtLeast(1)
        val h = min(area.height(), dp(446)).coerceAtLeast(1)
        val left = area.left + (area.width() - w) / 2
        val top = area.top + (area.height() - h) / 2
        val current = panel.layoutParams as FrameLayout.LayoutParams
        if (current.width != w || current.height != h || current.leftMargin != left || current.topMargin != top) {
            panel.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = left
                topMargin = top
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
    }
}
