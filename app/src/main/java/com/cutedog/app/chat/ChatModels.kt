package com.cutedog.app.chat

data class ChatMessage(val id: Long, val role: String, val content: String, val createdAt: Long, val voice: Boolean = false)
data class Memory(val id: Long, val text: String)
data class ChatReply(val text: String, val intent: String?, val contextTruncated: Boolean = false)

/** Safe for displaying to the user: never contains credentials or provider response bodies. */
class ChatException(message: String, cause: Throwable? = null) : Exception(message, cause)
