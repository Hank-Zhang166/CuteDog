package com.cutedog.app.pet.animation

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Immutable metadata plus read-only currently pinned pixels. Do not retain bitmaps across ticks. */
data class ClipPlaybackFrame(
    val bitmap: Bitmap?,
    val clipId: String?,
    val frameIndex: Int,
    val loading: Boolean,
    val error: String?,
    val timingWarning: String? = null,
)

/**
 * Main-thread tick/request, background manifest validation + decode. Holds the last genuine frame
 * while the exact next frame loads, without advancing time, crossfading incompatible poses, or
 * replacing missing actions with unrelated artwork.
 */
class FrameClipPlayer(context: Context) : Closeable {
    private data class Loaded(val manifest: ClipManifest, val loader: BitmapFrameLoader)
    private val loaded = AtomicReference<Loaded?>()
    private val startupError = AtomicReference<String?>()
    private val initWorker = Executors.newSingleThreadExecutor { Thread(it, "CuteDog-clip-manifest").apply { isDaemon = true } }
    @Volatile private var closed = false
    private var director: ClipDirector? = null
    private var pendingRequest: String? = null
    @Volatile private var active = true
    @Volatile private var memoryPressure = false
    private var restoreAfterActiveSeconds: Double? = null
    private val cadence = FrameCadence()
    private val recent = java.util.ArrayDeque<String>()
    private var currentBitmap: Bitmap? = null
    private var snapshot = ClipPlaybackFrame(null, null, 0, true, null)
    val manifest: ClipManifest? get() = loaded.get()?.manifest
    val currentClip: String? get() = director?.clipId
    val canInterrupt: Boolean get() = director?.canInterrupt ?: false
    val requiredTickRate: Int get() = currentClip?.let { manifest?.clips?.get(it)?.fps } ?: 24

    init {
        val assets = context.applicationContext.assets
        initWorker.execute {
            try {
                val json = assets.open(ClipManifest.ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val manifest = ClipManifest.parse(json)
                // Validate all declared filenames once, on the loader thread, before claiming readiness.
                for (clip in manifest.clips.values) {
                    val files = assets.list("pet3d/${clip.id}").orEmpty().toSet()
                    require(clip.frames.all { it.substringAfterLast('/') in files }) { "${clip.id} 缺少清单声明的实际动画帧" }
                }
                val loader = BitmapFrameLoader(FrameAssetSource { assets.open(it) }, manifest.width, manifest.height)
                if (memoryPressure) loader.trimMemory()
                if (closed) loader.close() else {
                    loaded.set(Loaded(manifest, loader))
                    if (closed) loaded.getAndSet(null)?.loader?.close()
                    else if (active) loader.prefetch(manifest.clips.getValue(manifest.initialClip).frames.take(8))
                }
            } catch (error: Exception) {
                startupError.set("完整角色动画资源尚不可用：${error.message ?: "无法读取清单"}")
            } finally { initWorker.shutdown() }
        }
    }

    /** Returns false for unknown/unreachable clips. Before manifest readiness the request is queued. */
    fun request(clipId: String): Boolean {
        if (closed) return false
        val control = director
        if (control == null) { pendingRequest = clipId; return loaded.get()?.manifest?.clips?.containsKey(clipId) ?: true }
        val accepted = control.request(clipId)
        if (accepted) { startupError.set(null); if (active) loaded.get()?.loader?.prefetch(control.prefetchPaths()) }
        return accepted
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; cadence.reset()
        if (!value) {
            restoreAfterActiveSeconds = null
            loaded.get()?.loader?.cancelPrefetch()
        } else restoreAfterActiveSeconds = 0.0
    }

    fun trimMemory() {
        memoryPressure = true; restoreAfterActiveSeconds = null
        loaded.get()?.loader?.trimMemory()
        cadence.reset()
    }

    /** Explicit recovery, or automatic only after a genuine pause/resume stays active five seconds. */
    fun restoreMemoryBudget() {
        memoryPressure = false; restoreAfterActiveSeconds = null
        loaded.get()?.loader?.let { loader ->
            loader.restoreMemoryBudget()
            if (active) director?.let { loader.prefetch(it.prefetchPaths()) }
        }
    }

    fun tick(deltaSeconds: Double): ClipPlaybackFrame {
        if (closed) return snapshot
        val resources = loaded.get()
        if (resources == null) return ClipPlaybackFrame(null, null, 0, startupError.get() == null, startupError.get()).also { snapshot = it }
        val control = director ?: ClipDirector(resources.manifest).also { created ->
            director = created
            pendingRequest?.let { target -> if (!created.request(target)) startupError.set("动作不存在：$target") }
            pendingRequest = null
        }
        val loader = resources.loader
        val current = control.current
        if (currentBitmap == null) {
            loader.pin(setOf(current.path))
            currentBitmap = loader.ready(current.path)
            if (currentBitmap == null) {
                if (active) loader.prefetch(control.prefetchPaths())
                return ClipPlaybackFrame(null, control.clipId, control.frameIndex, loader.error(current.path) == null, loader.error(current.path)).also { snapshot = it }
            }
            recent.add(current.path)
        }
        if (!active) return ClipPlaybackFrame(currentBitmap, control.clipId, control.frameIndex, false, startupError.get()).also { snapshot = it }
        restoreAfterActiveSeconds?.let { seconds ->
            val elapsed = seconds + (deltaSeconds.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(.25) ?: 0.0)
            if (elapsed >= 5.0) restoreMemoryBudget() else restoreAfterActiveSeconds = elapsed
        }
        // No frame skipping after a stalled decoder or a long background pause.
        val fps = resources.manifest.clips.getValue(control.clipId).fps
        cadence.add(deltaSeconds, fps)
        var waiting = false
        var error: String? = startupError.get()
        if (cadence.due(fps)) {
            val next = control.peekNext()
            val pins = (recent.filter { it != next.path } + next.path).takeLast(3).toSet()
            val pixels = loader.readyAndPin(next.path, pins)
            if (pixels != null) {
                recent.remove(next.path); recent.add(next.path)
                while (recent.size > 3) recent.removeFirst()
                control.commit(next); currentBitmap = pixels; cadence.consumed(fps)
            } else {
                cadence.blocked(fps)
                waiting = true; error = loader.error(next.path) ?: error
            }
        }
        loader.prefetch(control.prefetchPaths())
        return ClipPlaybackFrame(currentBitmap, control.clipId, control.frameIndex, waiting && error == null, error,
            if (cadence.hostTooSlow) "当前渲染回调不足 ${fps}fps，请保持足够刷新频率或选择实际低帧率资源" else null).also { snapshot = it }
    }
    fun cacheStats(): FrameCacheStats? = loaded.get()?.loader?.stats()
    override fun close() {
        closed = true; active = false
        loaded.getAndSet(null)?.loader?.close()
        initWorker.shutdownNow()
        currentBitmap = null; recent.clear(); director = null
        snapshot = ClipPlaybackFrame(null, null, 0, false, null)
    }
}
