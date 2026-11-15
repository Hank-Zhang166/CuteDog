package com.cutedog.app.pet.animation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.Executors

fun interface FrameAssetSource { fun open(path: String): InputStream }

data class FrameCacheStats(val allocatedBytes: Long, val budgetBytes: Long, val cachedFrames: Int, val pendingFrames: Int, val decodedFrames: Long, val reusedBuffers: Long)

/** Single background decoder, bounded mutable buffers, and pinned display frames. No UI disk I/O. */
class BitmapFrameLoader(
    private val source: FrameAssetSource,
    private val width: Int,
    private val height: Int,
    private val budgetBytes: Long = MAX_BYTES,
) : Closeable {
    private val lock = Any()
    private val cache = LinkedHashMap<String, Bitmap>(32, .75f, true)
    private val pending = linkedSetOf<String>()
    private val errors = mutableMapOf<String, String>()
    private var pinned = emptySet<String>()
    private var decoding: String? = null
    private var working = false
    private var closed = false
    private var allocated = 0L
    private var decoded = 0L
    private var reused = 0L
    private val frameBytes = width.toLong() * height * 4
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "CuteDog-frame-decode").apply { isDaemon = true } }

    init {
        require(width in 16..1024 && height in 16..1024)
        require(budgetBytes in (frameBytes * 4)..MAX_BYTES) { "帧缓存至少容纳四帧且不超过 24 MiB" }
    }

    /** Returned pixels are read-only and valid while pinned. The player pins its current/previous frames. */
    fun ready(path: String): Bitmap? = synchronized(lock) { cache[path] }
    fun readyAndPin(path: String, retainedPaths: Set<String>): Bitmap? = synchronized(lock) {
        val bitmap = cache[path] ?: return@synchronized null
        require(retainedPaths.size <= 3 && path in retainedPaths)
        pinned = retainedPaths
        bitmap
    }
    fun error(path: String): String? = synchronized(lock) { errors[path] }
    fun pin(paths: Set<String>) = synchronized(lock) {
        require(paths.size <= 3) { "只可固定当前及两个最近显示帧" }
        pinned = paths
    }
    fun prefetch(paths: List<String>) = synchronized(lock) {
        if (closed) return@synchronized
        pending.clear()
        paths.take(24).filterTo(pending) { it !in cache && it != decoding && it !in errors }
        if (!working && pending.isNotEmpty()) {
            working = true
            worker.execute(::drain)
        }
    }
    fun cancelPrefetch() = synchronized(lock) { pending.clear() }
    fun stats(): FrameCacheStats = synchronized(lock) {
        FrameCacheStats(allocated, budgetBytes, cache.size, pending.size + if (decoding != null) 1 else 0, decoded, reused)
    }

    private fun drain() {
        while (true) {
            val path: String
            val reuse: Bitmap?
            synchronized(lock) {
                if (closed || pending.isEmpty()) { working = false; return }
                path = pending.first()
                if (allocated + frameBytes <= budgetBytes) {
                    reuse = null; allocated += frameBytes
                } else {
                    val evict = cache.entries.firstOrNull { it.key !in pinned }
                    if (evict == null) { working = false; return }
                    reuse = evict.value
                    cache.remove(evict.key)
                }
                pending.remove(path); decoding = path
            }
            var bitmap: Bitmap? = null
            var failure: String? = null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                source.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth == width && bounds.outHeight == height) { "角色帧尺寸与清单不符" }
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                    inScaled = false
                    if (reuse != null) inBitmap = reuse
                }
                bitmap = source.open(path).use { BitmapFactory.decodeStream(it, null, options) }
                    ?: throw IllegalArgumentException("角色帧无法解码")
                require(reuse == null || bitmap === reuse) { "平台未复用约定的角色帧缓冲" }
                require(bitmap.width == width && bitmap.height == height && bitmap.allocationByteCount <= frameBytes) { "角色帧内存超出预算" }
            } catch (error: Exception) {
                failure = "角色资源不可用：$path（${if (error is java.io.IOException) "读取失败" else "帧数据无效"}）"
            }
            synchronized(lock) {
                decoding = null
                if (closed || failure != null) {
                    bitmap?.recycle()
                    if (reuse !== bitmap) reuse?.recycle()
                    allocated -= frameBytes
                    if (failure != null) errors[path] = failure
                } else {
                    cache[path] = bitmap!!
                    decoded++
                    if (reuse != null) reused++
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true; pending.clear()
            // Do not recycle the current GPU/display frame during view teardown.
            cache.clear(); pinned = emptySet()
            allocated = if (decoding == null) 0 else frameBytes
        }
        worker.shutdownNow()
    }
    companion object { const val MAX_BYTES: Long = 24L * 1024 * 1024 }
}
