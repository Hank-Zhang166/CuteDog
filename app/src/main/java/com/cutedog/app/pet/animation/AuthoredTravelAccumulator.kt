package com.cutedog.app.pet.animation

import kotlin.math.abs

/** Main-thread handoff from frame cadence to window cadence. Values are already rendered pixels. */
class AuthoredTravelAccumulator(private val maximumAgeMs: Long = 250L) {
    private var pixels = 0.0
    private var heading = 0
    private var firstRecordedAt = 0L
    init { require(maximumAgeMs > 0L) }

    fun record(fraction: Double, renderedWidth: Double, sign: Int, allowed: Boolean, nowMs: Long) {
        if (!allowed || (sign != -1 && sign != 1) || !fraction.isFinite() || !renderedWidth.isFinite() ||
            renderedWidth <= 0.0 || abs(fraction) > 1.0 || (fraction != 0.0 && fraction * sign < 0.0)) {
            clear(); return
        }
        if (heading != sign || (pixels != 0.0 && (nowMs < firstRecordedAt || nowMs - firstRecordedAt > maximumAgeMs))) clear()
        if (fraction == 0.0) return // A host tick without a new frame must preserve earlier unconsumed frames.
        if (pixels == 0.0) firstRecordedAt = nowMs
        heading = sign
        val accumulated = pixels + fraction * renderedWidth
        if (!accumulated.isFinite() || abs(accumulated) > Float.MAX_VALUE) { clear(); return }
        pixels = accumulated
    }

    /** Always drains: rejected/stale travel cannot reappear after resume or a direction change. */
    fun consume(sign: Int, allowed: Boolean, nowMs: Long): Float {
        val value = if (allowed && sign == heading && nowMs >= firstRecordedAt && nowMs - firstRecordedAt <= maximumAgeMs) pixels else 0.0
        clear()
        return value.toFloat()
    }

    fun clear() { pixels = 0.0; heading = 0; firstRecordedAt = 0L }
}
