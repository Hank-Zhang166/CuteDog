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
    val travelBlocked: Boolean = false,
    val travelBlockedReason: String? = null,
    val rejectedTravelTarget: String? = null,
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
    private var pendingHeading: Int? = null
    private var headingAfterRequest = false
    @Volatile private var active = true
    @Volatile private var memoryPressure = false
    private var restoreAfterActiveSeconds: Double? = null
    private val cadence = FrameCadence()
    private val recent = java.util.ArrayDeque<String>()
    private var currentBitmap: Bitmap? = null
    private var snapshot = ClipPlaybackFrame(null, null, 0, true, null)
    val manifest: ClipManifest? get() = loaded.get()?.manifest
    val currentClip: String? get() = director?.clipId
    val requestedClip: String? get() = director?.requestedClip ?: pendingRequest
    val canonicalClipId: String? get() = director?.canonicalClipId
    val hasReservedTravelSequence: Boolean get() = director?.hasReservedTravelSequence == true
    val canInterrupt: Boolean get() = director?.canInterrupt ?: false
    val canTravel: Boolean get() = !closed && active && snapshot.bitmap != null &&
        !snapshot.loading && !snapshot.travelBlocked && snapshot.error == null && snapshot.clipId == director?.clipId && director?.canTravel == true
    val travelHeading: Int get() = if (canTravel) director?.travelHeading ?: 0 else 0
    /** Signed canvas-width fraction for the last tick's successfully displayed frame advance. */
    var authoredTravelDeltaFraction: Double = 0.0; private set
    val stoppingTravelFraction: Double? get() = director?.stoppingTravelFraction
    val maximumStepFraction: Double get() = director?.maximumStepFraction ?: 0.0
    val requiredTickRate: Int get() = currentClip?.let { manifest?.clips?.get(it)?.fps } ?: 24
    fun nextFrameDelayMillis(elapsedSinceVsyncNanos: Long = 0L): Long {
        val retry = when {
            startupError.get() != null || snapshot.error != null || snapshot.travelBlocked -> 250L
            loaded.get() == null -> 32L
            snapshot.loading -> 16L // A lightweight cache check at the next vsync; decoding remains off-thread.
            else -> return cadence.delayUntilNextFrameMillis(requiredTickRate, elapsedSinceVsyncNanos)
        }
        return (retry - elapsedSinceVsyncNanos.coerceAtLeast(0L) / 1_000_000L).coerceAtLeast(0L)
    }

    init {
        val assets = context.applicationContext.assets
        initWorker.execute {
            try {
                val json = ClipIntegrity.readLimited(assets.open(ClipManifest.ASSET_PATH), ClipIntegrity.MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                val manifest = ClipManifest.parse(json, requiredClipIds = ReleasedArtwork.REQUIRED_ACTIONS)
                val source = FrameAssetSource { assets.open(it) }
                PhaseProofVerifier.verify(manifest, source)
                // Validate all declared filenames once, on the loader thread, before claiming readiness.
                for (clip in manifest.clips.values) {
                    val files = assets.list("pet3d/${clip.id}").orEmpty().toSet()
                    require(clip.frames.all { it.substringAfterLast('/') in files }) { "${clip.id} 缺少清单声明的实际动画帧" }
                }
                val loader = BitmapFrameLoader(source, manifest.width, manifest.height,
                    frameDigests = manifest.frameDigests, verificationDependencies = manifest.verificationDependencies)
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
        if (control == null) {
            val manifest = loaded.get()?.manifest
            if (manifest != null && manifest.requestableName(clipId) == null) return false
            pendingRequest = clipId; headingAfterRequest = false
            return true
        }
        val accepted = control.request(clipId)
        if (accepted) { authoredTravelDeltaFraction = 0.0; startupError.set(null); if (active) loaded.get()?.loader?.prefetch(control.prefetchPaths()) }
        return accepted
    }

    fun requestHeading(sign: Int): Boolean {
        if (closed || ClipHeading.fromSign(sign) == null) return false
        val control = director
        if (control == null) {
            if (pendingHeading == sign) return true
            val manifest = loaded.get()?.manifest
            if (manifest != null && manifest.headingIdle(ClipHeading.fromSign(sign)!!) == null) return false
            pendingHeading = sign; headingAfterRequest = true
            return true
        }
        val accepted = control.requestHeading(sign)
        if (accepted) {
            authoredTravelDeltaFraction = 0.0
            if (active) loaded.get()?.loader?.prefetch(control.prefetchPaths())
        }
        return accepted
    }
    fun requestStop(): Boolean {
        if (closed) return false
        val accepted = director?.requestStop() == true
        if (accepted && active) director?.let { loaded.get()?.loader?.prefetch(it.prefetchPaths()) }
        return accepted
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value; cadence.reset()
        authoredTravelDeltaFraction = 0.0
        if (!value) {
            restoreAfterActiveSeconds = null
            loaded.get()?.loader?.cancelPrefetch()
        } else restoreAfterActiveSeconds = 0.0
    }

    fun trimMemory() {
        memoryPressure = true; restoreAfterActiveSeconds = null
        authoredTravelDeltaFraction = 0.0
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

    fun tick(deltaSeconds: Double, approveTravel: (Double) -> Boolean = { true },
        approveEntry: (Int, Double) -> Boolean = { _, _ -> true }): ClipPlaybackFrame {
        authoredTravelDeltaFraction = 0.0
        if (closed) return snapshot
        val resources = loaded.get()
        if (resources == null) return ClipPlaybackFrame(null, null, 0, startupError.get() == null, startupError.get()).also { snapshot = it }
        val control = director ?: ClipDirector(resources.manifest).also { created ->
            director = created
            fun applyHeading() { pendingHeading?.let { if (!created.requestHeading(it)) startupError.set("角色资源缺少所需真实朝向") } }
            if (!headingAfterRequest) applyHeading()
            pendingRequest?.let { target -> if (!created.request(target)) startupError.set("动作不存在或缺少所需朝向：$target") }
            if (headingAfterRequest) applyHeading()
            pendingRequest = null; pendingHeading = null
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
        if (snapshot.travelBlocked) {
            // A deliberate spatial pause is not a slow display callback and earns no catch-up time.
            cadence.reset(); cadence.add(1.0 / fps, fps)
        } else cadence.add(deltaSeconds, fps)
        var waiting = false
        var travelBlocked = false
        var rejectedTravelTarget: String? = null
        var error: String? = startupError.get()
        control.upcomingPhaseBridge()?.frames?.take(2)?.firstNotNullOfOrNull(loader::error)?.let { error = it }
        if (cadence.due(fps)) {
            val plan = control.planNext(loader::isReady)
            val next = plan.next
            val pins = (recent.filter { it != next.path } + next.path).takeLast(3).toSet()
            val pixels = loader.readyAndPin(next.path, pins)
            if (pixels != null) {
                val travel = if (error != null) null else control.commitIfTravelAllowed(plan, approveTravel, approveEntry)
                rejectedTravelTarget = if (error == null) control.rejectedTravelTarget else null
                if (travel == null) {
                    // Keep both pose and position. Never truncate a measured step while showing
                    // the advanced feet, and never accumulate catch-up time behind a boundary.
                    travelBlocked = error == null; cadence.blocked(fps)
                } else {
                    recent.remove(next.path); recent.add(next.path)
                    while (recent.size > 3) recent.removeFirst()
                    currentBitmap = pixels; cadence.consumed(fps)
                    authoredTravelDeltaFraction = travel
                }
            } else {
                cadence.blocked(fps)
                waiting = true; error = loader.error(next.path) ?: error
            }
        }
        loader.prefetch(control.prefetchPaths())
        return ClipPlaybackFrame(currentBitmap, control.clipId, control.frameIndex, waiting && error == null, error,
            if (cadence.hostTooSlow) "当前渲染回调不足 ${fps}fps，请保持足够刷新频率或选择实际低帧率资源" else null,
            travelBlocked, if (rejectedTravelTarget != null) "水平空间不足以完成实测启动及停步；行走目标已拒绝并保留安全回程"
                else if (travelBlocked) "当前空间或交互状态不允许完整实测步幅；画面和位置同时暂停" else null,
            rejectedTravelTarget).also { snapshot = it }
    }
    fun cacheStats(): FrameCacheStats? = loaded.get()?.loader?.stats()
    override fun close() {
        closed = true; active = false
        authoredTravelDeltaFraction = 0.0
        loaded.getAndSet(null)?.loader?.close()
        initWorker.shutdownNow()
        currentBitmap = null; recent.clear(); director = null
        snapshot = ClipPlaybackFrame(null, null, 0, false, null)
    }
}
