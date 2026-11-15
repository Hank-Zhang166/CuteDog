package com.cutedog.app.pet.animation

/** Rejects the tail of a frame callback if a synchronous consumer paused/restarted its loop. */
class FrameScheduleGate {
    var running: Boolean = false; private set
    private var generation = 0L
    fun resume(): Boolean {
        if (running) return false
        running = true; generation++
        return true
    }
    fun pause() { running = false; generation++ }
    fun token(): Long = generation
    fun isCurrent(token: Long): Boolean = running && generation == token
}
