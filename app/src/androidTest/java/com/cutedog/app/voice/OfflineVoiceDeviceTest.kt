package com.cutedog.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.PetChatActivity
import com.cutedog.app.chat.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*

class OfflineVoiceDeviceTest {
    private val device get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = device.targetContext

    @Test fun silenceDoesNotTriggerAChat() = runBlocking {
        assertEquals("", OfflineSpeechInput.transcribe(context, ByteArray(32_000)))
    }

    @Test fun realMicrophoneCanRecordStopAndRestartInForeground() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val permission = Manifest.permission.RECORD_AUDIO
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            device.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").close()
            repeat(20) { if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) Thread.sleep(50) }
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
        ActivityScenario.launch(PetChatActivity::class.java).use {
            runBlocking {
                repeat(2) {
                    val capture = MicCapture.start(context, this)
                    try { delay(450); assertTrue(capture.finish().size >= 3200) }
                    finally { capture.end() }
                }
            }
        }
    }

    @Test fun mandarinAudioToHttpsAndSpokenReply() {
        val settings = AiSettings(context)
        val repository = ChatRepository(context)
        assumeTrue((Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) &&
            !settings.hasApiKey && repository.messages().isEmpty())
        val original = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        val originalSettings = original.all
        val consent = context.getSharedPreferences("ai_ui_consent", Context.MODE_PRIVATE)
        val wasConsented = consent.all["remote_chat"] as? Boolean
        val pass = "cutedog-test-only".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            device.context.assets.open("localhost.p12").use { load(it, pass) }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, pass) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        val prior = HttpsURLConnection.getDefaultSSLSocketFactory()
        val workers = Executors.newCachedThreadPool()
        val server = LocalHttpsFixture(tls, workers)
        val asked = AtomicReference("")
        val answer = "我也喜欢你呀！"
        val expected = "我喜欢小狗"
        try {
            server.createContext("/chat/completions") { exchange ->
                val body = JSONObject(exchange.requestBody.bufferedReader().readText()).getJSONArray("messages")
                asked.set(body.getJSONObject(body.length() - 1).getString("content"))
                val response = """{"choices":[{"message":{"content":"$answer <action:happy>"}}]}""".toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.write(response)
            }
            server.start()
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            settings.endpoint = "https://localhost:${server.address.port}"
            settings.model = "loopback-voice"
            settings.apiKey = "local-qa-only"
            settings.memoryEnabled = false
            consent.edit().putBoolean("remote_chat", true).commit()
            ActivityScenario.launch(PetChatActivity::class.java).use { scenario ->
                lateinit var session: VoiceConversation
                val status = mutableListOf<String>()
                scenario.onActivity { session = VoiceConversation(it, status::add) }
                try {
                    val pcm = device.context.assets.open("like-dogs-16k.pcm").use { it.readBytes() }
                    runBlocking { withTimeout(120_000) { withContext(Dispatchers.Main) { session.processAudio(pcm) } } }
                    assertEquals(expected, asked.get())
                    assertEquals(listOf(expected, answer), repository.messages().map { it.content })
                    assertEquals(answer, status.last())
                } finally { scenario.onActivity { session.close() } }
            }
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(prior)
            server.stop(0); workers.shutdownNow()
            repository.messages().filter { it.content == asked.get() || it.content == answer }.forEach { repository.deleteMessage(it.id) }
            original.edit().apply {
                for (key in listOf("api_key_ciphertext", "endpoint", "model", "memory_enabled")) when (val value = originalSettings[key]) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    null -> remove(key)
                }
            }.commit()
            consent.edit().apply { if (wasConsented == null) remove("remote_chat") else putBoolean("remote_chat", wasConsented) }.commit()
        }
    }
}
