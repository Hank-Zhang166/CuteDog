package com.cutedog.app.pet

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.cutedog.app.MainActivity
import kotlin.math.*

/** Only the pet's small rectangle and a temporary bubble receive touches. */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var pet: PetView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: PetSettings
    private val main = Handler(Looper.getMainLooper())
    private var attached = false
    private var hidden = false
    private var bubble: TextView? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var insetTop = 0
    private var insetBottom = 0
    private var floatingX = 0f
    private var seenPosition = .5f
    private var dragging = false
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "x" && key != "y") main.post { configure(); if (attached) safelyUpdate() }
    }
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { pet.pause(); pet.visibility = View.GONE; dismissBubble(); main.removeCallbacks(motion) }
                Intent.ACTION_USER_PRESENT -> if (!hidden) { pet.visibility = View.VISIBLE; pet.resume(); main.removeCallbacks(motion); main.post(motion) }
                Intent.ACTION_SCREEN_ON -> if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked && !hidden) { pet.visibility = View.VISIBLE; pet.resume(); main.removeCallbacks(motion); main.post(motion) }
                "com.cutedog.app.REMINDER_EVENT" -> if (!hidden && getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                    pet.react("wave"); showBubble(intent.getStringExtra("message") ?: "有一件事等着你", intent.getLongExtra("reminder_id", 0))
                }
            }
        }
    }
    private val motion = object : Runnable {
        override fun run() {
            if (attached && !hidden && pet.isShown && !dragging && !prefs.locked && !prefs.reducedMotion) {
                val delta = pet.behavior.position - seenPosition
                floatingX = (floatingX + delta * (screenWidth - params.width)).coerceIn(0f, max(0, screenWidth - params.width).toFloat())
                if (abs(params.x - floatingX) >= 1f) { params.x = floatingX.toInt(); safelyUpdate() }
            }
            seenPosition = pet.behavior.position
            if (!hidden && getSystemService(PowerManager::class.java).isInteractive) main.postDelayed(this, if (prefs.locked || prefs.reducedMotion) 1000 else 50)
        }
    }
    override fun onCreate() {
        super.onCreate()
        prefs = PetSettings(this); wm = getSystemService(WindowManager::class.java)
        pet = PetView(this)
        params = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        configure()
        pet.onTap = { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        pet.onDrag = { dx, dy, finished ->
            dragging = !finished
            params.x = (params.x + dx.toInt()).coerceIn(0, max(0, screenWidth - params.width))
            params.y = (params.y + dy.toInt()).coerceIn(insetTop, max(insetTop, screenHeight - insetBottom - params.height))
            floatingX = params.x.toFloat()
            dismissBubble()
            if (finished) snapToEdge() else safelyUpdate()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            addAction("com.cutedog.app.REMINDER_EVENT")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(events, filter)
        getSharedPreferences("pet", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener)
        main.post(motion)
    }
    private fun configure() {
        val density = resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.maximumWindowMetrics
            screenWidth = metrics.bounds.width(); screenHeight = metrics.bounds.height()
            val safe = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = safe.top; insetBottom = safe.bottom
        } else {
            @Suppress("DEPRECATION") val display = wm.defaultDisplay
            val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION") display.getRealMetrics(m)
            screenWidth = m.widthPixels; screenHeight = m.heightPixels
            insetTop = (32 * density).toInt(); insetBottom = (48 * density).toInt()
        }
        params.width = (prefs.size * density).toInt().coerceAtMost(screenWidth)
        params.height = params.width
        params.alpha = prefs.opacity
        params.x = ((screenWidth - params.width).coerceAtLeast(0) * prefs.xFraction).toInt()
        params.y = insetTop + ((screenHeight - insetTop - insetBottom - params.height).coerceAtLeast(0) * prefs.yFraction).toInt()
        floatingX = params.x.toFloat()
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); dismissBubble(); configure(); safelyUpdate() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "桌宠运行状态", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        val open = PendingIntent.getActivity(this, 200, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 201, Intent(this, OverlayService::class.java).setAction(TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 202, Intent(this, OverlayService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(com.cutedog.app.R.drawable.ic_notification)
            .setContentTitle("${prefs.name}在身边").setContentText("轻抚互动 · 长按拖动 · 点击打开应用")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "显示 / 隐藏", toggle).build())
            .addAction(Notification.Action.Builder(null, "结束陪伴", close).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(200, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(200, notification)
        hidden = when (intent?.action) { HIDE -> true; TOGGLE -> !hidden; else -> false }
        if (!attached) {
            try { wm.addView(pet, params); attached = true } catch (_: SecurityException) { stopSelf(); return START_NOT_STICKY }
            catch (_: WindowManager.BadTokenException) { stopSelf(); return START_NOT_STICKY }
            catch (_: WindowManager.InvalidDisplayException) { stopSelf(); return START_NOT_STICKY }
        }
        pet.visibility = if (hidden || getSystemService(KeyguardManager::class.java).isKeyguardLocked) View.GONE else View.VISIBLE
        main.removeCallbacks(motion)
        if (hidden) { pet.pause(); dismissBubble() } else { pet.resume(); main.post(motion) }
        return START_NOT_STICKY // The user starts again; no endless restart after revoking permission.
    }
    private fun safelyUpdate() {
        if (!attached) return
        try { wm.updateViewLayout(pet, params) } catch (_: SecurityException) { stopSelf() } catch (_: IllegalArgumentException) { attached = false; stopSelf() }
    }
    private fun snapToEdge() {
        val start = params.x; val end = if (start + params.width / 2 < screenWidth / 2) 0 else screenWidth - params.width
        val began = SystemClock.uptimeMillis()
        val task = object : Runnable {
            override fun run() {
                if (!attached || dragging) return
                val t = ((SystemClock.uptimeMillis() - began) / 260f).coerceIn(0f, 1f)
                params.x = (start + (end - start) * (1 - (1 - t).pow(3))).toInt(); floatingX = params.x.toFloat(); safelyUpdate()
                if (t < 1) main.postDelayed(this, 16) else savePosition()
            }
        }
        main.post(task)
    }
    private fun savePosition() {
        prefs.xFraction = params.x.toFloat() / max(1, screenWidth - params.width)
        prefs.yFraction = (params.y - insetTop).toFloat() / max(1, screenHeight - insetTop - insetBottom - params.height)
    }
    private fun showBubble(message: String, reminderId: Long) {
        dismissBubble()
        val dp = resources.displayMetrics.density
        val text = TextView(this).apply {
            this.text = message; textSize = 14f; setTextColor(Color.rgb(41, 57, 47)); maxLines = 5
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { setColor(Color.argb(248, 255, 251, 242)); cornerRadius = 20 * dp; setStroke((1 * dp).toInt(), Color.rgb(221, 228, 214)) }
            elevation = 4 * dp
            setOnClickListener { startActivity(Intent(this@OverlayService, MainActivity::class.java).putExtra("reminder_id", reminderId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); dismissBubble() }
            contentDescription = "提醒：$message，点击查看"
        }
        val p = WindowManager.LayoutParams(min((260 * dp).toInt(), screenWidth - (16 * dp).toInt()), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = params.x.coerceIn(0, max(0, screenWidth - width))
            y = max(insetTop, params.y - (130 * dp).toInt())
        }
        try { wm.addView(text, p); bubble = text; main.postDelayed({ dismissBubble() }, 12_000) } catch (_: SecurityException) { stopSelf() }
    }
    private fun dismissBubble() { bubble?.let { runCatching { wm.removeView(it) } }; bubble = null }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null); dismissBubble(); pet.pause()
        if (attached) { savePosition(); runCatching { wm.removeView(pet) }; attached = false }
        unregisterReceiver(events)
        getSharedPreferences("pet", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(settingsListener)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "pet_status"
        private const val STOP = "pet.stop"; private const val HIDE = "pet.hide"; private const val TOGGLE = "pet.toggle"
        fun start(context: Context) { context.startForegroundService(Intent(context, OverlayService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, OverlayService::class.java)) }
        fun hide(context: Context) { context.startService(Intent(context, OverlayService::class.java).setAction(HIDE)) }
        fun show(context: Context) = start(context)
    }
}
