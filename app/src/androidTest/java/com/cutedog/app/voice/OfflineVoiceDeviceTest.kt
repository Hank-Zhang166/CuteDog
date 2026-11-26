package com.cutedog.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.cutedog.app.MainActivity
import com.cutedog.app.PetChatActivity
import com.cutedog.app.chat.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

class OfflineVoiceDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val device get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = device.targetContext

    @Test fun silenceDoesNotTriggerAChat() = runBlocking {
        assumeTrue(com.cutedog.app.BuildConfig.LEGACY_SPEECH)
        assertEquals("", OfflineSpeechInput.transcribe(context, ByteArray(32_000)))
    }

    @Test fun liveMandarinBlocksMatchTheRecordingAndCancelledAudioIsDiscarded() = runBlocking {
        assumeTrue(com.cutedog.app.BuildConfig.LEGACY_SPEECH)
        val recording = device.context.assets.open("like-dogs-16k.pcm").use { it.readBytes() }
        val cancelled = OfflineSpeechInput.listen(context,this)
        cancelled.offer(recording,3200)
        cancelled.cancel()
        assertTrue(runCatching { cancelled.finish() }.exceptionOrNull() is CancellationException)
        val listener = OfflineSpeechInput.listen(context,this)
        try {
            var offset = 0
            while (offset < recording.size) {
                val end = minOf(offset+3200,recording.size)
                listener.offer(recording.copyOfRange(offset,end))
                delay((end-offset)*1000L/(MicCapture.RATE*2))
                offset = end
            }
            assertEquals("我喜欢小狗",listener.finish())
        } finally { listener.cancel() }
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
        assumeTrue("This build includes the optional legacy model", com.cutedog.app.BuildConfig.LEGACY_SPEECH)
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
        val followUp = "我们来玩球吧。"
        val followUpAnswer = "好呀，我来接！"
        val requests = AtomicInteger()
        fun showMessage(value: String) =
            compose.onNodeWithTag("chat_messages", useUnmergedTree = true).performScrollToNode(hasText(value))
        fun foregroundLines(): Map<String, Rect> {
            val root = device.uiAutomation.rootInActiveWindow ?: return emptyMap()
            if (root.packageName?.toString() != context.packageName) return emptyMap()
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            val visible = mutableMapOf<String, Rect>()
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val text = node.text?.toString()
                if (node.isVisibleToUser && !text.isNullOrBlank()) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.isEmpty) visible[text] = bounds
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            }
            return visible
        }
        fun waitForMessage(value: String) {
            var lastObservation = "row not yet saved"
            try {
                compose.waitUntil(25_000) {
                    val id = repository.messages().singleOrNull { it.content == value }?.id ?: return@waitUntil false
                    // A LazyColumn can be in the middle of auto-scrolling after the DB flow emits.
                    // Seek its stable row key, then inspect the visible text in Android's own window.
                    val result = runCatching {
                        val list = compose.onNodeWithTag("chat_messages", useUnmergedTree = true)
                        list.performScrollToKey(id)
                        val bubble = hasText(value) and hasAnyAncestor(hasTestTag("chat_messages"))
                        compose.onNode(bubble, useUnmergedTree = true).assertIsDisplayed()
                    }
                    val visible = foregroundLines()
                    if (result.isFailure || value !in visible) {
                        lastObservation = "id=$id; Compose=${result.exceptionOrNull()?.message?.take(500) ?: "visible"}; visible=${visible.keys}"
                        false
                    } else {
                        android.util.Log.i("CuteDogUiEvidence","Voice chat row $id visible in Compose and Android at ${visible[value]}")
                        true
                    }
                }
            } catch (failure: ComposeTimeoutException) {
                val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 12) }.getOrNull()
                throw AssertionError("Saved chat bubble was not visible: $value; $lastObservation; tree=${tree?.take(4500)}", failure)
            }
        }
        try {
            server.createContext("/chat/completions") { exchange ->
                val body = JSONObject(exchange.requestBody.bufferedReader().readText()).getJSONArray("messages")
                asked.set(body.getJSONObject(body.length() - 1).getString("content"))
                if (requests.incrementAndGet() == 2) {
                    exchange.sendResponseHeaders(503, 0L)
                    return@createContext
                }
                val voiceRequest = asked.get() == expected
                val response = if (voiceRequest) {
                    listOf("我也", answer.removePrefix("我也"), " <action:happy>").joinToString("") { fragment ->
                        "data: {\"choices\":[{\"delta\":{\"content\":${JSONObject.quote(fragment)}}}]}\n\n"
                    } + "data: [DONE]\n\n"
                } else """{"choices":[{"message":{"content":"$followUpAnswer <action:happy>"}}]}"""
                exchange.responseHeaders.set("Content-Type", if (voiceRequest) "text/event-stream" else "application/json")
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
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
                scenario.onActivity {
                    it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    session = VoiceConversation(it, status::add)
                }
                try {
                    val pcm = device.context.assets.open("like-dogs-16k.pcm").use { it.readBytes() }
                    // Keep the chat visible as a desktop microphone session writes through its own repository instance.
                    runBlocking { withContext(Dispatchers.Main) { session.processAudio(ByteArray(32_000)) } }
                    assertTrue(repository.messages().isEmpty())
                    assertEquals(0, requests.get())
                    runBlocking { withTimeout(360_000) { withContext(Dispatchers.Main) { session.processAudio(pcm) } } }
                    if (android.os.Process.is64Bit()) {
                        assertEquals("The local A voice must finish reading the saved reply", answer, status.last())
                    } else {
                        assertTrue(status.last().startsWith(answer) && "64" in status.last())
                    }
                    assertEquals(expected, asked.get())
                    val saved = repository.messages()
                    assertEquals(listOf(expected, answer), saved.map { it.content })
                    assertTrue(saved.all { it.voice })
                    waitForMessage(answer)
                    showMessage("你 · 语音输入")
                    waitForMessage(expected)
                } finally { scenario.onActivity { session.close() } }
            }
            val mainChat = Intent(context, MainActivity::class.java).putExtra("open_chat", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(mainChat).use {
                waitForMessage(answer)
                waitForMessage(expected)
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithText("连接你的模型").assertExists()
                compose.onNodeWithText("安装 A 声线语音包").assertDoesNotExist()
                compose.onNodeWithText("关闭").performScrollTo().performClick()
                compose.onNodeWithTag("chat_input").performTextInput(followUp)
                compose.onNodeWithTag("chat_send").performClick()
                compose.waitUntil(15_000) { requests.get() == 2 && ChatClient.pendingReplies.value.isEmpty() }
                showMessage("重试上一条")
                assertEquals(listOf(expected, answer, followUp), repository.messages().map { m -> m.content })
                compose.onNodeWithText("重试上一条").performClick()
                waitForMessage(followUpAnswer)
            }
            ActivityScenario.launch(PetChatActivity::class.java).use {
                waitForMessage(followUpAnswer)
                waitForMessage(expected)
                waitForMessage(answer)
                val saved = ChatRepository(context).messages()
                assertEquals(listOf(expected, answer, followUp, followUpAnswer), saved.map { m -> m.content })
                assertEquals(listOf(true, true, false, false), saved.map { m -> m.voice })
                assertEquals(3, requests.get())
            }
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(prior)
            server.stop(0); workers.shutdownNow()
            val testMessages = setOf(expected, answer, followUp, followUpAnswer)
            repository.messages().filter { it.content in testMessages }.forEach { repository.deleteMessage(it.id) }
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
