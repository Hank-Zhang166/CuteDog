package com.cutedog.app.chat

import android.content.Context
import com.cutedog.app.pet.PetSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** OpenAI-compatible client. No network, encryption or database work runs on the animation thread. */
class ChatClient(context: Context) {
    private val app = context.applicationContext
    private val transport = ChatTransport()

    suspend fun reply(history: List<ChatMessage>, userMessageId: Long? = null): ChatReply {
        val request = withContext(Dispatchers.IO) {
            val settings = AiSettings(app)
            val key = try { settings.apiKey } catch (e: IllegalStateException) {
                throw ChatException(e.message ?: "请重新配置 API Key")
            }
            val useMemory = settings.memoryEnabled
            ChatRequestFactory.create(
                endpoint = settings.endpoint, model = settings.model, apiKey = key,
                history = history,
                memories = if (useMemory) ChatRepository(app).memories() else emptyList(),
                memoryEnabled = useMemory, name = PetSettings(app).name,
                userMessageId = userMessageId,
            )
        }
        return transport.execute(request)
    }
}
