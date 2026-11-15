package com.cutedog.app.pet.animation

/** Seconds-based clock: clip sampling rate may change, wall time does not. */
class FrameCadence {
    private var seconds = 0.0
    private var consecutiveLateTicks = 0
    val hostTooSlow: Boolean get() = consecutiveLateTicks >= 8
    fun add(deltaSeconds: Double, fps: Int) {
        require(fps > 0)
        val step = 1.0 / fps
        val delta = deltaSeconds.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(.25) ?: 0.0
        consecutiveLateTicks = if (delta > step * 1.1) consecutiveLateTicks + 1 else 0
        seconds = (seconds + delta).coerceAtMost(step * 2)
    }
    fun due(fps: Int): Boolean = seconds + 1e-9 >= 1.0 / fps
    fun consumed(fps: Int) { seconds = (seconds - 1.0 / fps).coerceAtLeast(0.0) }
    fun blocked(fps: Int) { seconds = (1.0 / fps).coerceAtMost(seconds) }
    fun reset() { seconds = 0.0; consecutiveLateTicks = 0 }
}
