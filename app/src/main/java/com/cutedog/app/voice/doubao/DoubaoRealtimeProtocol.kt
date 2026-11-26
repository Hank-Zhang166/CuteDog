package com.cutedog.app.voice.doubao

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.UUID

internal data class DoubaoHistory(val role: String, val text: String)

internal sealed interface DoubaoEvent {
    data object Ready : DoubaoEvent
    data class Transcript(val text: String, val final: Boolean) : DoubaoEvent
    data class Reply(val text: String, val final: Boolean) : DoubaoEvent
    data class Audio(val pcm: ByteArray) : DoubaoEvent
}

internal object DoubaoRealtimeProtocol {
    const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"
    const val INPUT_FRAME_BYTES = 640
    const val OUTPUT_RATE = 24_000
    private const val TRANSCRIPTION = "conversation.item.input_audio_transcription"

    fun event(type: String): JSONObject = JSONObject()
        .put("type", type)
        .put("event_id", UUID.randomUUID().toString())

    // Seeduplex's Realtime endpoint carries one JSON event per WebSocket text message.
    fun createSession(instructions: String, history: List<DoubaoHistory>, searchKey: String): JSONObject {
        val audio = JSONObject()
            .put("input", JSONObject().put("format", format("pcm", 16_000)))
            .put("output", JSONObject()
                .put("format", format("pcm_s16le", OUTPUT_RATE))
                .put("voice", "zh_female_vv_uranus_bigtts"))
        val search = JSONObject().put("enable_volc_websearch", searchKey.isNotBlank())
        if (searchKey.isNotBlank()) {
            search.put("volc_websearch_type", "web_custom_api")
                .put("volc_websearch_api_key", searchKey)
        }
        val context = JSONArray()
        val pairs = mutableListOf<Pair<DoubaoHistory, DoubaoHistory>>()
        var user: DoubaoHistory? = null
        for (message in history) {
            if (message.text.isBlank()) continue
            if (message.role == "user") {
                user = message
            } else if (message.role == "assistant") {
                user?.let { pairs.add(it to message) }
                user = null
            }
        }
        pairs.takeLast(20).forEach { (question, answer) ->
            for (message in listOf(question, answer)) {
                context.put(JSONObject().put("role", message.role).put("text", message.text.take(2_000)))
            }
        }
        val dialog = JSONObject().put("extra", search)
        if (context.length() > 0) dialog.put("dialog_context", context)
        return event("session.create")
            .put("session", JSONObject().put("model", "1.2.6.1")
                .put("instructions", instructions).put("audio", audio))
            .put("extension", JSONObject().put("dialog", dialog))
    }

    fun appendAudio(pcm: ByteArray): JSONObject =
        event("input_audio_buffer.append").put("audio", Base64.getEncoder().encodeToString(pcm))

    private fun format(type: String, rate: Int) = JSONObject().put("type", type).put("rate", rate)

    fun read(frame: String): ServerEvent {
        val json = try {
            JSONObject(frame)
        } catch (_: Exception) {
            throw IOException("豆包实时语音返回了无法读取的消息。")
        }
        return when (json.optString("type")) {
            "session.created" -> ServerEvent.Connected
            "session.closed" -> ServerEvent.Closed
            "$TRANSCRIPTION.started" -> ServerEvent.SpeechStarted
            "$TRANSCRIPTION.delta" -> ServerEvent.Transcript(json.field("delta"), false)
            "$TRANSCRIPTION.completed" -> ServerEvent.Transcript(
                json.field("transcript").ifBlank { json.field("text") }, true
            )
            "$TRANSCRIPTION.failed" -> ServerEvent.TranscriptFailed
            "response.output_text.delta" -> ServerEvent.Reply(json.field("delta"), false)
            "response.output_text.done" -> ServerEvent.Reply(json.field("text"), true)
            "response.output_audio.delta" -> {
                val bytes = try {
                    Base64.getDecoder().decode(json.field("delta"))
                } catch (_: IllegalArgumentException) {
                    throw IOException("豆包实时语音返回了无法读取的音频。")
                }
                if (bytes.size % 2 != 0) throw IOException("豆包实时语音返回了不完整的音频帧。")
                ServerEvent.Audio(bytes)
            }
            "response.output_audio.done" -> ServerEvent.AudioDone
            "response.canceled" -> ServerEvent.Canceled
            "response.done" -> ServerEvent.Done
            "error" -> {
                val error = json.optJSONObject("error")
                val code = (error?.field("code").orEmpty().ifBlank { json.field("status_code") })
                    .takeIf { it.length in 3..10 && it.all { c -> c in '0'..'9' } }
                val message = if (code == "42000020") "豆包搜索配置未通过，请检查搜索服务与密钥。"
                    else "豆包实时语音服务未完成请求。"
                throw IOException(if (code == null) message else "$message（$code）")
            }
            else -> ServerEvent.Other
        }
    }

    private fun JSONObject.field(key: String): String =
        if (isNull(key)) "" else optString(key, "")
}

internal sealed interface ServerEvent {
    data object Connected : ServerEvent
    data object Closed : ServerEvent
    data object SpeechStarted : ServerEvent
    data object TranscriptFailed : ServerEvent
    data object AudioDone : ServerEvent
    data object Canceled : ServerEvent
    data object Done : ServerEvent
    data object Other : ServerEvent
    data class Transcript(val text: String, val final: Boolean) : ServerEvent
    data class Reply(val text: String, val final: Boolean) : ServerEvent
    data class Audio(val bytes: ByteArray) : ServerEvent
}
