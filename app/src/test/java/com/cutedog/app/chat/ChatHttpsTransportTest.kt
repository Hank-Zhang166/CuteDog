package com.cutedog.app.chat

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Actual loopback HTTPS I/O against explicit fixture responses; never used by the production UI. */
class ChatHttpsTransportTest {
    private lateinit var server: LocalHttpsFixture
    private lateinit var ssl: SSLContext
    private val workers = Executors.newCachedThreadPool { Thread(it, "https-fixture").apply { isDaemon = true } }
    private var handler: (LocalHttpsFixture.Exchange) -> Unit = { respond(it, 200, fixtureReply()) }
    private val requestCount = AtomicInteger()

    @Before fun startServer() {
        val store = KeyStore.getInstance("PKCS12").apply {
            ChatHttpsTransportTest::class.java.classLoader!!.getResourceAsStream("chat/localhost.p12").use { load(it, "cutedog-test-only".toCharArray()) }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "cutedog-test-only".toCharArray()) }
        val trusted = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        ssl = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trusted.trustManagers, null) }
        server = LocalHttpsFixture(ssl, workers)
        server.createContext("/v1/chat/completions") { exchange ->
            requestCount.incrementAndGet()
            handler(exchange)
        }
        server.start()
    }

    @After fun stopServer() { server.stop(0); workers.shutdownNow() }

    private fun request(): ChatRequest = ChatRequestFactory.create("https://localhost:${server.address.port}/v1", "fixture-model", "fixture-secret-key",
        listOf(ChatMessage(1, "user", "中文网络测试", 1)), emptyList(), false)
    private fun transport(read: Int = 2000, deadline: Long = 4000) = ChatTransport(2000, read, deadline) { url ->
        (url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply { sslSocketFactory = ssl.socketFactory }
        // Default hostname verifier is retained. Trust only the test fixture cert, never trust-all.
    }
    private fun fixtureReply(content: String = "fixture-response <action:happy>") =
        JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()
    private fun respond(exchange: LocalHttpsFixture.Exchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test fun roundTripUsesPostJsonAuthAndParsesValidatedAction() = runBlocking {
        val captured = AtomicReference<JSONObject>()
        handler = { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer fixture-secret-key", exchange.requestHeaders.getFirst("Authorization"))
            assertTrue(exchange.requestHeaders.getFirst("Content-Type").startsWith("application/json"))
            captured.set(JSONObject(exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()))
            respond(exchange, 200, fixtureReply())
        }
        assertEquals(ChatReply("fixture-response", "happy"), transport().execute(request()))
        assertEquals("中文网络测试", captured.get().getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(1, requestCount.get())
    }

    @Test fun redirectDoesNotForwardCredentialOrFollowDestination() = runBlocking {
        val redirected = AtomicInteger()
        server.createContext("/leak") { redirected.incrementAndGet(); it.close() }
        handler = { it.responseHeaders.set("Location", "https://localhost:${server.address.port}/leak"); respond(it, 307, "") }
        val error = runCatching { transport().execute(request()) }.exceptionOrNull()
        assertTrue(error is ChatException)
        assertTrue(error!!.message!!.contains("重定向"))
        assertEquals(0, redirected.get())
    }

    @Test fun providerErrorBodyAndCredentialsNeverEnterDisplayedError() = runBlocking {
        for (status in listOf(401, 403, 404, 429, 503)) {
            handler = { respond(it, status, "fixture-secret-key private user content") }
            val error = runCatching { transport().execute(request()) }.exceptionOrNull()
            assertTrue("HTTP $status", error is ChatException)
            assertFalse(error!!.message!!.contains("fixture-secret-key"))
            assertFalse(error.message!!.contains("private user content"))
        }
    }

    @Test fun invalidJsonNullContentAndUnboundedBodiesAreRejected() = runBlocking {
        for (body in listOf("not-json", "{\"choices\":[]}", "{\"choices\":[{\"message\":{\"content\":null}}]}", "x".repeat(262145))) {
            handler = { respond(it, 200, body) }
            assertTrue(runCatching { transport().execute(request()) }.exceptionOrNull() is ChatException)
        }
    }

    @Test fun readTimeoutAndOverallDeadlineTerminateRequests() = runBlocking {
        handler = { exchange -> Thread.sleep(1200); runCatching { respond(exchange, 200, fixtureReply()) } }
        val timeout = runCatching { transport(read = 150).execute(request()) }.exceptionOrNull()
        assertTrue(timeout is ChatException)
        assertTrue(timeout!!.message!!.contains("超时"))
        val deadline = runCatching { transport(read = 2000, deadline = 200).execute(request()) }.exceptionOrNull()
        assertTrue(deadline is ChatException)
        assertTrue(deadline!!.message!!.contains("时限"))
    }

    @Test fun userCancellationCannotReturnOrPersistLateResponse() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = AtomicInteger()
        handler = { exchange -> arrived.countDown(); release.await(2, TimeUnit.SECONDS); runCatching { respond(exchange, 200, fixtureReply()) } }
        val job = launch { transport().execute(request()); delivered.incrementAndGet() }
        assertTrue(withContext(Dispatchers.IO) { arrived.await(3, TimeUnit.SECONDS) })
        val cancelStarted = System.nanoTime()
        job.cancel()
        assertTrue("Cancellation must not block the caller's UI thread", (System.nanoTime() - cancelStarted) / 1_000_000 < 500)
        release.countDown()
        job.join()
        assertEquals(0, delivered.get())
        assertTrue(job.isCancelled)
    }

    @Test fun systemTrustRejectsFixtureCertificateWithoutExplicitTestPinning() = runBlocking {
        val productionTrust = ChatTransport(2000, 2000, 4000) { it.openConnection(Proxy.NO_PROXY) as HttpsURLConnection }
        val error = runCatching { productionTrust.execute(request()) }.exceptionOrNull()
        assertTrue(error is ChatException)
        assertTrue(error!!.message!!.contains("安全连接"))
    }

    @Test fun connectionFailureHasHonestOfflineError() = runBlocking {
        val port = server.address.port
        server.stop(0)
        val error = runCatching { transport().execute(ChatRequest("https://localhost:$port/v1/chat/completions", "fixture-key", "{}".toByteArray(), false)) }.exceptionOrNull()
        assertTrue(error is ChatException)
        assertTrue(error!!.message!!.contains("无法连接"))
    }
}
