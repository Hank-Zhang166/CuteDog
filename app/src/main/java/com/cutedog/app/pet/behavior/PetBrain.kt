package com.cutedog.app.pet.behavior

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Context contains no screen contents, application identities or other private device data. */
data class PetContext(
    val hour: Int,
    val quiet: Boolean,
    val activity: String,
    val charging: Boolean,
    val powerSaving: Boolean,
    val muted: Boolean,
    val interactionAgeSeconds: Float,
)

data class AttentionTarget(val x: Float, val y: Float)

/**
 * Goal selector, not an animation player. A returned ID must be passed to FrameClipPlayer.request;
 * that player's authored posture graph owns deceleration, sitting, lying down and waking.
 * update consumes elapsed seconds (including low-frequency callbacks), never frame counts.
 */
class PetBrain(seed: Long = System.nanoTime()) {
    enum class InteractionSource(val priority: Int) { MODEL(0), USER(1), REMINDER(2) }
    private val random = Random(seed)
    private var clock = 0.0
    private var knownAvailable: Set<String> = emptySet()
    private var observed = ""
    private var observedSince = 0.0
    private var nextDecision = 2.5
    private var dwellUntil = 0.0
    private var interactionHoldUntil = 0.0
    private var lastInteractionAt = Double.NEGATIVE_INFINITY
    private var pending: Request? = null
    private var queuedInteraction: Interaction? = null
    private var deferredUserInteraction: Interaction? = null
    private var lastInteraction: Interaction? = null
    private var activeReminderTarget: String? = null
    private var attentionAt = Double.NEGATIVE_INFINITY
    private var attention: AttentionTarget? = null
    private val lastRequested = mutableMapOf<String, Double>()
    private val recent = ArrayDeque<String>()
    private var lastContext = PetContext(12, false, "balanced", false, false, true, Float.POSITIVE_INFINITY)
    private var movementSeconds = 0.0

    val pendingTarget: String? get() = pending?.target
    val attentionTarget: AttentionTarget? get() = attention
    val elapsedSeconds: Double get() = clock

    fun setAttentionTarget(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        attention = AttentionTarget(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        attentionAt = clock
    }
    fun clearAttentionTarget() { attention = null; attentionAt = Double.NEGATIVE_INFINITY }

    /** New explicit goals replan from the real player pose; they never bypass its safe exits. */
    fun interact(kind: String, source: InteractionSource = InteractionSource.USER): String? {
        val input = Interaction(normalize(kind) ?: return null, source)
        // Coalesce identical touch repeats, without discarding a different, newer user intent.
        if (input == lastInteraction && clock - lastInteractionAt < .65) return null
        lastInteraction = input
        lastInteractionAt = clock
        interactionHoldUntil = clock + 14.0
        val protectedPriority = maxOf(pending?.source?.priority ?: -1, queuedInteraction?.source?.priority ?: -1,
            if (activeReminderTarget != null) InteractionSource.REMINDER.priority else -1)
        if (source.priority < protectedPriority) {
            if (source == InteractionSource.USER) deferredUserInteraction = input
            return null
        }
        if (source == InteractionSource.USER) deferredUserInteraction = null
        queuedInteraction = input
        if (knownAvailable.isEmpty()) return null
        return emitInteraction(input)
    }

    /** Call when the rendering graph rejects a goal; unavailable assets never cause request storms. */
    fun requestRejected(clipId: String) {
        if (pending?.target == clipId) {
            pending = null
            nextDecision = clock + 12.0
            lastRequested[clipId] = clock
        }
    }

    fun update(dt: Float, currentClip: String, available: Set<String>, context: PetContext): String? {
        if (!dt.isFinite() || dt < 0f) return null
        val previousClock = clock
        clock += dt.toDouble()
        knownAvailable = available
        lastContext = context
        if (clock - attentionAt > 12.0) attention = null
        if (activeReminderTarget !in available || (observed == activeReminderTarget && currentClip != observed)) {
            activeReminderTarget = null
        }
        if (currentClip != observed) {
            observed = currentClip
            observedSince = clock
            // Progress through a route extends the watchdog; never replace a healthy transition.
            pending = pending?.copy(progressAt = clock)
        }
        val activeDelta = min(clock - previousClock, 300.0)
        movementSeconds = if (currentClip in locomotion) min(180.0, movementSeconds + activeDelta)
            else max(0.0, movementSeconds - activeDelta * .7)

        val request = pending
        if (request != null) {
            when {
                request.target !in available -> {
                    pending = null
                    nextDecision = clock + 8.0
                }
                currentClip == request.target -> {
                    pending = null
                    val policy = catalog.getValue(request.target)
                    dwellUntil = clock + policy.dwell
                    nextDecision = max(nextDecision, dwellUntil)
                    // The new request reached an actual clip, rather than merely entering its route.
                    if (request.source != null) interactionHoldUntil = max(interactionHoldUntil, dwellUntil + 3.0)
                    if (request.source == InteractionSource.REMINDER) activeReminderTarget = request.target
                }
                clock - request.progressAt >= 90.0 && currentClip in stableClips -> {
                    // A rejected/stalled stable endpoint recovers once, with backoff. A transition is never churned.
                    pending = null
                    nextDecision = clock + 15.0
                    return null
                }
                else -> return null
            }
        }
        if (pending == null && activeReminderTarget == null) {
            if (queuedInteraction == null) {
                queuedInteraction = deferredUserInteraction
                deferredUserInteraction = null
            }
            // Autonomous dwell/stable-clip policy must never hold an explicit user request.
            queuedInteraction?.let { emitInteraction(it) }?.let { return it }
        }
        if (activeReminderTarget != null) return null
        if (pending != null || currentClip !in stableClips || clock < dwellUntil || clock < nextDecision) return null

        val night = hour(context) < 7 || hour(context) >= 22
        val restful = night || context.quiet || context.powerSaving || context.activity == "quiet"
        val recentlyInteracting = clock < interactionHoldUntil || interactionAge(context) < 35.0
        if (currentClip == "sleep" || currentClip == "curl_idle") {
            if (night || restful) { nextDecision = clock + 45.0; return null }
            if (clock - observedSince < 60.0 && !recentlyInteracting) { nextDecision = clock + 8.0; return null }
            if ("stretch" in available) return emit("stretch", null)
        }
        if ((night || context.quiet) && !recentlyInteracting && "sleep" in available) {
            return if (currentClip != "sleep") emit("sleep", null) else null
        }

        nextDecision = clock + decisionInterval(context)
        val candidates = catalog.values.filter { policy ->
            policy.autonomous && policy.id in available && policy.id != currentClip &&
                clock - (lastRequested[policy.id] ?: Double.NEGATIVE_INFINITY) >= policy.cooldown &&
                !(restful && policy.family in setOf(Family.MOVE, Family.PLAY)) &&
                !(context.charging && policy.id in setOf("jump", "hop"))
        }.map { it to utility(it, currentClip, context, recentlyInteracting) }.filter { it.second > 0.0 }
        if (candidates.isEmpty()) return null
        var draw = random.nextDouble() * candidates.sumOf { it.second }
        for ((policy, score) in candidates) {
            draw -= score
            if (draw <= 0.0) return emit(policy.id, null)
        }
        return emit(candidates.last().first.id, null)
    }

    private fun emitInteraction(input: Interaction): String? {
        val selected = interactions.getValue(input.kind).firstOrNull { it in knownAvailable }
        queuedInteraction = null
        if (selected == null) return null // Unavailable art does not cancel an existing valid route.
        val previous = pending
        if (previous?.target == selected) {
            // A reminder can protect an already-requested wave without restarting its route.
            if (input.source.priority > (previous.source?.priority ?: -1)) pending = previous.copy(source = input.source)
            return null
        }
        return emit(selected, input.source)
    }

    private fun emit(target: String, source: InteractionSource?): String {
        check(target in catalog && target in knownAvailable)
        pending = Request(target, clock, source)
        lastRequested[target] = clock
        recent.addLast(target)
        while (recent.size > 7) recent.removeFirst()
        val untilDwell = clock + catalog.getValue(target).dwell
        nextDecision = if (source == null) max(nextDecision, untilDwell) else untilDwell
        return target
    }

    private fun utility(policy: Policy, current: String, context: PetContext, recentTouch: Boolean): Double {
        var score = policy.weight
        if (policy.id == "sleep" && interactionAge(context) < 300 && !context.quiet && hour(context) in 7..21) return 0.0
        val currentFamily = catalog[current]?.family
        val idleAge = interactionAge(context)
        val morning = hour(context) in 7..10
        val evening = hour(context) in 19..21
        if (policy.id in recent) score *= .18
        if (policy.family == currentFamily && policy.family !in setOf(Family.REST, Family.LOOK)) score *= .4
        when (policy.family) {
            Family.REST -> {
                if (context.charging || context.powerSaving || context.quiet || evening) score *= 2.7
                if (movementSeconds > 25) score *= 3.0
                if (context.activity == "lively") score *= .65
            }
            Family.MOVE -> {
                if (context.activity == "lively") score *= 2.8
                if (morning) score *= 1.7
                if (idleAge > 600 || movementSeconds > 35) score *= .2
                if (context.muted) score *= .8
            }
            Family.PLAY -> {
                if (context.activity != "lively" && !recentTouch) score *= .12
                if (morning) score *= 1.5
                if (idleAge > 240 || context.muted) score *= .25
            }
            Family.LOOK -> {
                if (attention != null) score *= 2.0
                if (policy.id == "look_left" && (attention?.x ?: .5f) < .4f) score *= 5.0
                if (policy.id == "look_right" && (attention?.x ?: .5f) > .6f) score *= 5.0
                if (policy.id == "follow" && attention == null) return 0.0
            }
            Family.GROOM -> if (idleAge > 90 && !recentTouch) score *= 2.0 else score *= .3
            Family.AFFECTION -> {
                if (policy.id == "request_pet" || policy.id == "wave" || policy.id == "seek") {
                    // Infrequent, non-demanding acknowledgement. There is no hunger or neglect score.
                    if (idleAge < 180 || context.quiet || context.muted) return 0.0
                    score *= .25
                } else score *= if (recentTouch) 2.2 else .25
            }
            Family.STRETCH -> if (morning || movementSeconds > 35 || idleAge > 300) score *= 2.0
        }
        if (current == "back_stand" && policy.id != "stand") score *= .1
        return score
    }

    private fun decisionInterval(context: PetContext): Double {
        val base = when {
            context.quiet || context.powerSaving || hour(context) < 7 || hour(context) >= 22 -> 18.0
            context.charging -> 14.0
            context.activity == "lively" -> 2.4
            context.activity == "quiet" -> 20.0
            else -> 4.0
        }
        return base + random.nextDouble() * base * .65
    }
    private fun hour(context: PetContext) = ((context.hour % 24) + 24) % 24
    private fun interactionAge(context: PetContext) = context.interactionAgeSeconds.let {
        if (it.isNaN()) Double.POSITIVE_INFINITY else max(0.0, it.toDouble())
    }
    private fun normalize(kind: String): String? = when (kind.lowercase()) {
        "pet", "head", "pet_head" -> "head"
        "body", "back" -> "body"
        in interactions -> kind.lowercase()
        "run" -> "trot" // Older saved requests use the current jogging gait.
        "toy" -> "ball"
        "soothe", "comfort" -> "calm"
        else -> null
    }

    private data class Interaction(val kind: String, val source: InteractionSource)
    private data class Request(val target: String, val progressAt: Double, val source: InteractionSource?)
    private enum class Family { REST, MOVE, PLAY, LOOK, GROOM, AFFECTION, STRETCH }
    private data class Policy(val id: String, val family: Family, val dwell: Double, val cooldown: Double,
        val weight: Double, val autonomous: Boolean = true)

    companion object {
        private val locomotion = setOf("walk", "trot")
        private val stableClips = setOf("stand", "sit_idle", "lie_idle", "side_lie_idle", "curl_idle", "back_stand",
            "sleep", "breathe", "tail_wag", "relax", "walk", "trot")
        private fun p(id: String, family: Family, dwell: Double, cooldown: Double, weight: Double, autonomous: Boolean = true) =
            Policy(id, family, dwell, cooldown, weight, autonomous)
        private val catalog = listOf(
            p("stand", Family.REST, 4.0, 15.0, 1.8), p("sit_idle", Family.REST, 8.0, 35.0, 2.4),
            p("lie_idle", Family.REST, 20.0, 55.0, 1.6), p("side_lie_idle", Family.REST, 24.0, 150.0, .55),
            p("curl_idle", Family.REST, 50.0, 240.0, .3), p("sleep", Family.REST, 90.0, 360.0, .12),
            p("relax", Family.REST, 9.0, 65.0, 1.5), p("breathe", Family.REST, 5.0, 25.0, 1.3),
            p("walk", Family.MOVE, 7.0, 35.0, 2.0), p("trot", Family.MOVE, 5.0, 65.0, 1.0),
            p("back_stand", Family.LOOK, 6.0, 210.0, .25),
            p("blink", Family.LOOK, 1.5, 18.0, .9), p("look_left", Family.LOOK, 3.0, 35.0, 1.2),
            p("look_right", Family.LOOK, 3.0, 35.0, 1.2), p("tilt", Family.LOOK, 3.0, 60.0, 1.0),
            p("curious", Family.LOOK, 4.0, 70.0, .8), p("focus", Family.LOOK, 4.0, 90.0, .7),
            p("expect", Family.LOOK, 3.0, 45.0, 1.0),
            p("follow", Family.LOOK, 4.0, 25.0, 1.2), p("scratch", Family.GROOM, 4.0, 180.0, .8),
            p("lick_paw", Family.GROOM, 5.0, 150.0, 1.0), p("groom", Family.GROOM, 5.0, 210.0, .8),
            p("stretch", Family.STRETCH, 5.0, 100.0, 1.2), p("yawn", Family.STRETCH, 4.0, 130.0, .7),
            p("sleepy", Family.REST, 5.0, 160.0, .4), p("tail_wag", Family.AFFECTION, 6.0, 35.0, 1.2),
            p("backstep", Family.MOVE, 2.0, 165.0, .25), p("pause", Family.REST, 2.0, 75.0, .35),
            p("slide_correct", Family.MOVE, 2.0, 210.0, .12), p("rebound", Family.PLAY, 2.0, 150.0, .22),
            p("happy", Family.AFFECTION, 4.0, 65.0, .8), p("nuzzle", Family.AFFECTION, 4.0, 80.0, .55),
            p("cuddle", Family.AFFECTION, 5.0, 90.0, .55),
            p("wave", Family.AFFECTION, 4.0, 300.0, .6), p("request_pet", Family.AFFECTION, 5.0, 600.0, .3),
            p("seek", Family.AFFECTION, 5.0, 420.0, .35), p("hop", Family.PLAY, 3.0, 120.0, .6),
            p("jump", Family.PLAY, 3.0, 180.0, .4), p("excited", Family.PLAY, 4.0, 140.0, .4),
            p("belly_up", Family.AFFECTION, 6.0, 180.0, .25),
            p("pet_head", Family.AFFECTION, 5.0, 8.0, 0.0, false),
            p("soothe", Family.AFFECTION, 5.0, 8.0, 0.0, false),
            p("treat", Family.PLAY, 5.0, 8.0, 0.0, false),
            p("play_ball", Family.PLAY, 6.0, 10.0, 0.0, false),
            p("call", Family.LOOK, 4.0, 8.0, 0.0, false),
            p("shy", Family.AFFECTION, 3.0, 12.0, 0.0, false),
            p("startled", Family.LOOK, 2.0, 12.0, 0.0, false),
        ).associateBy { it.id }
        private val interactions = mapOf(
            "head" to listOf("pet_head", "nuzzle", "soothe"), "body" to listOf("cuddle", "belly_up", "relax"),
            "nose" to listOf("curious", "tilt"), "call" to listOf("call", "wave"),
            "happy" to listOf("happy", "tail_wag", "excited"), "calm" to listOf("soothe", "relax", "sit_idle"),
            "sleep" to listOf("sleep", "curl_idle", "sit_idle", "relax"),
            "curious" to listOf("curious", "tilt", "focus"), "cuddle" to listOf("cuddle", "nuzzle", "belly_up", "soothe", "relax"),
            "wave" to listOf("wave", "call"), "jump" to listOf("jump", "hop"), "treat" to listOf("treat"),
            "ball" to listOf("play_ball"), "follow" to listOf("follow", "look_left", "look_right"),
            "finger_follow" to listOf("stand", "breathe"), "trot" to listOf("trot", "walk"),
            "excited" to listOf("excited", "happy"),
            "sleepy" to listOf("sleepy", "relax"),
            "shy" to listOf("shy", "tilt"), "startled" to listOf("startled", "focus"),
            "seek" to listOf("seek", "look_right"), "request_pet" to listOf("request_pet", "wave"),
        )
        /** All goals are known authored endpoint/actions; transition clips are intentionally excluded. */
        val supportedTargets: Set<String> get() = catalog.keys
    }
}
