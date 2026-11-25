package com.cutedog.app.chat

import java.io.FilterInputStream
import java.io.InputStream
import org.json.JSONObject

/** Only the first choice's spoken text can reach a bubble. Reasoning and action cues stay hidden. */
internal object ChatResponseReader {
    fun read(input: InputStream, contentType: String?, onText: (String) -> Unit): ChatReply {
        val streaming = contentType?.substringBefore(';')?.trim().equals("text/event-stream", true)
        val limited = ByteLimit(input, if (streaming) 2_097_152 else 262_144)
        val raw = if (streaming) readEvents(limited, onText) else {
            val body = JSONObject(limited.bufferedReader(Charsets.UTF_8).readText())
            body.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content") as? String
                ?: throw ChatException("AI 服务未返回文字，请检查模型是否支持聊天补全。")
        }
        return try { ChatPolicy.parseReply(raw) }
        catch (error: IllegalArgumentException) { throw ChatException(error.message ?: "模型回复格式不正确") }
    }

    private fun readEvents(input: InputStream, onText: (String) -> Unit): String {
        val reader = input.bufferedReader(Charsets.UTF_8)
        val content = StringBuilder()
        val data = mutableListOf<String>()
        var finished = false
        while (true) {
            val line = reader.readLine()
            if (line == null || line.isEmpty()) {
                if (data.isNotEmpty()) {
                    val payload = data.joinToString("\n")
                    data.clear()
                    if (payload.trim() == "[DONE]") return content.toString()
                    val event = JSONObject(payload)
                    if (event.has("error")) throw ChatException("模型服务中断了这次回复，请重试。")
                    val choice = event.optJSONArray("choices")?.optJSONObject(0)
                    if (choice != null) {
                        val delta = choice.optJSONObject("delta")?.opt("content") as? String
                        if (!delta.isNullOrEmpty()) {
                            if (content.length + delta.length > 20_128) throw ChatException("回复过长，请分成几个问题。")
                            content.append(delta)
                            onText(ChatPolicy.previewText(content.toString()))
                        }
                        if (!choice.isNull("finish_reason")) finished = true
                    }
                }
                if (line == null) {
                    if (finished) return content.toString()
                    throw ChatException("连接在小狗说完前中断了，可以重试这条消息。")
                }
            } else if (line.startsWith("data:")) {
                data.add(line.substring(5).removePrefix(" "))
            }
        }
    }

    private class ByteLimit(input: InputStream, private val limit: Int) : FilterInputStream(input) {
        private var total = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count(it) }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        private fun count(bytes: Int) {
            total += bytes
            if (total > limit) throw ChatException("AI 响应过大，请减少对话长度后重试。")
        }
    }
}
