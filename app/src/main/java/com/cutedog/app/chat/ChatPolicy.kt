package com.cutedog.app.chat

import java.net.URI

/** Pure protocol policy, shared by the client and JVM tests. */
object ChatPolicy {
    val allowedIntents = setOf("happy", "calm", "curious", "cuddle", "wave")
    private val actionSuffix = Regex("\\s*<action:([a-z_]+)>\\s*$")

    fun completionUrl(endpoint: String): String {
        val clean = endpoint.trim().trimEnd('/')
        require(clean.length in 1..2048 && clean.none { it.isWhitespace() || it.isISOControl() }) {
            "服务地址格式不正确"
        }
        val uri = try { URI(clean) } catch (_: Exception) {
            throw IllegalArgumentException("服务地址格式不正确")
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "为保护对话与密钥，仅支持 HTTPS 服务地址"
        }
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "服务地址不能包含用户名、密码、查询参数或片段"
        }
        require(uri.port == -1 || uri.port in 1..65535) { "服务端口不正确" }
        return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
    }

    fun boundedHistory(history: List<ChatMessage>, maxCharacters: Int = 24_000): List<ChatMessage> {
        require(maxCharacters > 0)
        val result = mutableListOf<ChatMessage>()
        var remaining = maxCharacters
        for (message in history.asReversed()) {
            if (message.role !in setOf("user", "assistant") || message.content.isBlank()) continue
            if (result.size == 24 || remaining == 0) break
            if (message.content.length > remaining) {
                require(result.isNotEmpty()) { "当前消息过长，请分成几条再发送" }
                break // Keep complete messages, including surrogate pairs and quoted context.
            }
            result += message
            remaining -= message.content.length
        }
        return result.asReversed()
    }

    fun parseReply(content: String): ChatReply {
        val match = actionSuffix.find(content)
        val intent = match?.groupValues?.get(1)?.takeIf { it in allowedIntents }
        val text = if (match != null) content.substring(0, match.range.first).trim() else content.trim()
        require(text.isNotBlank()) { "模型没有返回可显示的文字，请重试" }
        require(text.length <= 20_000) { "模型回复过长，请要求更简短的回复" }
        return ChatReply(text, intent)
    }

    const val SYSTEM_PROMPT = """你是 CuteDog，一只温柔的萨摩耶伙伴。用自然、有内容的简体中文交流，可以活泼、幽默，但不要每句都叫“主人”或“汪汪”。认真回应用户实际表达，记住当前上下文；给出实用帮助，并承认不确定性。不要冒充真人，不要宣称自己有真实感受、能监视用户手机或已执行提醒、支付等系统操作。保持健康边界，不操纵情绪、不引导用户远离现实人际关系。遇到痛苦情绪先倾听、温和支持，并在需要时鼓励寻求可信的人或专业支持。你没有系统工具，不能创建提醒、读取其他应用或执行命令。若用户要求提醒，请建议使用应用备忘录并确认日期。可在回复末尾附加且仅附加一个动作标记：<action:happy>、<action:calm>、<action:curious>、<action:cuddle> 或 <action:wave>。标记只用于宠物表情，不表示任何系统操作。没有合适动作时省略。"""
}
