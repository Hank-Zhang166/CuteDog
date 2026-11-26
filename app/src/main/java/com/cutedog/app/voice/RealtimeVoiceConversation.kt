package com.cutedog.app.voice

import android.app.AlertDialog
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import com.cutedog.app.chat.ChatPolicy
import com.cutedog.app.chat.ChatRepository
import com.cutedog.app.pet.PetSettings
import com.cutedog.app.voice.doubao.DoubaoEvent
import com.cutedog.app.voice.doubao.DoubaoHistory
import com.cutedog.app.voice.doubao.DoubaoRealtimeClient
import com.cutedog.app.voice.doubao.DoubaoSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

internal class RealtimeVoiceConversation(
    private val activity: ComponentActivity,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
    private val onSetup: () -> Unit,
    private val client: DoubaoRealtimeClient = DoubaoRealtimeClient(),
    private val onTurnEnd: () -> Unit = {},
) {
    private val settings = DoubaoSettings(activity)
    private var active: Capture? = null
    private var consent: AlertDialog? = null

    private class Capture(val recorder: MicCapture, val input: Channel<ByteArray>) {
        var turn: Job? = null
        var releasedAt: Long? = null
    }

    fun start(): Boolean {
        if (!settings.hasVoiceKey) {
            onSetup()
            return false
        }
        if (!settings.microphoneUploadAccepted) {
            if (consent?.isShowing != true) {
                consent = showRealtimeMicrophoneConsent(activity) { accepted ->
                    if (accepted) onStatus("请重新按住麦克风说话。")
                }
            }
            return false
        }

        // AudioRecord produces at most 30 seconds. Allow temporary buffering while
        // TLS connects, so the first word is preserved even on a cold connection.
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        val recorder = try {
            MicCapture.start(activity, scope, onPcm = { input.trySend(it) })
        } catch (_: Exception) {
            input.cancel()
            onStatus("暂时打不开麦克风，请检查权限或其他正在录音的应用。")
            return false
        }
        val capture = Capture(recorder, input)
        active = capture
        onStatus("在听啦…松手发送，滑出取消。最多说 30 秒。")
        capture.turn = scope.launch {
            try {
                exchange(capture)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                val message = failure.message
                onStatus(message?.takeIf { it.startsWith("豆包") || it.startsWith("没听清") ||
                    it.startsWith("小狗") || it.startsWith("连接豆包") }?.take(120)
                    ?: "豆包语音暂时无法连接，请检查网络和语音设置。")
            } catch (failure: IllegalArgumentException) {
                onStatus("豆包语音设置或回复格式有误，请核对配置后重试。")
            } catch (failure: Exception) {
                onStatus(failure.message?.takeIf { it.startsWith("豆包") }?.take(120)
                    ?: "豆包语音暂时无法连接，请检查网络和语音设置。")
            } finally {
                recorder.discard()
                input.cancel()
                if (active === capture) {
                    active = null
                    onTurnEnd()
                }
            }
        }
        return true
    }

    fun submit(): Boolean {
        val capture = active ?: return false
        if (capture.releasedAt != null) return true
        capture.releasedAt = SystemClock.elapsedRealtime()
        Log.i("CuteDogRealtimeVoice", "mic_released elapsedMs=${capture.releasedAt}")
        capture.recorder.end()
        onStatus("小狗在听你刚才说的话…")
        scope.launch {
            try { capture.recorder.finish() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { capture.input.cancel(); return@launch }
            capture.input.close()
        }
        return true
    }

    fun cancel() {
        active?.let {
            active = null
            it.recorder.discard()
            it.input.cancel()
            it.turn?.cancel()
        }
    }

    fun close() {
        cancel()
        consent?.dismiss()
    }

    private suspend fun exchange(capture: Capture) = coroutineScope {
        val repository = ChatRepository(activity)
        val (voiceKey, searchKey, history) = withContext(Dispatchers.IO) {
            Triple(settings.voiceApiKey, settings.searchApiKey, recentTurns(repository))
        }
        val instructions = buildString {
            append(ChatPolicy.PERSONA)
            append("\n你的显示名字是 ").append(JSONObject.quote(PetSettings(activity).name.take(20)))
            append("。回答用于直接口播，用短句自然停顿。用户问时效信息时请查询，能核实来源时说出来源。")
        }
        val audio = Channel<ByteArray>(capacity = 128)
        var playbackFailed = false
        val player = launch {
            try { RealtimePcmPlayer.play(audio) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                playbackFailed = true
                // Drain remaining packets so the text and transcript can still be saved.
                for (ignored in audio) Unit
            }
        }
        var userId: Long? = null
        var answer = ""
        try {
            client.converse(voiceKey, searchKey, instructions, history, capture.input) { event ->
                when (event) {
                    DoubaoEvent.Ready -> Unit
                    is DoubaoEvent.Transcript -> {
                        if (event.final && event.text.isNotBlank() && userId == null) {
                            userId = withContext(Dispatchers.IO) {
                                repository.add("user", event.text.take(20_000), voice = true).id
                            }
                            onStatus("听到你说「${event.text}」。")
                        }
                    }
                    is DoubaoEvent.Reply -> {
                        if (event.text.isNotBlank()) {
                            answer = event.text.take(20_000)
                            onStatus(answer)
                        }
                    }
                    is DoubaoEvent.Audio -> audio.send(event.pcm)
                }
            }
            userId?.let { id ->
                if (answer.isNotBlank()) withContext(Dispatchers.IO) { repository.saveReply(id, answer) }
            }
        } finally { audio.close() }
        player.join()
        onStatus(when {
            answer.isEmpty() -> "没听清呢，再按住麦克风说一次吧。"
            playbackFailed -> "$answer\n\n声音未播完，文字已保留。"
            else -> answer
        })
    }

    private fun recentTurns(repository: ChatRepository): List<DoubaoHistory> =
        repository.messages().takeLast(24).zipWithNext().filter { (user, reply) ->
            user.role == "user" && reply.role == "assistant"
        }.takeLast(8).flatMap { (user, reply) ->
            listOf(DoubaoHistory("user", user.content.take(1_000)),
                DoubaoHistory("assistant", reply.content.take(1_000)))
        }
}

internal fun showRealtimeMicrophoneConsent(context: Context, onDismiss: (Boolean) -> Unit): AlertDialog {
    var accepted = false
    return AlertDialog.Builder(context)
        .setTitle("使用豆包实时语音？")
        .setMessage("按住时录音会实时发给豆包，以便松手后更快回答。启用搜索时豆包会按需查询。听写和回答保存在本机聊天记录，近期对话会随请求发给豆包。滑出按钮会停止本轮。")
        .setNegativeButton("暂不使用", null)
        .setPositiveButton("同意") { _, _ ->
            DoubaoSettings(context).microphoneUploadAccepted = true
            accepted = true
        }
        .setOnDismissListener { onDismiss(accepted) }
        .show()
}
