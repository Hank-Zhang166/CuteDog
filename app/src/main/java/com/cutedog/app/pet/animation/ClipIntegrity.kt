package com.cutedog.app.pet.animation

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest

data class AssetDigest(val path: String, val sha256: String)
data class ClipTerminalProof(val path: String, val sha256: String, val stateSha256: String)
data class PhaseEntry(
    val sourceClip: String,
    val atFrame: Int,
    val sourceClipSha256: String,
    val sourceFrameSha256: String,
    val entryStateSha256: String,
    val proof: AssetDigest,
)

/** Shared producer/runtime byte contract. Hashing and file reads belong on worker threads. */
object ClipIntegrity {
    val SHA256 = Regex("[0-9a-f]{64}")
    const val MAX_MANIFEST_BYTES = 2_000_000
    const val MAX_REPORT_BYTES = 512 * 1024
    const val MAX_FRAME_BYTES = 16 * 1024 * 1024

    fun sha256(bytes: ByteArray): String = sha256(bytes, bytes.size)
    fun sha256(bytes: ByteArray, count: Int): String {
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(bytes, 0, count)
        return hex(hash.digest())
    }
    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val digits = "0123456789abcdef"
        for (byte in bytes) { val value = byte.toInt() and 255; append(digits[value ushr 4]); append(digits[value and 15]) }
    }

    /** Canonical JSON with expanded defaults supplied by contentSha256, no exponent or -0. */
    fun canonicalJson(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Number -> {
            require(value.toDouble().isFinite()) { "摘要数值必须有限" }
            BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        }
        is String -> buildString {
            append('"')
            for (char in value) when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 32) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
            }
            append('"')
        }
        is Map<*, *> -> value.keys.map { require(it is String); it }.sorted().joinToString(",", "{", "}") {
            canonicalJson(it) + ":" + canonicalJson(value[it])
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { canonicalJson(it) }
        else -> throw IllegalArgumentException("不支持的摘要记录类型")
    }

    fun contentSha256(width: Int, height: Int, clip: FrameClip): String {
        val record = mapOf(
            "schema" to "cutedog-clip-content-v2", "width" to width, "height" to height,
            "id" to clip.id, "fps" to clip.fps, "sampling" to clip.sampling,
            "entryPose" to clip.entryPose, "exitPose" to clip.exitPose, "loop" to clip.loop,
            "interrupt" to clip.interrupt.name.lowercase(), "transitions" to clip.transitions,
            "safeExitFrames" to clip.safeExitFrames.sorted(), "variantOf" to clip.variantOf,
            "heading" to clip.heading?.name?.lowercase(), "exitHeading" to clip.exitHeading?.name?.lowercase(),
            "frames" to clip.frames, "frameSha256" to clip.frameSha256, "travelUnits" to clip.travelUnits,
            "projectedTravel" to clip.projectedTravel, "canTravel" to clip.travelEnabled,
            "entryStateSha256" to clip.entryStateSha256, "exitStateSha256" to clip.exitStateSha256,
            "terminalProof" to clip.terminalProof?.let { mapOf("path" to it.path, "sha256" to it.sha256, "stateSha256" to it.stateSha256) },
        )
        val signed = if (clip.backwardTravel) record + ("backwardTravel" to true) else record
        return sha256(canonicalJson(signed).toByteArray(Charsets.UTF_8))
    }

    fun readLimited(input: InputStream, limit: Int): ByteArray = input.use {
        require(limit > 0)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val size = it.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (size < 0) break
            if (size == 0) { val next = it.read(); if (next < 0) break; output.write(next) }
            else output.write(buffer, 0, size)
            require(output.size() <= limit) { "资源文件超出大小限制" }
        }
        output.toByteArray()
    }

    /** Bounds decoding may stop early or skip PNG chunks; hash the entire bounded byte stream. */
    fun <T> verifyWhileReading(input: InputStream, expected: String, limit: Int = MAX_FRAME_BYTES,
        consume: (InputStream) -> T): T {
        return input.use { raw ->
            require(SHA256.matches(expected)) { "资源摘要格式无效" }
            val digest = MessageDigest.getInstance("SHA-256")
            val hashing = object : FilterInputStream(raw) {
                private var total = 0L
                private val skipped = ByteArray(8192)
                private fun account(count: Int) { if (count > 0) { total += count; require(total <= limit) { "资源文件超出大小限制" } } }
                override fun read(): Int = `in`.read().also { if (it >= 0) { account(1); digest.update(it.toByte()) } }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also {
                    if (it > 0) { account(it); digest.update(bytes, offset, it) }
                }
                override fun skip(count: Long): Long {
                    var remaining = count.coerceAtLeast(0)
                    while (remaining > 0) {
                        val size = read(skipped, 0, minOf(remaining, skipped.size.toLong()).toInt())
                        if (size < 0) break
                        if (size == 0) { if (read() < 0) break; remaining-- } else remaining -= size
                    }
                    return count.coerceAtLeast(0) - remaining
                }
            }
            BufferedInputStream(hashing, 8192).use { stream ->
                val result = consume(stream)
                val tail = ByteArray(8192)
                while (true) { val count = stream.read(tail); if (count < 0) break; if (count == 0 && stream.read() < 0) break }
                require(hex(digest.digest()) == expected) { "角色资源摘要与批准清单不符" }
                result
            }
        }
    }
}
