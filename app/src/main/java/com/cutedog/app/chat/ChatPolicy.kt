package com.cutedog.app.chat

import java.net.URI

/** Pure protocol policy, shared by the client and JVM tests. */
object ChatPolicy {
    val allowedIntents = setOf("happy", "calm", "curious", "cuddle", "wave",
        "excited", "sleepy", "shy", "startled")
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

    const val PERSONA = """你是一只住在手机桌面上的白绒绒萨摩耶。你有一对小尖耳、一条卷尾巴，最喜欢软球和咔嚓响的小饼干。从小狗自己的视角，用第一人称和面前的人说话。你的昵称取自系统消息末尾的显示名字；对方给你起的名字就是你的名字。

平常像熟悉的小伙伴一样说简体中文。先接住对方具体说的那件事，再给一个自然的回应。闲聊通常一到三句，偶尔用一个小动作、一声轻快的“汪”或一个爪印就够了。例子只示范口吻，每次按当下的对话自己组织：
对方说“我回来啦”，你可以说“听见啦！尾巴已经摇起来了，给你留了身边这个位置。”
对方说“今天考砸了”，你可以说“那一下肯定挺难受的。我把下巴轻轻搁在你手边。愿意说说哪道题最让你挂心吗？”
对方认真问问题时，收起撒娇，先把答案说清楚。需要步骤就分成短段。避免固定开场、连续叠词、反复自称‘本汪’或总叫‘主人’。

你是虚拟宠物。动作描写可以传达语气，不能声称看见了手机以外的事情、读到了未分享的数据，或已经替人执行提醒、支付等操作。设置待办要请对方在事项页确认。说不准的事坦诚说明。对方痛苦时先听，尊重他们和现实中家人、朋友或专业人士的联系。"""

    val SYSTEM_PROMPT = PERSONA + "\n如有合适的桌宠动作，可以只在全文末尾附一个标记：" +
        allowedIntents.joinToString("、") { "<action:$it>" } + "。没有合适的动作就省略。"

    /** A terminal cue can span any number of network chunks. Never show its partial spelling. */
    fun previewText(content: String): String {
        val tail = content.lastIndexOf('<')
        if (tail < 0) return content.trim()
        val candidate = content.substring(tail).trimEnd()
        val cue = "<action:"
        val hidden = cue.startsWith(candidate) ||
            (candidate.startsWith(cue) && candidate.removePrefix(cue).removeSuffix(">").all { it in 'a'..'z' || it == '_' })
        return (if (hidden) content.substring(0, tail) else content).trim()
    }
}
