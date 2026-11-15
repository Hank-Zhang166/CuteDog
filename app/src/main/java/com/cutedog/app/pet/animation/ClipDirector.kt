package com.cutedog.app.pet.animation

/** Small immutable reference; contains no pixels and is cheap to allocate. */
data class ClipFrame(val clipId: String, val index: Int, val path: String)
data class TravelReservation(val heading: Int, val fraction: Double)

/** Main-thread control only. Never skips authored intermediate poses or advances a missing frame. */
class ClipDirector(val manifest: ClipManifest) {
    var clipId: String = manifest.initialClip; private set
    var frameIndex: Int = 0; private set
    var requestedClip: String = manifest.initialClip; private set
    var requestedHeading: ClipHeading? = manifest.clips.getValue(manifest.initialClip).heading
        ?: manifest.clips["stand"]?.heading; private set
    private val route = java.util.ArrayDeque<String>()
    var rejectedTravelTarget: String? = null; private set
    /** Includes an already-entered zero-travel startup; it is not permission for a new route. */
    var hasReservedTravelSequence: Boolean = false; private set
    val current: ClipFrame get() = ref(clipId, frameIndex)
    val queuedClips: List<String> get() = route.toList()
    val canonicalClipId: String get() = manifest.canonicalClipId(clipId)!!
    /** A bridge never reports its destination heading before its genuine final pose is reached. */
    val travelHeading: Int get() = manifest.clips.getValue(clipId).let { clip ->
        if (manifest.travelReady(clip.id) && clip.heading == clip.exitHeading) clip.heading?.sign ?: 0 else 0
    }
    val canTravel: Boolean get() = travelHeading != 0
    val stoppingTravelFraction: Double? get() = manifest.stoppingTravelFraction(clipId, frameIndex)
    val maximumStepFraction: Double get() = manifest.clips.getValue(clipId).projectedTravel.maxOrNull() ?: 0.0
    val canInterrupt: Boolean get() = manifest.clips.getValue(clipId).let {
        frameIndex == it.frames.lastIndex || (it.interrupt == ClipInterrupt.IMMEDIATE && frameIndex in it.safeExitFrames)
    }

    fun request(target: String): Boolean {
        val actual = manifest.resolveClip(target, requestedHeading) ?: return false
        val path = manifest.route(clipId, actual) ?: return false
        route.clear(); route.addAll(path); requestedClip = actual
        // Exact variant requests also carry an explicit direction; ordinary interactions retain
        // the next desired travel direction while the graph returns to their authored front pose.
        manifest.clips.getValue(target).takeIf { it.variantOf != null }?.heading?.let { requestedHeading = it }
        return true
    }

    /** Replans the same canonical motion through real stop/turn/start clips, without mirroring. */
    fun requestHeading(sign: Int): Boolean {
        val heading = ClipHeading.fromSign(sign) ?: return false
        val idle = manifest.headingIdle(heading) ?: return false
        if (requestedHeading == heading) return true // A repeated host hint must not overwrite an interaction.
        val canonical = manifest.canonicalClipId(requestedClip)!!
        val actual = manifest.resolveClip(canonical, heading)?.takeIf { manifest.clips.getValue(it).heading == heading } ?: idle
        val path = manifest.route(clipId, actual) ?: return false
        route.clear(); route.addAll(path); requestedClip = actual; requestedHeading = heading
        return true
    }

    fun requestStop(): Boolean {
        if (isSafeStandingClip()) hasReservedTravelSequence = false
        val heading = manifest.clips.getValue(clipId).exitHeading
        if (heading == null) {
            // Legacy stationary poses (for example back_stand/turn_back) may have no heading.
            // Follow only nonmoving authored clips to a stand; never freeze a valid zero-travel return.
            val fallback = stationaryReturn() ?: return false
            route.clear(); route.addAll(fallback.second); requestedClip = fallback.first
            requestedHeading = manifest.clips.getValue(fallback.first).heading ?: requestedHeading
            return true
        }
        val idle = manifest.headingIdle(heading) ?: return false
        val path = manifest.route(clipId, idle) ?: return false
        route.clear(); route.addAll(path); requestedClip = idle; requestedHeading = heading
        return true
    }

    private fun stationaryReturn(): Pair<String, List<String>>? {
        fun stationary(id: String) = manifest.clips.getValue(id).let {
            !it.travelEnabled && it.projectedTravel.none { value -> value > 0.0 }
        }
        if (!stationary(clipId)) return null
        val queue = java.util.ArrayDeque<String>(); queue.add(clipId)
        val previous = mutableMapOf<String, String?>(clipId to null)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val clip = manifest.clips.getValue(id)
            if (clip.loop && manifest.canonicalClipId(id) == "stand") {
                val path = mutableListOf<String>(); var cursor = id
                while (cursor != clipId) { path += cursor; cursor = previous.getValue(cursor)!! }
                return id to path.asReversed()
            }
            for (next in clip.transitions) if (next !in previous && stationary(next)) {
                previous[next] = id; queue.add(next)
            }
        }
        return null
    }

    /** Signed, resolution-independent authored travel, consumed only with this exact ready frame. */
    fun projectedTravelFor(next: ClipFrame): Double {
        require(next == peekNext()) { "位移必须与即将提交的真实帧对应" }
        if (!canTravel) return 0.0
        return manifest.clips.getValue(clipId).projectedTravel[frameIndex] * travelHeading
    }
    /** Look beyond stationary startup/turn bridges before leaving a safe standing pose. */
    fun travelReservationFor(next: ClipFrame): TravelReservation? {
        require(next == peekNext())
        val repeatsCycle = next.clipId == clipId && next.index == 0 && frameIndex == manifest.clips.getValue(clipId).frames.lastIndex
        if (next.clipId == clipId && !repeatsCycle) return null
        val continuation = if (repeatsCycle) listOf(clipId)
            else if (route.firstOrNull() == next.clipId) route.toList()
            else manifest.route(clipId, manifest.initialClip).orEmpty()
        val firstMoving = continuation.firstOrNull { manifest.travelReady(it) } ?: return null
        val fraction = manifest.stoppingTravelFraction(firstMoving, 0) ?: return null
        val heading = manifest.clips.getValue(firstMoving).heading!!.sign
        // The current inclusive interval may still move on the boundary before the new clip.
        // Adding its magnitude is conservative even when a later turn changes direction.
        return TravelReservation(heading, fraction + kotlin.math.abs(projectedTravelFor(next)))
    }
    fun commitIfTravelAllowed(next: ClipFrame, approve: (Double) -> Boolean): Double? =
        commitIfTravelAllowed(next, approve, { _, _ -> true })

    fun commitIfTravelAllowed(next: ClipFrame, approve: (Double) -> Boolean, approveEntry: (Int, Double) -> Boolean): Double? {
        rejectedTravelTarget = null
        val travel = projectedTravelFor(next)
        val reservation = travelReservationFor(next)
        if (reservation != null && !approveEntry(reservation.heading, reservation.fraction)) {
            rejectedTravelTarget = manifest.canonicalClipId(requestedClip)
            requestStop() // Retain this real pose and route safely back, never enter a dead-end gait.
            return null
        }
        if (travel != 0.0 && !approve(travel)) return null
        commit(next)
        if (reservation != null && !isSafeStandingClip()) hasReservedTravelSequence = true
        return travel
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
        if (isSafeStandingClip()) hasReservedTravelSequence = false
    }

    private fun isSafeStandingClip(): Boolean = manifest.clips.getValue(clipId).heading?.let {
        manifest.headingIdle(it) == clipId
    } == true

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
