package com.cutedog.app.voice.doubao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class DoubaoRealtimeClient(
    private val sockets: WebSocket.Factory = defaultClient,
) {
    /**
     * A push-to-talk turn. The caller closes [audio] when the user releases the button,
     * or cancels this coroutine to discard the turn. Incoming audio is signed 16-bit
     * little-endian PCM at [DoubaoRealtimeProtocol.OUTPUT_RATE].
     */
    suspend fun converse(
        voiceKey: String,
        searchKey: String,
        instructions: String,
        history: List<DoubaoHistory>,
        audio: ReceiveChannel<ByteArray>,
        onEvent: suspend (DoubaoEvent) -> Unit,
    ) = coroutineScope {
        require(voiceKey.isNotBlank()) { "请先填写豆包实时语音密钥。" }
        val incoming = Channel<Incoming>(128)
        val request = Request.Builder().url(DoubaoRealtimeProtocol.ENDPOINT)
            .header("X-Api-Key", voiceKey).build()
        val socket = sockets.newWebSocket(request, listener(incoming))
        var connected = false
        var successful = false
        var sending: Job? = null
        try {
            socket.sendEvent(DoubaoRealtimeProtocol.createSession(instructions, history, searchKey))
            val ready = withTimeoutOrNull(10_000) {
                var accepted = false
                while (!accepted) {
                    val frame = incoming.receive()
                    if (frame is Incoming.Message) when (DoubaoRealtimeProtocol.read(frame.json)) {
                        ServerEvent.Connected -> accepted = true
                        ServerEvent.Closed -> throw IOException("豆包实时语音会话已断开。")
                        else -> Unit
                    }
                }
                true
            }
            if (ready != true) throw IOException("连接豆包实时语音超时，请检查网络后再试。")
            connected = true
            onEvent(DoubaoEvent.Ready)

            sending = launch(Dispatchers.Default) {
                socket.sendAudio(audio)
                socket.sendEvent(DoubaoRealtimeProtocol.event("input_audio_buffer.commit"))
                socket.sendEvent(DoubaoRealtimeProtocol.event("input_audio_mute.commit"))
                incoming.send(Incoming.Release)
            }
            val turn = ReleasedTurn(onEvent)
            // The first timeout also guards a lost release gesture. While speaking, capture lasts up to 30 seconds.
            val released = withTimeoutOrNull(45_000) {
                while (true) {
                    when (val frame = incoming.receive()) {
                        is Incoming.Message -> turn.accept(DoubaoRealtimeProtocol.read(frame.json))
                        Incoming.Release -> break
                    }
                }
                true
            }
            if (released != true) throw IOException("录音等待超时，请重新按住麦克风。")
            turn.release()
            val finished = withTimeoutOrNull(60_000) {
                while (!turn.finished) {
                    val frame = incoming.receive()
                    if (frame is Incoming.Message) turn.accept(DoubaoRealtimeProtocol.read(frame.json))
                }
                true
            }
            if (finished != true) throw IOException("等待小狗回复超时，请稍后再试。")
            sending.join()
            successful = true
        } finally {
            sending?.cancel()
            var acknowledged = false
            if (connected) withContext(NonCancellable) {
                if (!successful) socket.send(DoubaoRealtimeProtocol.event("response.cancel").toString())
                val closeSent = socket.send(DoubaoRealtimeProtocol.event("session.close").toString())
                if (closeSent) withTimeoutOrNull(750) {
                    while (true) {
                        val frame = incoming.receiveCatching().getOrNull() ?: break
                        if (frame is Incoming.Message &&
                            runCatching { DoubaoRealtimeProtocol.read(frame.json) }.getOrNull() == ServerEvent.Closed
                        ) {
                            acknowledged = socket.close(1000, null)
                            break
                        }
                    }
                }
            }
            if (!acknowledged) socket.cancel()
            incoming.cancel()
        }
    }

    private fun listener(incoming: Channel<Incoming>) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > 128 * 1024 || !incoming.trySend(Incoming.Message(text)).isSuccess) {
                incoming.close(IOException("豆包实时语音消息积压，请重试。"))
                webSocket.cancel()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            incoming.close(IOException("豆包实时语音返回了不受支持的消息格式。"))
            webSocket.cancel()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            incoming.close(IOException("豆包实时语音连接已关闭（$code）。"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            val message = when (code) {
                401, 403 -> "豆包实时语音鉴权失败，请检查密钥和服务开通状态（$code）。"
                429 -> "豆包实时语音使用频率超过限制，请稍后再试（429）。"
                null -> "连接豆包实时语音失败，请检查网络后再试。"
                else -> "豆包实时语音连接失败（HTTP $code）。"
            }
            incoming.close(IOException(message))
        }
    }

    private suspend fun WebSocket.sendAudio(audio: ReceiveChannel<ByteArray>) {
        val frame = ByteArray(DoubaoRealtimeProtocol.INPUT_FRAME_BYTES)
        var filled = 0
        var samplesSent = 0
        var lastPacketAt = 0L
        suspend fun sendFrame() {
            // The direct API expects 640-byte packets at realtime pace, even when capture
            // has buffered audio while the WebSocket was establishing its session.
            if (lastPacketAt != 0L) {
                val remainingNs = 20_000_000L - (System.nanoTime() - lastPacketAt)
                if (remainingNs > 0) delay((remainingNs + 999_999) / 1_000_000)
            }
            if (queueSize() > 128 * 1024) throw IOException("网络来不及发送录音，请重试。")
            sendEvent(DoubaoRealtimeProtocol.appendAudio(frame))
            lastPacketAt = System.nanoTime()
            samplesSent += filled / 2
            filled = 0
        }
        for (block in audio) {
            var offset = 0
            while (offset < block.size) {
                val bytes = minOf(frame.size - filled, block.size - offset)
                block.copyInto(frame, filled, offset, offset + bytes)
                filled += bytes
                offset += bytes
                if (filled == frame.size) sendFrame()
            }
        }
        if (filled % 2 != 0) filled--
        if (filled > 0) {
            frame.fill(0, fromIndex = filled)
            sendFrame()
        }
        if (samplesSent == 0) throw IOException("没听清呢，再按住麦克风说一次吧。")
    }

    private fun WebSocket.sendEvent(event: JSONObject) {
        if (!send(event.toString())) throw IOException("豆包实时语音连接已断开。")
    }

    private sealed interface Incoming {
        data class Message(val json: String) : Incoming
        data object Release : Incoming
    }

    private companion object {
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }
}

/** The cloud can auto-end on a pause. Hold resulting text and PCM until the user releases. */
private class ReleasedTurn(private val emit: suspend (DoubaoEvent) -> Unit) {
    private val transcript = StringBuilder()
    private val previousTranscript = StringBuilder()
    private val reply = StringBuilder()
    private val audio = mutableListOf<ByteArray>()
    private var audioBytes = 0
    private var transcriptFinal = false
    private var transcriptFailed = false
    private var replyFinal = false
    private var audioDone = false
    private var done = false
    private var released = false
    val finished: Boolean get() {
        if (!done) return false
        if (transcriptFailed || (transcriptFinal && transcript.isBlank()))
            throw IOException("没听清呢，再按住麦克风说一次吧。")
        if (!transcriptFinal || !audioDone) return false
        if (reply.isBlank() || audioBytes == 0)
            throw IOException("小狗这次没有收到完整的语音回复，请重试。")
        return true
    }

    suspend fun release() {
        released = true
        emitTranscript()
        emitReply()
        for (chunk in audio) emit(DoubaoEvent.Audio(chunk))
        audio.clear()
    }

    suspend fun accept(event: ServerEvent) {
        when (event) {
            ServerEvent.SpeechStarted -> if (!released && transcriptFinal) {
                previousTranscript.append(transcript).append(' ')
                transcript.clear()
                transcriptFinal = false
                transcriptFailed = false
                resetReply()
            }
            is ServerEvent.Transcript -> {
                if (event.final && event.text.isNotBlank()) transcript.clear()
                transcript.append(event.text)
                transcriptFinal = event.final
                if (released) emitTranscript()
            }
            ServerEvent.TranscriptFailed -> {
                transcriptFailed = true
                if (released) throw IOException("没听清呢，再按住麦克风说一次吧。")
            }
            is ServerEvent.Reply -> {
                if (event.final && event.text.isNotBlank()) reply.clear()
                reply.append(event.text)
                replyFinal = event.final || replyFinal
                if (released) emitReply()
            }
            is ServerEvent.Audio -> if (event.bytes.isNotEmpty()) {
                audioBytes += event.bytes.size
                if (audioBytes > 90 * 24_000 * 2) throw IOException("小狗的语音回复过长。")
                if (released) emit(DoubaoEvent.Audio(event.bytes)) else audio.add(event.bytes)
            }
            ServerEvent.AudioDone -> audioDone = true
            ServerEvent.Canceled -> if (released) throw IOException("这轮语音对话已结束，请重试。") else resetReply()
            ServerEvent.Done -> {
                done = true
                if (!replyFinal) {
                    replyFinal = true
                    if (released) emitReply()
                }
            }
            ServerEvent.Closed -> throw IOException("豆包实时语音会话已断开。")
            else -> Unit
        }
    }

    private suspend fun emitTranscript() {
        val text = (previousTranscript.toString() + transcript).trim()
        if (text.isNotBlank()) emit(DoubaoEvent.Transcript(text, transcriptFinal))
    }

    private suspend fun emitReply() {
        if (reply.isNotBlank()) emit(DoubaoEvent.Reply(reply.toString(), replyFinal))
    }

    private fun resetReply() {
        reply.clear()
        audio.clear()
        audioBytes = 0
        replyFinal = false
        audioDone = false
        done = false
    }
}
