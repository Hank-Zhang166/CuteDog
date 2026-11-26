package com.cutedog.app.chat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** Isolated DB and Keystore aliases; never clears the user's production chat or credentials. */
@RunWith(AndroidJUnit4::class)
class ChatPrivacyPersistenceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "instrumentation_fixture"
    private lateinit var repository: ChatRepository
    private lateinit var settings: AiSettings

    @Before fun prepare() {
        repository = ChatRepository(context, "chat-instrumentation-fixture.db")
        repository.clear(); repository.clearMemories()
        context.getSharedPreferences("ai_settings_$namespace", Context.MODE_PRIVATE).edit().clear().commit()
        settings = AiSettings(context, namespace)
    }

    @After fun cleanup() {
        repository.clear(); repository.clearMemories()
        context.getSharedPreferences("ai_settings_$namespace", Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("cutedog.ai.$namespace") }
    }

    @Test fun keystoreEncryptsPersistsRandomizesAndClearsCredential() {
        val secret = "fixture-only-not-a-real-key-123456"
        settings.apiKey = secret
        assertTrue(settings.hasApiKey)
        val prefs = context.getSharedPreferences("ai_settings_$namespace", Context.MODE_PRIVATE)
        val firstCiphertext = prefs.getString("api_key_ciphertext", null)!!
        assertNotEquals(secret, firstCiphertext)
        assertFalse(prefs.all.values.any { it.toString().contains(secret) })
        assertEquals(secret, AiSettings(context, namespace).apiKey)
        settings.apiKey = secret
        assertNotEquals(firstCiphertext, prefs.getString("api_key_ciphertext", null))
        assertEquals(secret, settings.apiKey)
        settings.apiKey = ""
        assertFalse(settings.hasApiKey)
        assertEquals("", AiSettings(context, namespace).apiKey)
        assertFalse(prefs.contains("api_key_ciphertext"))
    }

    @Test fun tamperedCiphertextNeverFallsBackToPlaintext() {
        settings.apiKey = "fixture-secret"
        context.getSharedPreferences("ai_settings_$namespace", Context.MODE_PRIVATE).edit()
            .putString("api_key_ciphertext", "corrupt_ciphertext").commit()
        assertThrows(IllegalStateException::class.java) { settings.apiKey }
        settings.apiKey = "replacement-fixture"
        assertEquals("replacement-fixture", settings.apiKey)
    }

    @Test fun historyPersistsDeleteAndExportPreserveExactlyRemainingMessages() {
        val first = repository.add("user", "测试第一条\n中文内容")
        val second = repository.add("assistant", "测试第二条")
        val reopened = ChatRepository(context, "chat-instrumentation-fixture.db")
        assertEquals(listOf(first, second), reopened.messages())
        reopened.deleteMessage(first.id)
        assertEquals(listOf(second), repository.messages())
        val exported = JSONObject(repository.exportConversation()).getJSONArray("messages")
        assertEquals(1, exported.length())
        assertEquals(second.id, exported.getJSONObject(0).getLong("id"))
        assertEquals(second.createdAt, exported.getJSONObject(0).getLong("createdAt"))
        assertEquals(second.content, exported.getJSONObject(0).getString("content"))
        repository.clear()
        assertTrue(reopened.messages().isEmpty())
    }

    @Test fun upgradePreservesOldConversationAndStoresOnlyOneReplyPerVoiceTurn() {
        val name = "legacy-voice-instrumentation-fixture.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL)")
            db.execSQL("INSERT INTO messages VALUES (7, 'user', '旧的聊天消息', 1234)")
            db.execSQL("INSERT INTO memories VALUES (3, '原来的记忆')")
            db.version = 1
        }
        val updated = ChatRepository(context, name)
        try {
            assertEquals(listOf(ChatMessage(7L, "user", "旧的聊天消息", 1234L)), updated.messages())
            assertEquals(listOf(Memory(3L, "原来的记忆")), updated.memories())
            val user = updated.add("user", "语音问题", voice = true)
            val answer = updated.saveReply(user.id, "完成后的真实回复")!!
            assertTrue(answer.voice)
            assertNull(updated.saveReply(user.id, "迟到的重复回复"))
            val pending = updated.add("user", "已删除的问题", voice = true)
            updated.deleteMessage(pending.id)
            assertNull(updated.saveReply(pending.id, "已删除后返回的回复"))
            val reopened = ChatRepository(context, name)
            assertEquals(listOf(user, answer), reopened.messages().drop(1))
            val items = JSONObject(reopened.exportConversation()).getJSONArray("messages")
            assertEquals(3, items.length())
            assertFalse(items.getJSONObject(0).has("voice"))
            assertTrue(items.getJSONObject(1).getBoolean("voice"))
            assertTrue(items.getJSONObject(2).getBoolean("voice"))
        } finally {
            updated.clear()
            updated.clearMemories()
        }
    }

    @Test fun memoriesPersistEditDeleteExportAndClear() {
        val id = repository.saveMemory(text = "想学习陶艺")
        val second = repository.saveMemory(text = "喜欢散步")
        val reopened = ChatRepository(context, "chat-instrumentation-fixture.db")
        assertEquals(2, reopened.memories().size)
        assertEquals(id, reopened.saveMemory(id, "正在学习陶艺"))
        repository.deleteMemory(second)
        val memory = repository.memories().single()
        assertEquals(Memory(id, "正在学习陶艺"), memory)
        val exported = JSONObject(repository.exportMemories()).getJSONArray("memories")
        assertEquals(1, exported.length())
        assertEquals(memory.text, exported.getJSONObject(0).getString("text"))
        assertThrows(IllegalArgumentException::class.java) { repository.saveMemory(second, "已删除的条目") }
        reopened.clearMemories()
        assertTrue(repository.memories().isEmpty())
    }

    @Test fun memoryConsentDeletionAndDisableControlEveryFuturePayload() {
        val privateText = "这是一条只用于测试的私密记忆"
        val id = repository.saveMemory(text = privateText)
        val user = repository.add("user", "今天聊聊生活")
        fun payload() = String(ChatRequestFactory.create("https://example.org/v1", "test-model", "test-key",
            repository.messages(), repository.memories(), settings.memoryEnabled, userMessageId = user.id).payload, Charsets.UTF_8)
        assertFalse(settings.memoryEnabled)
        assertFalse(payload().contains(privateText))
        settings.memoryEnabled = true
        assertTrue(payload().contains(privateText))
        repository.deleteMemory(id)
        assertFalse(payload().contains(privateText))
        repository.saveMemory(text = privateText)
        settings.memoryEnabled = false
        assertFalse(payload().contains(privateText))
        repository.clearMemories()
        settings.memoryEnabled = true
        assertFalse(payload().contains(privateText))
        repository.deleteMessage(user.id)
        assertThrows(ChatException::class.java) { payload() }
    }
}
