package com.cutedog.app.chat

import java.io.InterruptedIOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Production uses platform TLS trust and hostname verification. Test-only injection pins a local fixture certificate. */
internal class ChatTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 45_000,
    private val deadlineMs: Long = 60_000,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    init { require(connectTimeoutMs > 0 && readTimeoutMs > 0 && deadlineMs > 0) }

    suspend fun execute(request: ChatRequest, onText: (String) -> Unit = {}): ChatReply = withTimeoutOrNull(deadlineMs) { exchange(request, onText) }
        ?: throw ChatException("AI 回复超过请求时限，已取消本次请求。请稍后重试。")

    private suspend fun exchange(request: ChatRequest, onText: (String) -> Unit): ChatReply = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpURLConnection?>()
        val future = NETWORK.submit {
            if (!continuation.isActive) return@submit
            try {
                val connection = connectionFactory(URL(request.url))
                active.set(connection)
                if (!continuation.isActive) { connection.disconnect(); return@submit }
                connection.apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = false // Never forward Authorization to a redirect host.
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "text/event-stream, application/json")
                    setRequestProperty("Authorization", "Bearer ${request.key}")
                    setFixedLengthStreamingMode(request.payload.size)
                }
                connection.outputStream.use { it.write(request.payload) }
                val status = connection.responseCode
                if (status !in 200..299) throw ChatException(when (status) {
                    401, 403 -> "AI 服务拒绝访问，请检查 API Key、模型权限及账户状态。"
                    404 -> "未找到模型接口，请检查服务地址与模型名称。"
                    429 -> "AI 服务限流或账户额度不足，请稍后重试并检查服务账户。"
                    in 300..399 -> "服务返回了重定向。为保护密钥，请在设置中填写最终 HTTPS 接口地址。"
                    in 500..599 -> "AI 服务暂时不可用（HTTP $status），请稍后再试。"
                    else -> "AI 请求未被接受（HTTP $status），请检查服务是否兼容聊天接口。"
                })
                val reply = connection.inputStream.use { input ->
                    ChatResponseReader.read(input, connection.contentType) { preview ->
                        if (!continuation.isActive) throw InterruptedIOException()
                        onText(preview)
                    }
                }.copy(contextTruncated = request.contextTruncated)
                if (continuation.isActive) continuation.resume(reply)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(when (e) {
                    is ChatException -> e
                    is SocketTimeoutException -> ChatException("连接 AI 服务超时，请检查网络后重试。")
                    is javax.net.ssl.SSLException -> ChatException("无法验证服务的安全连接，请检查 HTTPS 地址和证书。")
                    is java.io.IOException -> ChatException("无法连接 AI 服务，请检查网络与服务地址。")
                    else -> ChatException("无法读取 AI 回复，请检查服务是否兼容 OpenAI 聊天接口。")
                })
            } finally {
                active.getAndSet(null)?.disconnect()
            }
        }
        continuation.invokeOnCancellation {
            // Some HttpURLConnection implementations synchronize disconnect with a blocking read.
            // Never run that work on the caller's UI cancellation thread.
            active.getAndSet(null)?.let { connection -> CLEANUP.execute { runCatching { connection.disconnect() } } }
            future.cancel(true)
        }
    }

    private companion object {
        val NETWORK = Executors.newFixedThreadPool(2) { task -> Thread(task, "CuteDog-AI").apply { isDaemon = true } }
        val CLEANUP = Executors.newSingleThreadExecutor { task -> Thread(task, "CuteDog-AI-close").apply { isDaemon = true } }
    }
}
