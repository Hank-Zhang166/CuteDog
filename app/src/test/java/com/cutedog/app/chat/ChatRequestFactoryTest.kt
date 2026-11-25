package com.cutedog.app.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatRequestFactoryTest {
    private val history = listOf(ChatMessage(1, "user", "第一条 \"问题\"\n与换行", 1), ChatMessage(2, "assistant", "回答", 2), ChatMessage(3, "user", "继续", 3))
    private fun request(memories: List<Memory> = emptyList(), enabled: Boolean = false, selected: Long? = null) =
        ChatRequestFactory.create("https://example.org/v1", "example-model", "fixture-key", history, memories, enabled, name = "糯米", userMessageId = selected)

    @Test fun serializesRealRolesUnicodeModelAndEscapedContent() {
        val request = request()
        val json = JSONObject(String(request.payload, Charsets.UTF_8))
        val messages = json.getJSONArray("messages")
        assertEquals("example-model", json.getString("model"))
        assertFalse(json.getBoolean("stream"))
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("糯米"))
        assertEquals(history[0].content, messages.getJSONObject(1).getString("content"))
        assertEquals(listOf("system", "user", "assistant", "user"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertFalse(String(request.payload).contains("fixture-key"))
        assertEquals("ChatRequest(redacted)", request.toString())
    }

    @Test fun deepSeekGetsTheSamoyedPersonaAndStreamsByDefault() {
        val request = ChatRequestFactory.create("https://api.deepseek.com/v1", "deepseek-flash", "fixture",
            history, emptyList(), false, name = "奶糖")
        val payload = JSONObject(String(request.payload, Charsets.UTF_8))
        assertTrue(payload.getBoolean("stream"))
        val system = payload.getJSONArray("messages").getJSONObject(0)
        assertEquals("system", system.getString("role"))
        assertTrue(system.getString("content").contains(ChatPolicy.PERSONA))
        assertTrue(system.getString("content").contains("奶糖"))
    }

    @Test fun memoryIsNeverSentWithoutConsentAndIsNeverPrivileged() {
        val secret = "仅本地记忆：准备考试"
        assertFalse(String(request(listOf(Memory(7, secret)), false).payload).contains(secret))
        val messages = JSONObject(String(request(listOf(Memory(7, secret)), true).payload)).getJSONArray("messages")
        assertFalse(messages.getJSONObject(0).getString("content").contains(secret))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertTrue(messages.getJSONObject(1).getString("content").contains(secret))
        assertFalse(String(request(emptyList(), true).payload).contains(secret))
    }

    @Test fun retryBindsToExistingUserAndNeverIncludesFutureMessages() {
        val messages = JSONObject(String(request(selected = 1).payload)).getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals(history.first().content, messages.getJSONObject(1).getString("content"))
        assertThrows(ChatException::class.java) { request(selected = 99) }
        assertThrows(ChatException::class.java) { request(selected = 2) }
    }

    @Test fun boundedHistoryDropsWholeOldMessagesAndSignalsTruncation() {
        val longHistory = (1L..51L).map { ChatMessage(it, if (it % 2 == 0L) "assistant" else "user", "x".repeat(1500) + "🐾", it) }
        val request = ChatRequestFactory.create("https://example.org", "model", "key", longHistory, emptyList(), false)
        assertTrue(request.contextTruncated)
        val messages = JSONObject(String(request.payload)).getJSONArray("messages")
        assertTrue(messages.length() < 25)
        for (i in 1 until messages.length()) assertEquals(1502, messages.getJSONObject(i).getString("content").length)
        assertThrows(ChatException::class.java) {
            ChatRequestFactory.create("https://example.org", "model", "key", listOf(ChatMessage(1, "user", "x".repeat(25000), 1)), emptyList(), false)
        }
    }

    @Test fun rejectsMissingKeyAndAssistantOnlyRequestsBeforeTransport() {
        assertThrows(ChatException::class.java) { ChatRequestFactory.create("https://example.org", "model", "", history, emptyList(), false) }
        assertThrows(ChatException::class.java) { ChatRequestFactory.create("https://example.org", "model", "key", history.take(2), emptyList(), false) }
    }
}
