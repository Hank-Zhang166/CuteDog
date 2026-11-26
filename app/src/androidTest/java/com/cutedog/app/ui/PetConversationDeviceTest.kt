package com.cutedog.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.cutedog.app.PetChatActivity
import com.cutedog.app.chat.*
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PetConversationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun desktopConversationSendsPersonaSpeaksAndReopensTheSavedAnswer() {
        org.junit.Assume.assumeTrue("This build includes the optional legacy model", com.cutedog.app.BuildConfig.LEGACY_SPEECH)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = AiSettings(context)
        val repository = ChatRepository(context)
        assumeTrue("Use a disposable emulator with empty chat and no API key",
            (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) &&
                !settings.hasApiKey && repository.messages().isEmpty())
        val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        val consent = context.getSharedPreferences("ai_ui_consent", Context.MODE_PRIVATE)
        val originalPrefs = prefs.all
        val originalConsent = if (consent.contains("remote_chat")) consent.getBoolean("remote_chat", false) else null
        val password = "cutedog-test-only".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            instrumentation.context.assets.open("localhost.p12").use { load(it, password) }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        val priorTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val workers = Executors.newCachedThreadPool()
        val server = LocalHttpsFixture(tls, workers)
        val sent = AtomicReference<JSONObject>()
        val firstChunk = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val question = "可以把红球拿给我吗？"
        val first = "叼回来啦！"
        val answer = first + "我把球放在你手边，再来一次？"
        try {
            fun event(value: String): String = "data: " + JSONObject().put("choices", JSONArray()
                .put(JSONObject().put("delta", JSONObject().put("content", value)))) + "\n\n"
            server.createContext("/chat/completions") { exchange ->
                sent.set(JSONObject(exchange.requestBody.bufferedReader().readText()))
                val lead = event(first).toByteArray()
                val tail = (event(answer.removePrefix(first) + " <ac") + event("tion:happy>") + "data: [DONE]\n\n").toByteArray()
                exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
                exchange.sendResponseHeaders(200, (lead.size + tail.size).toLong())
                exchange.responseBody.write(lead)
                exchange.responseBody.flush()
                firstChunk.countDown()
                if (allowFinish.await(15, TimeUnit.SECONDS)) exchange.responseBody.write(tail)
            }
            server.start()
            // Pin only a private loopback fixture, retaining Android's hostname verification.
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            settings.endpoint = "https://localhost:${server.address.port}"
            settings.model = "local-fixture"
            settings.memoryEnabled = false
            settings.apiKey = "local-qa-only"
            consent.edit().putBoolean("remote_chat", false).commit()
            ActivityScenario.launch(PetChatActivity::class.java).use {
                compose.onNodeWithTag("chat_input").performTextInput(question)
                compose.onNodeWithTag("chat_send").performClick()
                compose.onNodeWithText("同意并继续").performClick()
                assertTrue("Loopback SSE request not received", firstChunk.await(12, TimeUnit.SECONDS))
                val payload = sent.get().getJSONArray("messages")
                assertEquals("system", payload.getJSONObject(0).getString("role"))
                assertTrue(payload.getJSONObject(0).getString("content").contains(ChatPolicy.PERSONA))
                assertEquals(question, payload.getJSONObject(1).getString("content"))
                if (ValueAnimator.areAnimatorsEnabled() &&
                    !context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled) {
                    compose.waitUntil(8_000) { compose.onAllNodesWithText("叼回", substring = true).fetchSemanticsNodes().isNotEmpty() }
                }
                assertTrue(repository.messages().none { row -> row.role == "assistant" })
                allowFinish.countDown()
                compose.waitUntil(10_000) { compose.onAllNodesWithText(answer).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(answer, repository.messages().single { row -> row.role == "assistant" }.content)
                compose.onNodeWithText("收起聊天").performClick()
            }
            ActivityScenario.launch(PetChatActivity::class.java).use {
                compose.waitUntil(5_000) { compose.onAllNodesWithText(answer).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("chat_speaking").assertDoesNotExist()
                val file = File(context.getExternalFilesDir(null), "qa-conversation-window.png")
                instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                    try { file.outputStream().use { out -> screenshot.compress(Bitmap.CompressFormat.PNG, 100, out) } }
                    finally { screenshot.recycle() }
                }
            }
        } finally {
            allowFinish.countDown()
            HttpsURLConnection.setDefaultSSLSocketFactory(priorTls)
            server.stop(0)
            workers.shutdownNow()
            repository.messages().filter { it.content == question || it.content == answer }.forEach { repository.deleteMessage(it.id) }
            prefs.edit().apply {
                for (key in listOf("endpoint", "model", "api_key_ciphertext", "memory_enabled")) {
                    when (val value = originalPrefs[key]) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        null -> remove(key)
                    }
                }
            }.commit()
            consent.edit().apply {
                if (originalConsent == null) remove("remote_chat") else putBoolean("remote_chat", originalConsent)
            }.commit()
        }
    }
}
