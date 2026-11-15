package com.cutedog.app.chat

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Minimal HTTP/1.1-over-TLS fixture, using real sockets and standard certificate verification. */
internal class LocalHttpsFixture(ssl: SSLContext, private val executor: ExecutorService) {
    private val socket = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
    private val handlers = ConcurrentHashMap<String, (Exchange) -> Unit>()
    private val clients = ConcurrentHashMap.newKeySet<SSLSocket>()
    init { socket.bind(InetSocketAddress("127.0.0.1", 0)) }
    val address: InetSocketAddress get() = socket.localSocketAddress as InetSocketAddress

    fun createContext(path: String, handler: (Exchange) -> Unit) { handlers[path] = handler }
    fun start() {
        executor.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() as SSLSocket } catch (_: SocketException) { break }
                clients.add(client)
                executor.execute {
                    try {
                        client.soTimeout = 5000
                        client.startHandshake()
                        Exchange.read(client).use { exchange ->
                            val handler = handlers[exchange.path]
                            if (handler != null) handler(exchange)
                            else exchange.sendResponseHeaders(404, 0)
                        }
                    } catch (_: java.io.IOException) {
                        // Expected when client rejects TLS, cancels a call, or reaches its timeout.
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        clients.remove(client)
                        runCatching { client.close() }
                    }
                }
            }
        }
    }
    fun stop(@Suppress("UNUSED_PARAMETER") delay: Int) {
        socket.close()
        clients.forEach { runCatching { it.close() } }
    }

    class Headers {
        private val values = linkedMapOf<String, String>()
        fun set(name: String, value: String) { values[name.lowercase(Locale.ROOT)] = value }
        fun getFirst(name: String): String = values[name.lowercase(Locale.ROOT)] ?: ""
        fun entries() = values.entries
    }
    class Exchange private constructor(
        private val socket: SSLSocket,
        val requestMethod: String,
        val path: String,
        val requestHeaders: Headers,
        val requestBody: InputStream,
    ) : Closeable {
        val responseHeaders = Headers()
        val responseBody: OutputStream get() = socket.outputStream
        fun sendResponseHeaders(status: Int, length: Long) {
            val header = buildString {
                append("HTTP/1.1 $status Fixture\r\n")
                append("Content-Length: $length\r\nConnection: close\r\n")
                responseHeaders.entries().forEach { append("${it.key}: ${it.value}\r\n") }
                append("\r\n")
            }
            responseBody.write(header.toByteArray(Charsets.US_ASCII))
            responseBody.flush()
        }
        override fun close() { socket.close() }
        companion object {
            fun read(socket: SSLSocket): Exchange {
                val input = socket.inputStream
                val first = line(input).split(' ')
                require(first.size >= 3) { "Malformed fixture request" }
                val headers = Headers()
                while (true) {
                    val line = line(input)
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    require(colon > 0)
                    headers.set(line.substring(0, colon), line.substring(colon + 1).trim())
                }
                val length = headers.getFirst("Content-Length").toIntOrNull() ?: 0
                require(length in 0..1_048_576)
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(body, offset, length - offset)
                    if (read < 0) throw java.io.EOFException()
                    offset += read
                }
                return Exchange(socket, first[0], first[1], headers, ByteArrayInputStream(body))
            }
            private fun line(input: InputStream): String {
                val value = StringBuilder()
                while (value.length < 16_384) {
                    val next = input.read()
                    if (next < 0) throw java.io.EOFException()
                    if (next == 10) return value.toString().removeSuffix("\r")
                    value.append(next.toChar())
                }
                error("Fixture HTTP header too large")
            }
        }
    }
}
