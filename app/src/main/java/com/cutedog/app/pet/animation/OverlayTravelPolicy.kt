package com.cutedog.app.pet.animation

import kotlin.math.abs
import kotlin.math.max

/** A visible bubble permits an existing measured return, never a new roaming goal. */
object OverlayTravelPolicy {
    fun mayAdvance(hostReady: Boolean, bubbleVisible: Boolean, travelInProgress: Boolean): Boolean =
        hostReady && (!bubbleVisible || travelInProgress)

    fun mayReserve(hostReady: Boolean, bubbleVisible: Boolean, travelInProgress: Boolean, travelRequested: Boolean): Boolean =
        mayAdvance(hostReady, bubbleVisible, travelInProgress) && (!bubbleVisible || !travelRequested)

    fun shouldStop(bubbleVisible: Boolean, travelRequested: Boolean): Boolean = bubbleVisible && travelRequested
}

/** One real step, issued before commit and drained in the same frame callback. Reservations cannot issue it. */
class AuthoredStepApproval(private val maximumAgeMs: Long = 250L) {
    private data class Approval(val sequence: Long, val pixels: Float, val atMs: Long)
    private var approval: Approval? = null
    init { require(maximumAgeMs > 0) }

    fun approve(sequence: Long, pixels: Float, nowMs: Long, allowed: Boolean): Boolean {
        approval = null
        if (!allowed || sequence < 0 || !pixels.isFinite() || pixels == 0f) return false
        approval = Approval(sequence, pixels, nowMs)
        return true
    }

    fun consume(sequence: Long, pixels: Float, nowMs: Long, hostReady: Boolean, spaceClear: Boolean): Boolean {
        val expected = approval
        approval = null
        return expected != null && hostReady && spaceClear && pixels.isFinite() &&
            sequence == expected.sequence && nowMs >= expected.atMs && nowMs - expected.atMs <= maximumAgeMs &&
            pixels * expected.pixels > 0f && abs(pixels - expected.pixels) <= max(.0001f, abs(expected.pixels) * .00001f)
    }

    fun clear() { approval = null }
}
