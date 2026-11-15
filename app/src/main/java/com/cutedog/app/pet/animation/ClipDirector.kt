package com.cutedog.app.pet.animation

/** Small immutable reference; contains no pixels and is cheap to allocate. */
data class ClipFrame(val clipId: String, val index: Int, val path: String)
data class TravelReservation(val heading: Int, val fraction: Double)
data class ClipAdvancePlan(val from: ClipFrame, val next: ClipFrame, val revision: Long, val phaseBridge: String? = null)

/** Main-thread control only. Never skips authored intermediate poses or advances a missing frame. */
class ClipDirector(val manifest: ClipManifest) {
    var clipId: String = manifest.initialClip; private set
    var frameIndex: Int = 0; private set
    var requestedClip: String = manifest.initialClip; private set
    var requestedHeading: ClipHeading? = manifest.clips.getValue(manifest.initialClip).heading
        ?: manifest.clips["stand"]?.heading; private set
    private val route = java.util.ArrayDeque<String>()
    private var revision = 0L
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
        revision++
        // Exact variant requests also carry an explicit direction; ordinary interactions retain
        // the next desired travel direction while the graph returns to their authored front pose.
        manifest.clips.getValue(target).takeIf { it.variantOf != null }?.heading?.let { requestedHeading = it }
        releaseCancelledStaticReservation()
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
        revision++
        releaseCancelledStaticReservation()
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
            revision++
            requestedHeading = manifest.clips.getValue(fallback.first).heading ?: requestedHeading
            releaseCancelledStaticReservation()
            return true
        }
        val idle = manifest.headingIdle(heading) ?: return false
        val path = manifest.route(clipId, idle) ?: return false
        route.clear(); route.addAll(path); requestedClip = idle; requestedHeading = heading
        revision++
        releaseCancelledStaticReservation()
        return true
    }

    private fun releaseCancelledStaticReservation() {
        if (!hasReservedTravelSequence || manifest.travelReady(requestedClip)) return
        val heading = manifest.clips.getValue(clipId).exitHeading ?: return
        val idle = manifest.headingIdle(heading) ?: return
        val home = manifest.route(clipId, idle) ?: return
        // A stationary phase bridge can safely finish without starting the cancelled walk.
        // A zero-travel walk_start whose return requires walk/stop must keep its reservation.
        if ((listOf(clipId) + home).all { id -> manifest.clips.getValue(id).let {
                !it.travelEnabled && it.projectedTravel.size == it.frames.size && it.projectedTravel.all { distance -> distance == 0.0 }
            } }) hasReservedTravelSequence = false
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
        return travelReservationFor(ClipAdvancePlan(current, next, revision))
    }
    fun travelReservationFor(plan: ClipAdvancePlan): TravelReservation? {
        validate(plan)
        val next = plan.next
        val repeatsCycle = next.clipId == clipId && next.index == 0 && frameIndex == manifest.clips.getValue(clipId).frames.lastIndex
        if (next.clipId == clipId && !repeatsCycle) return null
        val continuation = if (plan.phaseBridge != null) listOf(plan.phaseBridge) + manifest.route(plan.phaseBridge, requestedClip).orEmpty()
            else if (repeatsCycle) listOf(clipId)
            else if (route.firstOrNull() == next.clipId) route.toList()
            else manifest.route(clipId, manifest.initialClip).orEmpty()
        val firstMoving = continuation.firstOrNull { manifest.travelReady(it) } ?: return null
        val fraction = manifest.stoppingTravelFraction(firstMoving, 0) ?: return null
        val heading = manifest.clips.getValue(firstMoving).heading!!.sign
        // The current inclusive interval may still move on the boundary before the new clip.
        // Adding its magnitude is conservative even when a later turn changes direction.
        return TravelReservation(heading, fraction + kotlin.math.abs(if (plan.phaseBridge != null) 0.0 else projectedTravelFor(next)))
    }
    fun commitIfTravelAllowed(next: ClipFrame, approve: (Double) -> Boolean): Double? =
        commitIfTravelAllowed(next, approve, { _, _ -> true })

    fun commitIfTravelAllowed(next: ClipFrame, approve: (Double) -> Boolean, approveEntry: (Int, Double) -> Boolean): Double? {
        require(next == peekNext())
        return commitIfTravelAllowed(ClipAdvancePlan(current, next, revision), approve, approveEntry)
    }
    fun commitIfTravelAllowed(plan: ClipAdvancePlan, approve: (Double) -> Boolean, approveEntry: (Int, Double) -> Boolean): Double? {
        validate(plan)
        rejectedTravelTarget = null
        val travel = if (plan.phaseBridge != null) 0.0 else projectedTravelFor(plan.next)
        val reservation = travelReservationFor(plan)
        if (reservation != null && !approveEntry(reservation.heading, reservation.fraction)) {
            rejectedTravelTarget = manifest.canonicalClipId(requestedClip)
            requestStop() // Retain this real pose and route safely back, never enter a dead-end gait.
            return null
        }
        if (travel != 0.0 && !approve(travel)) return null
        commit(plan)
        if (reservation != null && !isSafeStandingClip()) hasReservedTravelSequence = true
        return travel
    }

    /** The currently displayed anchor is already past; only a strictly future source interval may branch. */
    fun upcomingPhaseBridge(): FrameClip? {
        if (route.isEmpty()) return null
        val source = manifest.clips.getValue(clipId)
        if (source.phaseEntry != null) return null
        val ordinaryTime = (source.frames.size - frameIndex).toDouble() / source.fps + routeTimeBeforeGoal(route.toList())
        return manifest.phaseExits(clipId).firstOrNull { bridge ->
            val anchor = bridge.phaseEntry!!.atFrame
            val after = manifest.route(bridge.id, requestedClip)
            anchor > frameIndex && after != null &&
                (anchor - frameIndex).toDouble() / source.fps + bridge.frames.size.toDouble() / bridge.fps + routeTimeBeforeGoal(after) < ordinaryTime - 1e-9
        }
    }

    private fun routeTimeBeforeGoal(path: List<String>): Double = path.dropLast(1).sumOf {
        manifest.clips.getValue(it).let { part -> part.frames.size.toDouble() / part.fps }
    }

    /** Readiness is a cache-only query. A late bridge never displaces a later, different source phase. */
    fun planNext(isReady: (String) -> Boolean): ClipAdvancePlan {
        val bridge = upcomingPhaseBridge()?.takeIf { it.phaseEntry!!.atFrame == frameIndex + 1 && it.frames.take(2).all(isReady) }
        return if (bridge == null) ClipAdvancePlan(current, peekNext(), revision)
            else ClipAdvancePlan(current, ref(bridge.id, 0), revision, bridge.id)
    }

    private fun validate(plan: ClipAdvancePlan) {
        require(plan.revision == revision && plan.from == current) { "过期帧计划不能提交" }
        if (plan.phaseBridge == null) require(plan.next == peekNext()) { "普通帧计划不符" }
        else {
            val bridge = upcomingPhaseBridge()
            require(bridge != null && bridge.id == plan.phaseBridge && bridge.phaseEntry!!.atFrame == frameIndex + 1 && plan.next == ref(bridge.id, 0)) { "相位桥与当前真实入口不符" }
        }
    }

    fun commit(plan: ClipAdvancePlan) {
        validate(plan)
        if (plan.phaseBridge == null) { commit(plan.next); return }
        val after = requireNotNull(manifest.route(plan.phaseBridge, requestedClip))
        route.clear(); route.addAll(after)
        clipId = plan.phaseBridge; frameIndex = 0; revision++
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
        revision++
        if (isSafeStandingClip()) hasReservedTravelSequence = false
    }

    private fun isSafeStandingClip(): Boolean = manifest.clips.getValue(clipId).heading?.let {
        manifest.headingIdle(it) == clipId
    } == true

    /** Prioritized lookahead without mutating playback. Includes the next clip's first frames. */
    fun prefetchPaths(lookAhead: Int = 8): List<String> {
        val clip = manifest.clips.getValue(clipId)
        val result = linkedSetOf(current.path, peekNext().path)
        // Protect the next genuine source frame plus the nearest bridge entry/next sample before
        // lower-priority lookahead; even the six-frame pressure budget can make this choice.
        upcomingPhaseBridge()?.let { result.addAll(it.frames.take(2)) }
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
