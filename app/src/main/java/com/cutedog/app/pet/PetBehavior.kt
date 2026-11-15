package com.cutedog.app.pet

import kotlin.math.*
import kotlin.random.Random

/** Deterministic, frame-rate independent behavior. Rendering is deliberately separate. */
class PetBehavior(private val random: Random = Random.Default) {
    enum class State(val pose: Int) {
        IDLE(0), WALK(1), TROT(1), RUN(1), STOPPING(1), SIT(2), LIE(3),
        SLEEP(3), WAKE(2), STRETCH(0), JUMP(5), LAND(0), HAPPY(4), CURIOUS(0), WAVE(4), CUDDLE(4)
    }
    var state = State.IDLE; private set
    var previous = State.IDLE; private set
    var blend = 1f; private set
    var velocity = 0f; private set
    var direction = 1f; private set
    var position = .5f; private set
    var jump = 0f; private set
    var depth = 1f; private set
    var clock = 0f; private set
    private var elapsed = 0f
    private var duration = 4f
    private var target: State? = null
    private var jumpVelocity = 0f
    private val recent = mutableMapOf<State, Float>()
    var quiet = false
    var reducedMotion = false
    var active = false

    fun react(intent: String) {
        val next = when (intent) {
            "happy", "pet" -> State.HAPPY
            "calm", "sleep" -> State.SLEEP
            "curious" -> State.CURIOUS
            "cuddle" -> State.CUDDLE
            "wave" -> State.WAVE
            "jump", "toy" -> State.JUMP
            else -> return
        }
        if (state == State.JUMP || state == State.LAND) { target = next; return }
        target = next
        if (abs(velocity) > .005f) enter(State.STOPPING)
        else prepare(next)
    }

    private fun prepare(next: State) {
        when {
            state == State.SLEEP -> enter(State.WAKE)
            next == State.SLEEP && state != State.SIT && state != State.LIE -> enter(State.SIT)
            next == State.SLEEP && state == State.SIT -> enter(State.LIE)
            else -> { target = null; enter(next) }
        }
    }

    fun update(delta: Float) {
        // Long background pauses never cause teleportation or a giant physics step.
        val dt = delta.coerceIn(0f, .05f)
        clock += dt; elapsed += dt; blend = (blend + dt / .48f).coerceAtMost(1f)
        val speed = if (quiet || reducedMotion) 0f else when (state) {
            State.WALK -> .035f; State.TROT -> .07f; State.RUN -> .12f; else -> 0f
        }
        val desired = speed * direction
        velocity += (desired - velocity).coerceIn(-.12f * dt, .12f * dt)
        position = (position + velocity * dt).coerceIn(0f, 1f)
        if (position >= .99f && direction > 0 || position <= .01f && direction < 0) {
            direction *= -1; enter(State.STOPPING); target = State.IDLE
        }
        depth += ((if (state == State.CUDDLE) 1.08f else 1f) - depth) * min(1f, dt * 2)
        if (state == State.JUMP) {
            jumpVelocity -= dt * 2.5f
            jump = (jump + jumpVelocity * dt).coerceAtLeast(0f)
            if (jump == 0f && elapsed > .12f) enter(State.LAND)
        }
        if (state == State.STOPPING && abs(velocity) < .002f) { prepare(target ?: State.IDLE); return }
        if (elapsed < duration) return
        when (state) {
            State.JUMP -> Unit
            State.WAKE -> enter(State.STRETCH)
            State.STRETCH -> prepare(target ?: State.IDLE)
            State.SIT, State.LIE -> if (target == State.SLEEP) prepare(State.SLEEP) else choose()
            State.LAND -> prepare(target ?: State.IDLE)
            else -> target?.let { prepare(it) } ?: choose()
        }
    }

    private fun choose() {
        val candidates = if (quiet || reducedMotion) listOf(State.SIT, State.IDLE, State.SLEEP, State.CURIOUS)
            else listOf(State.IDLE, State.SIT, State.WALK, State.WALK, State.TROT, State.CURIOUS, State.WAVE, State.STRETCH) +
                if (active) listOf(State.RUN, State.JUMP) else emptyList()
        val next = candidates.filter { it != state && clock - (recent[it] ?: -100f) > 12f }.randomOrNull(random) ?: State.IDLE
        target = next
        if (abs(velocity) > .005f && next !in listOf(State.WALK, State.TROT, State.RUN)) enter(State.STOPPING)
        else prepare(next)
    }

    private fun enter(next: State) {
        if (state == next) { elapsed = 0f; return }
        previous = state; state = next; elapsed = 0f; blend = 0f; recent[next] = clock
        duration = when (next) {
            State.WAKE, State.STRETCH -> 1.5f
            State.LIE -> 2f
            State.LAND -> .5f
            State.SLEEP -> if (quiet) 40f else 15f
            State.HAPPY, State.WAVE, State.CUDDLE -> 3.5f
            else -> 4f + random.nextFloat() * 5f
        }
        if (next == State.JUMP) { jump = .001f; jumpVelocity = 1.05f }
    }
}
