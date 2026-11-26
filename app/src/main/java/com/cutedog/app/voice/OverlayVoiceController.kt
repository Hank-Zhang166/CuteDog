package com.cutedog.app.voice

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.cutedog.app.BuildConfig
import com.cutedog.app.voice.doubao.DoubaoRealtimeClient
import com.cutedog.app.voice.doubao.DoubaoSettings

/**
 * The panel and its pointer gesture belong to the overlay. Only an explicit
 * microphone press requests a visible Activity for Android's while-in-use permission.
 */
internal class OverlayVoiceController(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onSetup: () -> Unit,
    private val onTurnEnd: () -> Unit = {},
    private val onAuthorizationVisible: (Boolean) -> Unit = {},
    private val realtimeClient: DoubaoRealtimeClient = DoubaoRealtimeClient(),
) {
    private var turn: OverlayMicTurn? = null

    fun start(): Boolean {
        cancel()
        val settings = DoubaoSettings(context)
        if (!settings.hasVoiceKey && !BuildConfig.LEGACY_SPEECH) {
            onSetup()
            return false
        }
        val authorizeOnly = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED || settings.hasVoiceKey && !settings.microphoneUploadAccepted
        val next = OverlayMicTurn(authorizeOnly, onStatus, onSetup, {
            if (authorizeOnly) onAuthorizationVisible(false)
            onTurnEnd()
        }, realtimeClient)
        OverlayMicTurn.active?.finish()
        OverlayMicTurn.active = next
        turn = next
        if (authorizeOnly) onAuthorizationVisible(true)
        else onStatus("正在打开麦克风…")
        val launched = try {
            context.startActivity(Intent(context, OverlayMicrophoneActivity::class.java)
                .putExtra(OverlayMicrophoneActivity.TURN_ID, next.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!launched) {
            next.finish("无法打开麦克风，请在陪伴页试一次。")
            return false
        }
        return !authorizeOnly
    }

    fun submit() {
        val request = turn ?: return
        if (request.finished || request.authorizeOnly) return
        val recorder = request.conversation
        if (recorder == null) {
            // The press may end before Android delivers the foreground lease.
            request.finish("请按住麦克风，等小狗开始听再说话。")
        } else recorder.submit()
    }

    fun cancel() {
        turn?.finish()
        turn = null
    }

    fun close() = cancel()
}

/** Main-thread handoff. Intent data can only select the current, still-held press. */
private class OverlayMicTurn(
    val authorizeOnly: Boolean,
    val status: (String) -> Unit,
    val setup: () -> Unit,
    private val onEnd: () -> Unit,
    val realtimeClient: DoubaoRealtimeClient,
) {
    val id = ++sequence
    var activity: OverlayMicrophoneActivity? = null
    var conversation: VoiceConversation? = null
    var finished = false
        private set

    fun finish(message: String? = null) {
        if (finished) return
        finished = true
        if (active === this) active = null
        conversation?.close()
        conversation = null
        activity?.dismissTurn()
        activity = null
        if (message != null) status(message)
        onEnd()
    }

    companion object {
        private var sequence = 0L
        var active: OverlayMicTurn? = null
    }
}

/** The gesture remains with the overlay while this window makes recording eligible. */
class OverlayMicrophoneActivity : ComponentActivity() {
    private var turn: OverlayMicTurn? = null
    private var permissionPending = false
    private var resumed = false
    private var authorization: AlertDialog? = null

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        val request = turn ?: return@registerForActivityResult
        if (granted) authorize(request)
        else request.finish("麦克风未授权。可在安卓应用权限设置中允许录音。")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = OverlayMicTurn.active
        if (request == null || request.id != intent.getLongExtra(TURN_ID, -1L) || request.activity != null) {
            finish()
            return
        }
        turn = request
        request.activity = this
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        setContentView(View(this))
        window.setGravity(Gravity.TOP or Gravity.START)
        window.setLayout(1, 1)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        beginIfReady()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) beginIfReady()
    }

    private fun beginIfReady() {
        val request = turn ?: return
        if (!resumed || !hasWindowFocus() || request.finished || request.conversation != null ||
            permissionPending || authorization?.isShowing == true) return
        if (request.authorizeOnly) {
            authorize(request)
            return
        }
        val conversation = VoiceConversation(this, request.status, onSetup = request.setup,
            onTurnEnd = { request.finish() }, realtimeClient = request.realtimeClient)
        request.conversation = conversation
        if (!conversation.start()) request.finish()
    }

    private fun authorize(request: OverlayMicTurn) {
        if (request.finished) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else if (DoubaoSettings(this).let { it.hasVoiceKey && !it.microphoneUploadAccepted }) {
            authorization = showRealtimeMicrophoneConsent(this) { accepted ->
                request.finish(if (accepted) "请重新按住麦克风说话。" else "已取消语音授权。")
            }
        } else {
            request.finish("请重新按住麦克风说话。")
        }
    }

    internal fun dismissTurn() {
        turn = null
        authorization?.dismiss()
        authorization = null
        if (!isFinishing) finish()
    }

    override fun onStop() {
        if (!permissionPending) turn?.finish()
        super.onStop()
    }

    override fun onDestroy() {
        turn?.finish()
        super.onDestroy()
    }

    companion object {
        internal const val TURN_ID = "overlay_mic_turn"
    }
}
