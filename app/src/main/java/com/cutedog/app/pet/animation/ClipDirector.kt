package com.cutedog.app.pet.animation

/** Small immutable reference; contains no pixels and is cheap to allocate. */
data class ClipFrame(val clipId: String, val index: Int, val path: String)

/** Main-thread control only. Never skips authored intermediate poses or advances a missing frame. */
class ClipDirector(val manifest: ClipManifest) {
    var clipId: String = manifest.initialClip; private set
    var frameIndex: Int = 0; private set
    var requestedClip: String = manifest.initialClip; private set
    private val route = java.util.ArrayDeque<String>()
    val current: ClipFrame get() = ref(clipId, frameIndex)
    val queuedClips: List<String> get() = route.toList()
    val canInterrupt: Boolean get() = manifest.clips.getValue(clipId).let {
        frameIndex == it.frames.lastIndex || (it.interrupt == ClipInterrupt.IMMEDIATE && frameIndex in it.safeExitFrames)
    }

    fun request(target: String): Boolean {
        val path = manifest.route(clipId, target) ?: return false
        route.clear(); route.addAll(path); requestedClip = target
        return true
    }

    fun peekNext(): ClipFrame {
        val clip = manifest.clips.getValue(clipId)
        val safeEarlyExit = clip.interrupt == ClipInterrupt.IMMEDIATE && frameIndex in clip.safeExitFrames
        if (route.isNotEmpty() && (frameIndex == clip.frames.lastIndex || safeEarlyExit)) return ref(route.first, 0)
        if (frameIndex < clip.frames.lastIndex) return ref(clipId, frameIndex + 1)
        if (clip.loop) return ref(clipId, 0)
        val home = manifest.route(clipId, manifest.initialClip) ?: return current
        return home.firstOrNull()?.let { ref(it, 0) } ?: current
    }

    /** Caller commits only after the exact frame is decoded; slow I/O pauses rather than invents frames. */
    fun commit(next: ClipFrame) {
        require(next == peekNext()) { "不能跳过动作帧或绕过姿态转移" }
        if (next.clipId != clipId) {
            if (route.firstOrNull() == next.clipId) route.removeFirst()
            else { // One-shot completed without a new request: retain the entire authored return route.
                route.clear()
                route.addAll(manifest.route(clipId, manifest.initialClip).orEmpty().drop(1))
            }
        }
        clipId = next.clipId; frameIndex = next.index
    }

    /** Prioritized lookahead without mutating playback. Includes the next clip's first frames. */
    fun prefetchPaths(lookAhead: Int = 8): List<String> {
        val clip = manifest.clips.getValue(clipId)
        val result = linkedSetOf(current.path, peekNext().path)
        for (step in 1..lookAhead) {
            val index = frameIndex + step
            if (index <= clip.frames.lastIndex) result += clip.frames[index]
            else if (clip.loop && route.isEmpty()) result += clip.frames[index % clip.frames.size]
        }
        val nextClip = route.firstOrNull() ?: if (!clip.loop) manifest.route(clipId, manifest.initialClip)?.firstOrNull() else null
        nextClip?.let { result.addAll(manifest.clips.getValue(it).frames.take(3)) }
        return result.toList()
    }
    private fun ref(id: String, frame: Int) = ClipFrame(id, frame, manifest.clips.getValue(id).frames[frame])
}
