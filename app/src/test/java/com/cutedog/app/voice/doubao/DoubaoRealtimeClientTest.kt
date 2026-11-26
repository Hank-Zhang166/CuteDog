package com.cutedog.app.voice.doubao

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DoubaoRealtimeClientTest {
    @Test fun negotiatedSearchAndAudioArePartOfTheSession() {
        val session = DoubaoRealtimeProtocol.createSession(
            "你是糯米。",
            listOf(DoubaoHistory("user", "喂"), DoubaoHistory("assistant", "汪"),
                DoubaoHistory("user", "未完成的提问")),
            "fixture-search-key",
        )
        val config = session.getJSONObject("session")
        assertEquals("1.2.6.1", config.getString("model"))
        val format = config.getJSONObject("audio").getJSONObject("output").getJSONObject("format")
        assertEquals("pcm_s16le", format.getString("type"))
        assertEquals(24_000, format.getInt("rate"))
        val dialog = session.getJSONObject("extension").getJSONObject("dialog")
        val search = dialog.getJSONObject("extra")
        assertTrue(search.getBoolean("enable_volc_websearch"))
        assertEquals("web_custom_api", search.getString("volc_websearch_type"))
        assertEquals("fixture-search-key", search.getString("volc_websearch_api_key"))
        assertEquals(2, dialog.getJSONArray("dialog_context").length())
    }

    @Test fun partialMicReadsAreFramedAndAnEarlyReplyWaitsForRelease() = runBlocking {
        val frames = Channel<ByteArray>(4)
        val events = mutableListOf<DoubaoEvent>()
        val fixture = SpeechFixture()
        val ready = CompletableDeferred<Unit>()
        val row = launch {
            DoubaoRealtimeClient(fixture).converse("fixture-speech", "", "你是糯米。", emptyList(), frames) {
                events.add(it)
                if (it == DoubaoEvent.Ready) ready.complete(Unit)
            }
        }
        try {
            withTimeout(5_000) {
                ready.await()
                frames.send(ByteArray(270) { 1 })
                frames.send(ByteArray(384) { 2 })
                fixture.earlyReplySent.await()
                assertEquals(listOf(DoubaoEvent.Ready), events)
                frames.close()
                row.join()
            }
        } finally {
            row.cancel()
        }
        assertEquals(listOf("session.create", "input_audio_buffer.append", "input_audio_buffer.append",
            "input_audio_buffer.commit", "input_audio_mute.commit", "session.close"), fixture.commands)
        assertEquals("fixture-speech", fixture.key)
        assertArrayEquals(ByteArray(270) { 1 } + ByteArray(370) { 2 }, fixture.audioFrames[0])
        assertArrayEquals(ByteArray(14) { 2 } + ByteArray(626), fixture.audioFrames[1])
        assertEquals(listOf(DoubaoEvent.Transcript("小狗好", true)), events.filterIsInstance<DoubaoEvent.Transcript>())
        assertEquals(listOf(DoubaoEvent.Reply("我来啦！", true)), events.filterIsInstance<DoubaoEvent.Reply>())
        assertArrayEquals(byteArrayOf(1, 2), events.filterIsInstance<DoubaoEvent.Audio>().single().pcm)
        assertFalse(fixture.commands.contains("response.cancel"))
    }

    @Test fun aProviderErrorDoesNotPrintRemoteMessagesOrCredentials() {
        val failure = runCatching {
            DoubaoRealtimeProtocol.read(
                """{"type":"error","error":{"code":42000020,"message":"secret token, raw request"}}"""
            )
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("42000020"))
        assertFalse(failure?.message.orEmpty().contains("secret"))
    }

    private class SpeechFixture : WebSocket.Factory {
        val commands = mutableListOf<String>()
        val audioFrames = mutableListOf<ByteArray>()
        val earlyReplySent = CompletableDeferred<Unit>()
        var key: String? = null
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            key = request.header("X-Api-Key")
            return object : WebSocket {
                private fun receive(text: String) = listener.onMessage(this, text)
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = error("The official interface takes JSON text frames")
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val event = JSONObject(text)
                    val type = event.getString("type")
                    commands.add(type)
                    when (type) {
                        "session.create" -> receive("""{"type":"session.created","session":{"id":"fixture"}}""")
                        "input_audio_buffer.append" -> {
                            audioFrames.add(Base64.getDecoder().decode(event.getString("audio")))
                            if (audioFrames.size == 1) {
                                receive("""{"type":"conversation.item.input_audio_transcription.delta","delta":"小狗"}""")
                                receive("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"小狗好"}""")
                                receive("""{"type":"response.output_text.delta","delta":"我来"}""")
                                receive("""{"type":"response.output_text.done","text":"我来啦！"}""")
                                receive("""{"type":"response.output_audio.delta","delta":"AQI="}""")
                                receive("""{"type":"response.output_audio.done"}""")
                                receive("""{"type":"response.done"}""")
                                earlyReplySent.complete(Unit)
                            }
                        }
                        "session.close" -> receive("""{"type":"session.closed"}""")
                    }
                    return true
                }
            }
        }
    }
}
