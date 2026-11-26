package com.cutedog.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import com.cutedog.app.chat.ChatRepository
import com.cutedog.app.voice.doubao.DoubaoRealtimeClient
import com.cutedog.app.voice.doubao.DoubaoSettings
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

/** Only the WebSocket peer is replaced. The overlay still uses Android's AudioRecord and AudioTrack. */
internal class OverlayVoiceTestFixture(private val app: Context) : AutoCloseable {
    private val settings = DoubaoSettings(app)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val history = ChatRepository(app)
    private var originalIds = emptySet<Long>()
    private var installed = false
    private val question = "糯米，听得见吗？"
    val answer = "听得见，我在这里呀！"
    val frames = AtomicInteger()
    val commits = AtomicInteger()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
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
                    "session.create" -> deliver("session.created")
                    "input_audio_buffer.append" -> {
                        check(decoder.decode(event.getString("audio")).size == 640)
                        frames.incrementAndGet()
                    }
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
                        deliver("response.output_audio.delta", "delta", encoder.encodeToString(pcm))
                        deliver("response.output_audio.done")
                        deliver("response.done")
                    }
                    "session.close" -> deliver("session.closed")
                }
                return true
            }
        }
    })

    fun install() {
        assumeTrue("Grant microphone access on the emulator first",
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        assumeTrue("Do not replace existing credentials", !settings.hasVoiceKey && !settings.hasSearchKey)
        assumeTrue("No competing microphone capture", !hasRecording())
        originalIds = history.messages().map { it.id }.toSet()
        installed = true
        settings.save("test-overlay-key", "")
        settings.microphoneUploadAccepted = true
    }

    fun hasRecording(unsilenced: Boolean = false): Boolean = audio.activeRecordingConfigurations.any {
        it.clientAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION &&
            it.clientFormat.sampleRate == MicCapture.RATE && (!unsilenced || !it.isClientSilenced)
    }

    fun hasSavedTurn(): Boolean = history.messages().filter { it.id !in originalIds && it.voice }
        .map { it.content } == listOf(question, answer)

    override fun close() {
        if (!installed) return
        settings.clear()
        history.messages().filter { it.id !in originalIds && (it.content == question || it.content == answer) }
            .forEach { history.deleteMessage(it.id) }
    }
}
