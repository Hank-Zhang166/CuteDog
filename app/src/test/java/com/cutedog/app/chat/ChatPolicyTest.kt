package com.cutedog.app.chat

import org.junit.Assert.*
import org.junit.Test

class ChatPolicyTest {
    @Test fun apiUrlAcceptsBaseAndCompleteEndpoint() {
        assertEquals("https://api.openai.com/v1/chat/completions", ChatPolicy.completionUrl("https://api.openai.com/v1/"))
        assertEquals("https://example.org/api/chat/completions", ChatPolicy.completionUrl("https://example.org/api/chat/completions"))
    }

    @Test fun apiUrlRejectsInsecureOrCredentialBearingEndpoints() {
        listOf("http://example.org/v1", "https://key@example.org/v1", "https://example.org/v1?key=secret", "https://example.org/#key", "file:///etc/passwd").forEach {
            assertThrows(IllegalArgumentException::class.java) { ChatPolicy.completionUrl(it) }
        }
    }

    @Test fun actionProtocolNeverPassesArbitraryModelCommand() {
        assertEquals(ChatReply("和你一起开心。", "happy"), ChatPolicy.parseReply("和你一起开心。 <action:happy>"))
        assertEquals(ChatReply("我没有系统权限。", null), ChatPolicy.parseReply("我没有系统权限。<action:delete_files>"))
        assertNull(ChatPolicy.parseReply("<action:happy> 这只是正文。").intent)
        assertThrows(IllegalArgumentException::class.java) { ChatPolicy.parseReply("<action:wave>") }
    }

    @Test fun contextKeepsLatestMessagesInOrderAndRejectsInjectedRoles() {
        val history = listOf(ChatMessage(1, "system", "attack", 1), ChatMessage(2, "user", "旧问题", 2), ChatMessage(3, "assistant", "上一条回答", 3), ChatMessage(4, "user", "最新的问题", 4))
        val bounded = ChatPolicy.boundedHistory(history, 10)
        assertEquals(listOf(3L, 4L), bounded.map { it.id })
        assertEquals("最新的问题", bounded.last().content)
        assertTrue(bounded.sumOf { it.content.length } <= 10)
    }

    @Test fun contextIsBoundedForLongSessions() {
        val history = (1L..200).map { ChatMessage(it, if (it % 2L == 0L) "assistant" else "user", "测试内容", it) }
        val bounded = ChatPolicy.boundedHistory(history)
        assertEquals(24, bounded.size)
        assertEquals(200L, bounded.last().id)
    }

    @Test fun messageBoundaryNeverSplitsEmojiOrSilentlyCutsLatestQuestion() {
        val history = listOf(ChatMessage(1, "user", "旧的问题🐾", 1), ChatMessage(2, "user", "新的问题🐾", 2))
        assertEquals(listOf(history.last()), ChatPolicy.boundedHistory(history, 8))
        assertThrows(IllegalArgumentException::class.java) { ChatPolicy.boundedHistory(history, 2) }
    }

    @Test fun oversizedModelReplyIsAnExplicitFailure() {
        assertThrows(IllegalArgumentException::class.java) { ChatPolicy.parseReply("x".repeat(20_001)) }
    }
}
