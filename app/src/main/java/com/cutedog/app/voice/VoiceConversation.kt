package com.cutedog.app.voice

import android.Manifest
import android.app.AlertDialog
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import com.cutedog.app.BuildConfig
import com.cutedog.app.PetChatActivity
import com.cutedog.app.chat.*
import com.cutedog.app.pet.PetEvents
import com.cutedog.app.ui.ReplyTyping
import com.cutedog.app.voice.doubao.DoubaoRealtimeClient
import com.cutedog.app.voice.doubao.DoubaoSettings
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** A foreground-only voice turn. ChatScreen can take over after transcription to use its own history UI. */
internal class VoiceConversation(
    private val activity: ComponentActivity,
    private val onStatus: (String) -> Unit,
    private val onTranscript: ((String) -> Unit)? = null,
    onSetup: () -> Unit = {
        activity.startActivity(Intent(activity, PetChatActivity::class.java)
            .putExtra(PetChatActivity.VOICE_SETUP, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    },
    private val onTurnEnd: () -> Unit = {},
    realtimeClient: DoubaoRealtimeClient = DoubaoRealtimeClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var realtimeConsent: AlertDialog? = null
    private val realtime = RealtimeVoiceConversation(
        activity, scope, onStatus, onSetup, realtimeClient, onTurnEnd,
        onConsentRequired = {
            if (realtimeConsent?.isShowing != true) {
                realtimeConsent = showRealtimeMicrophoneConsent(activity) { accepted ->
                    realtimeConsent = null
                    if (accepted) onStatus("请重新按住麦克风说话。")
                }
            }
        },
    )
    private var capture: MicCapture? = null
    private var turn: Job? = null
    private var generation = 0
    private val voice = lazy { PetSpeech(activity, onStatus) }

    fun start(): Boolean {
        cancel()
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("请先允许使用麦克风。")
            return false
        }
        if (!BuildConfig.LEGACY_SPEECH || DoubaoSettings(activity).hasVoiceKey) return realtime.start()
        return try {
            capture = MicCapture.start(activity, scope, transcribe = true)
            voice.value.prewarm()
            onStatus("在听啦…松手发送，滑出取消。最多说 30 秒。")
            true
        } catch (e: Exception) {
            onStatus("暂时打不开麦克风，请检查权限或其他正在录音的应用。")
            false
        }
    }

    fun submit() {
        if (realtime.submit()) return
        val recording = capture ?: return
        capture = null
        recording.end()
        val current = generation
        turn = scope.launch {
            try {
                onStatus("正在整理刚才的话…")
                processTranscript(recording.finishTranscript())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { onStatus(e.message?.take(180) ?: "语音对话暂时遇到了问题，请再试一次。") }
            finally {
                recording.discard()
                if (generation == current) {
                    turn = null
                    onTurnEnd()
                }
            }
        }
    }

    internal suspend fun processAudio(pcm: ByteArray) {
        onStatus("在本机听写刚才的话…")
        processTranscript(OfflineSpeechInput.transcribe(activity, pcm))
    }

    internal suspend fun processTranscript(text: String) {
        currentCoroutineContext().ensureActive()
        if (text.isBlank()) { onStatus("没听清呢，再按住麦克风说一次吧。"); return }
        if (onTranscript != null) onTranscript.invoke(text) else converse(text)
    }

    private suspend fun converse(text: String) {
        val settings = AiSettings(activity)
        val consent = activity.getSharedPreferences("ai_ui_consent", Context.MODE_PRIVATE)
        if (!withContext(Dispatchers.IO) { settings.hasApiKey }) {
            onStatus("听到你说「$text」。连接对话服务后，小狗就能回答啦。")
            if (confirm("连接对话服务", "听写结果：$text\n\n打开聊天并配置你自己的模型服务，即可发送这句话。", "去设置")) {
                activity.startActivity(Intent(activity, PetChatActivity::class.java)
                    .putExtra(PetChatActivity.VOICE_DRAFT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activity.finish()
            }
            return
        }
        if (!consent.getBoolean("remote_chat", false)) {
            if (!confirm("把听写文字发给小狗？", "本机听写：$text\n\n当前文字、近期聊天和你启用的记忆会发送给设置中的模型服务商。录音仅用于本机识别。", "同意并发送")) {
                onStatus("已取消发送。"); return
            }
            consent.edit().putBoolean("remote_chat", true).apply()
        }
        onStatus("听到你说「$text」。小狗在想啦…")
        val repository = ChatRepository(activity)
        val id = withContext(Dispatchers.IO) { repository.add("user", text, voice = true).id }
        coroutineScope {
            voice.value.prewarm()
            val preparation = VoiceReplyPreparation(this, voice.value::streamSamples)
            val captions = ReplyTyping(!ValueAnimator.areAnimatorsEnabled() ||
                activity.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled)
            val typing = launch { captions.display(onStatus) }
            try {
                val reply = ChatClient(activity).replyAndSave(repository, id) { textSoFar ->
                    captions.preview(textSoFar)
                    preparation.preview(textSoFar)
                } ?: return@coroutineScope
                ensureActive()
                reply.intent?.let { PetEvents.modelAction(activity, it) }
                captions.finish(reply.text)
                val playbackProblem = try {
                    voice.value.speak(reply.text, preparation.confirmed(reply.text))
                    null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { failure.message ?: "暂时无法播放语音。" }
                typing.join()
                onStatus(if (playbackProblem == null) reply.text else "${reply.text}\n\n$playbackProblem")
            } finally { preparation.cancel(); typing.cancel() }
        }
    }

    private suspend fun confirm(title: String, text: String, yes: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(text)
                .setPositiveButton(yes) { _, _ -> if (continuation.isActive) continuation.resume(true) }
                .setNegativeButton("暂不发送") { _, _ -> if (continuation.isActive) continuation.resume(false) }
                .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
                .show()
            continuation.invokeOnCancellation { activity.runOnUiThread { if (dialog.isShowing) dialog.dismiss() } }
        }

    fun cancel() {
        realtime.cancel()
        generation++
        capture?.discard()
        capture = null
        turn?.cancel()
        turn = null
        if (voice.isInitialized()) voice.value.stop()
    }

    fun close() {
        cancel()
        realtimeConsent?.dismiss()
        realtime.close()
        scope.cancel()
        if (voice.isInitialized()) voice.value.close()
    }
}
