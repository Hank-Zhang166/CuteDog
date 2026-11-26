package com.cutedog.app.chat

import android.content.Context
import com.cutedog.app.pet.PetSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** OpenAI-compatible client. No network, encryption or database work runs on the animation thread. */
class ChatClient(context: Context) {
    private val app = context.applicationContext
    private val transport = ChatTransport()

    companion object {
        private val active = MutableStateFlow(emptySet<Long>())
        internal val pendingReplies = active.asStateFlow()
    }

    internal suspend fun replyAndSave(repository: ChatRepository, id: Long, onText: (String) -> Unit): ChatReply? {
        // Both chat windows and the desktop microphone share the same database and dispatcher.
        if (id in active.getAndUpdate { it + id }) return null
        try {
            val history = withContext(Dispatchers.IO) {
                if (repository.canReplyTo(id)) repository.messages().takeWhile { it.id <= id } else null
            } ?: return null
            val reply = reply(history, id, onText)
            val saved = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                repository.saveReply(id, reply.text)
            }
            return reply.takeIf { saved != null }
        } finally { active.update { it - id } }
    }

    suspend fun reply(history: List<ChatMessage>, userMessageId: Long? = null, onText: (String) -> Unit = {}): ChatReply {
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
        return transport.execute(request, onText)
    }
}
