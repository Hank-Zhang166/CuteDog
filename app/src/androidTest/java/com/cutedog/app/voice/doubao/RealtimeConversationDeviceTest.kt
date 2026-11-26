package com.cutedog.app.voice.doubao

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.PetChatActivity
import com.cutedog.app.chat.ChatRepository
import com.cutedog.app.voice.RealtimeVoiceConversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

class RealtimeConversationDeviceTest {
    @Test fun microphoneAndPlaybackKeepBothRowsInSharedHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        assumeTrue(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val settings = DoubaoSettings(app)
        assumeTrue("Do not replace existing credentials", !settings.hasVoiceKey && !settings.hasSearchKey)
        val history = ChatRepository(app)
        val oldIds = history.messages().map { it.id }.toSet()
        val question = "小狗在吗？"
        val answer = "我在这里呀！"
        val finished = CompletableDeferred<Unit>()
        val commits = AtomicInteger()
        val audioFrames = AtomicInteger()
        val client = DoubaoRealtimeClient(WebSocket.Factory { request, listener ->
            object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun cancel() = Unit
                override fun close(code: Int, reason: String?) = true
                override fun send(bytes: ByteString) = false
                private fun deliver(type: String, key: String = "", value: String = "") {
                    val event = JSONObject().put("type", type)
                    if (key.isNotEmpty()) event.put(key, value)
                    listener.onMessage(this, event.toString())
                }

                override fun send(text: String): Boolean {
                    val event = JSONObject(text)
                    when (event.getString("type")) {
                        "session.create" -> {
                            val search = event.getJSONObject("extension").getJSONObject("dialog").getJSONObject("extra")
                            assertTrue(search.getBoolean("enable_volc_websearch"))
                            deliver("session.created")
                        }
                        "input_audio_buffer.append" -> audioFrames.incrementAndGet()
                        "input_audio_buffer.commit" -> {
                            commits.incrementAndGet()
                            deliver("conversation.item.input_audio_transcription.completed", "transcript", question)
                            deliver("response.output_text.done", "text", answer)
                            val pcm = ByteArray(9600)
                            for (frame in 0 until pcm.size / 2) {
                                val sample = (sin(2 * Math.PI * frame / 54.0) * 1800).toInt()
                                pcm[frame * 2] = sample.toByte()
                                pcm[frame * 2 + 1] = (sample shr 8).toByte()
                            }
                            deliver("response.output_audio.delta", "delta", Base64.getEncoder().encodeToString(pcm))
                            deliver("response.output_audio.done")
                            deliver("response.done")
                        }
                        "session.close" -> deliver("session.closed")
                    }
                    return true
                }
            }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            settings.save("test-voice-key", "test-search-key")
            settings.microphoneUploadAccepted = true
            ActivityScenario.launch(PetChatActivity::class.java).use { scenario ->
                lateinit var conversation: RealtimeVoiceConversation
                var answerUpdates = 0
                scenario.onActivity { activity ->
                    conversation = RealtimeVoiceConversation(activity, scope, { status ->
                        if (status == answer && ++answerUpdates == 2) finished.complete(Unit)
                    }, { error("Fixture must already have credentials") }, client)
                    assertTrue(conversation.start())
                }
                try {
                    runBlocking {
                        withTimeout(8_000) {
                            while (audioFrames.get() < 8) delay(20)
                        }
                    }
                    assertEquals(0, commits.get())
                    assertFalse(finished.isCompleted)
                    scenario.onActivity { assertTrue(conversation.submit()) }
                    runBlocking { withTimeout(8_000) { finished.await() } }
                    assertEquals(1, commits.get())
                    val newRows = history.messages().filterNot { it.id in oldIds }
                    assertEquals(listOf(question, answer), newRows.map { it.content })
                    assertTrue(newRows.all { it.voice })
                } finally { scenario.onActivity { conversation.close() } }
            }
        } finally {
            scope.cancel()
            settings.clear()
            history.messages().filter { it.id !in oldIds && (it.content == question || it.content == answer) }
                .forEach { history.deleteMessage(it.id) }
        }
    }
}
