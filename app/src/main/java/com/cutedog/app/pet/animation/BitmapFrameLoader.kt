package com.cutedog.app.pet.animation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.Executors

fun interface FrameAssetSource { fun open(path: String): InputStream }

/** allocatedBytes counts managed pixel buffers/reservations, not total process/native/GPU RSS. */
data class FrameCacheStats(
    val allocatedBytes: Long,
    val budgetBytes: Long,
    val cachedFrames: Int,
    val pendingFrames: Int,
    val decodedFrames: Long,
    val reusedBuffers: Long,
    val workingBudgetBytes: Long,
    val activeDecoder: Boolean,
    val trimCount: Long,
    val trimmedBytes: Long,
    val discardedFrames: Long,
)

/** Single background decoder, bounded mutable buffers, and pinned display frames. No UI disk I/O. */
class BitmapFrameLoader(
    private val source: FrameAssetSource,
    private val width: Int,
    private val height: Int,
    private val budgetBytes: Long = MAX_BYTES,
    frameDigests: Map<String, String> = emptyMap(),
    verificationDependencies: Map<String, List<AssetDigest>> = emptyMap(),
    private val mirroredPaths: Set<String> = emptySet(),
) : Closeable {
    private class Entry(val bitmap: Bitmap, var everDisplayed: Boolean = false)
    private val lock = Any()
    private val cache = LinkedHashMap<String, Entry>(32, .75f, true)
    private val pending = linkedSetOf<String>()
    private val desired = linkedSetOf<String>()
    private val errors = mutableMapOf<String, String>()
    private var pinned = emptySet<String>()
    private var decoding: String? = null
    private var working = false
    private var closed = false
    private var generation = 0L
    private var allocated = 0L
    private var decoded = 0L
    private var reused = 0L
    private var trims = 0L
    private var trimmed = 0L
    private var discarded = 0L
    private var workingBudget = budgetBytes
    private val frameBytes = width.toLong() * height * 4
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "CuteDog-frame-decode").apply { isDaemon = true } }
    private val frameDigests = frameDigests.toMap()
    private val verificationDependencies = verificationDependencies.mapValues { it.value.toList() }
    private val verifiedDependencies = mutableSetOf<AssetDigest>() // Decoder-thread only; APK assets are immutable.
    private val encoded = EncodedFrame()
    // Scratch is decoder-owned, at most 64 KiB at the released 512px resolution.
    private val mirrorRows by lazy { IntArray(width * minOf(height, 32)) }

    init {
        require(width in 16..1024 && height in 16..1024)
        require(budgetBytes in (frameBytes * 4)..MAX_BYTES) { "帧缓存至少容纳四帧且不超过 24 MiB" }
        require(this.frameDigests.values.all(ClipIntegrity.SHA256::matches)) { "角色帧摘要格式无效" }
    }

    /** Returned pixels are read-only and valid while pinned. Renderer must not retain old frames. */
    fun ready(path: String): Bitmap? = synchronized(lock) {
        cache[path]?.also { it.everDisplayed = true }?.bitmap
    }
    fun isReady(path: String): Boolean = synchronized(lock) { path in cache }
    fun readyAndPin(path: String, retainedPaths: Set<String>): Bitmap? = synchronized(lock) {
        val entry = cache[path] ?: return@synchronized null
        require(retainedPaths.size <= 3 && path in retainedPaths)
        pinned = retainedPaths
        entry.everDisplayed = true
        entry.bitmap
    }
    fun error(path: String): String? = synchronized(lock) { errors[path] }
    fun pin(paths: Set<String>) = synchronized(lock) {
        require(paths.size <= 3) { "只可固定当前及两个最近显示帧" }
        pinned = paths
    }
    fun prefetch(paths: List<String>) = synchronized(lock) {
        if (closed) return@synchronized
        val capacity = (workingBudget / frameBytes).toInt()
        desired.clear(); desired.addAll(pinned)
        // Protect the entire requested working set. A small cache must not evict its next frame
        // while decoding lower-priority lookahead and then endlessly decode it again.
        for (path in paths.take(24)) {
            if (desired.size >= capacity) break
            desired.add(path)
        }
        pending.clear()
        desired.filterTo(pending) { it !in cache && it != decoding && it !in errors }
        if (!working && pending.isNotEmpty()) {
            working = true
            worker.execute(::drain)
        }
    }

    /** One in-flight decode may finish, but a cancelled generation cannot refill the cache. */
    fun cancelPrefetch() = synchronized(lock) {
        generation++; pending.clear(); desired.clear()
    }

    /** Retain pinned display frames; never forcibly recycle a bitmap handed to the renderer. */
    fun trimMemory() = synchronized(lock) {
        if (closed) return@synchronized
        generation++; pending.clear(); desired.clear()
        workingBudget = minOf(budgetBytes, frameBytes * 6)
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.key !in pinned) {
                release(item.value)
                iterator.remove()
                allocated -= frameBytes; trimmed += frameBytes
            }
        }
        trims++
    }

    fun restoreMemoryBudget() = synchronized(lock) { workingBudget = budgetBytes }
    fun stats(): FrameCacheStats = synchronized(lock) {
        FrameCacheStats(allocated, budgetBytes, cache.size, pending.size + if (decoding != null) 1 else 0,
            decoded, reused, workingBudget, decoding != null, trims, trimmed, discarded)
    }

    private fun drain() {
        while (true) {
            val path: String
            val reuse: Entry?
            val decodeGeneration: Long
            synchronized(lock) {
                if (closed || pending.isEmpty()) { working = false; return }
                path = pending.first()
                if (allocated + frameBytes <= workingBudget) {
                    reuse = null; allocated += frameBytes
                } else {
                    val evict = cache.entries.firstOrNull { it.key !in pinned && it.key !in desired }
                    if (evict == null) { working = false; return }
                    reuse = evict.value
                    cache.remove(evict.key)
                }
                pending.remove(path); decoding = path; decodeGeneration = generation
            }
            var bitmap: Bitmap? = null
            var failure: String? = null
            try {
                for (dependency in verificationDependencies[path].orEmpty()) if (dependency !in verifiedDependencies) {
                    ClipIntegrity.verifyWhileReading(source.open(dependency.path), dependency.sha256) { }
                    verifiedDependencies += dependency
                }
                encoded.load(source.open(path), frameDigests[path])
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                encoded.decode(bounds)
                require(bounds.outWidth == width && bounds.outHeight == height) { "角色帧尺寸与清单不符" }
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                    inScaled = false
                    if (reuse != null) inBitmap = reuse.bitmap
                }
                bitmap = encoded.decode(options)
                requireNotNull(bitmap) { "角色帧无法解码" }
                require(reuse == null || bitmap === reuse.bitmap) { "平台未复用约定的角色帧缓冲" }
                require(bitmap!!.width == width && bitmap!!.height == height && bitmap!!.allocationByteCount <= frameBytes) { "角色帧内存超出预算" }
                if (path in mirroredPaths) reflect(bitmap!!)
            } catch (error: Exception) {
                failure = "角色资源不可用：$path（${if (error is java.io.IOException) "读取失败" else "帧数据无效"}）"
            }
            synchronized(lock) {
                decoding = null
                if (failure == null) {
                    decoded++
                    if (reuse != null) reused++
                }
                if (closed || failure != null || generation != decodeGeneration || (path !in desired && path !in pinned)) {
                    bitmap?.let { release(Entry(it, reuse?.everDisplayed == true)) }
                    if (reuse?.bitmap !== bitmap) reuse?.let(::release)
                    allocated -= frameBytes
                    discarded++
                    if (failure != null) errors[path] = failure
                } else {
                    cache[path] = Entry(bitmap!!, reuse?.everDisplayed == true)
                }
            }
        }
    }

    private fun reflect(bitmap: Bitmap) {
        val pixels = mirrorRows
        val rows = pixels.size / width
        for (y in 0 until height step rows) {
            val count = minOf(rows, height - y)
            bitmap.getPixels(pixels, 0, width, 0, y, width, count)
            for (row in 0 until count) {
                var left = row * width
                var right = left + width - 1
                while (left < right) {
                    val value = pixels[left]
                    pixels[left++] = pixels[right]
                    pixels[right--] = value
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, y, width, count)
        }
    }

    private fun release(entry: Entry) {
        // Previously displayed frames may still be referenced by a GPU display list. Drop our
        // strong reference and let Android reclaim them; managed-byte reduction is not RSS proof.
        if (!entry.everDisplayed && !entry.bitmap.isRecycled) entry.bitmap.recycle()
    }

    override fun close() {
        synchronized(lock) {
            closed = true; generation++; pending.clear(); desired.clear()
            cache.values.forEach(::release)
            cache.clear(); pinned = emptySet()
            allocated = if (decoding == null) 0 else frameBytes
        }
        worker.shutdownNow()
    }
    // The decoder owns one reusable compressed buffer. Bounds and pixels always
    // come from the very same verified bytes, including when inBitmap is reused.
    private class EncodedFrame : ByteArrayOutputStream(64 * 1024) {
        private val block = ByteArray(8192)

        fun load(input: InputStream, expected: String?) {
            reset()
            input.use { stream ->
                while (true) {
                    val n = stream.read(block)
                    if (n < 0) break
                    if (n == 0) continue
                    require(count <= ClipIntegrity.MAX_FRAME_BYTES - n)
                    write(block, 0, n)
                }
            }
            if (expected != null) require(ClipIntegrity.sha256(buf, count) == expected) { "角色资源摘要不符" }
        }

        fun decode(options: BitmapFactory.Options): Bitmap? =
            BitmapFactory.decodeByteArray(buf, 0, count, options)
    }

    companion object { const val MAX_BYTES: Long = 24L * 1024 * 1024 }
}
