package com.cutedog.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.cutedog.app.BuildConfig
import com.cutedog.app.voice.doubao.DoubaoRealtimeClient
import com.cutedog.app.voice.doubao.DoubaoSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive

/** Records through the microphone foreground service while the panel owns the press. */
internal class OverlayVoiceController(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val voiceReady: () -> Boolean,
    onTurnEnd: () -> Unit = {},
    realtimeClient: DoubaoRealtimeClient = (if (BuildConfig.DEBUG) clientForDeviceTest else null)
        ?: DoubaoRealtimeClient(),
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = DoubaoSettings(app)
    private val conversation = RealtimeVoiceConversation(
        app, scope, onStatus, ::showSetupHint, realtimeClient, onTurnEnd,
        onConsentRequired = ::showSetupHint,
    )

    fun start(): Boolean {
        if (!scope.isActive) return false
        conversation.cancel()
        if (!voiceReady() || !settings.microphoneUploadAccepted ||
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            showSetupHint()
            return false
        }
        return conversation.start()
    }

    private fun showSetupHint() {
        onStatus("请在 CuteDog 的「小狗」页启用桌宠麦克风，再回到这里按住说话。")
    }

    fun submit() { conversation.submit() }

    fun cancel() = conversation.cancel()

    fun close() {
        conversation.close()
        scope.cancel()
    }

    companion object {
        @Volatile internal var clientForDeviceTest: DoubaoRealtimeClient? = null
    }
}
