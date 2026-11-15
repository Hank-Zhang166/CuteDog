package com.cutedog.app.pet.animation

import org.junit.Assert.*
import org.junit.Test

/** Synthetic measured metadata exercises the real director and handoff; no fixture art is shipped. */
class OverlayTravelPolicyTest {
    private fun clip(id: String, entry: String, exit: String, loop: Boolean, travel: List<Double>, vararg next: String) =
        FrameClip(id, travel.indices.map { "pet3d/$id/$it.webp" }, 24, entry, exit, loop,
            if (loop) ClipInterrupt.BOUNDARY else ClipInterrupt.NEVER, next.toList(), heading = ClipHeading.LEFT,
            projectedTravel = travel, travelUnits = "canvas_width_fraction", travelEnabled = travel.any { it > 0 },
            sampling = "interval_starts")

    private fun manifest(stationaryStartup: Boolean = false) = ClipManifest(512, 512, "stand", listOf(
        clip("stand", "S", "S", true, List(4) { 0.0 }, "walk_start", "wave"),
        clip("walk_start", "S", "W", false, if (stationaryStartup) List(4) { 0.0 } else listOf(.001, .002, .002, .001), "walk"),
        clip("walk", "W", "W", true, listOf(.01, .02, .03, .04), "walk_stop"),
        // Nonzero terminal interval is intentional: the next visible clip is already stand.
        clip("walk_stop", "W", "S", false, listOf(.004, .003, .002, .001), "stand"),
        clip("wave", "S", "S", false, List(4) { 0.0 }, "stand"),
    ), emptySet())

    private class Host(val director: ClipDirector) {
        val approval = AuthoredStepApproval()
        val accumulator = AuthoredTravelAccumulator()
        var bubble = false
        var ready = true
        var spaceClear = true
        var position = 200.0
        var expectedPosition = position
        var sequence = 0L
        var lastPixels = 0f
        var lastStoppedIntervalConsumed = false
        val frames = mutableListOf<ClipFrame>()
        fun planBubble() {
            if (OverlayTravelPolicy.shouldStop(bubble, director.manifest.travelReady(director.requestedClip))) director.requestStop()
        }
        fun tick(): Double? {
            sequence++
            val time = sequence * 42L
            val before = director.current
            val inProgress = director.hasReservedTravelSequence || director.manifest.travelReady(director.clipId)
            val requested = director.manifest.travelReady(director.requestedClip)
            val delta = director.commitIfTravelAllowed(director.peekNext(), { fraction ->
                approval.approve(sequence, (fraction * 200).toFloat(), time,
                    OverlayTravelPolicy.mayAdvance(ready, bubble, inProgress) && spaceClear)
            }, { _, _ -> OverlayTravelPolicy.mayReserve(ready, bubble, inProgress, requested) && spaceClear })
            if (delta == null) {
                assertEquals(before, director.current)
                assertEquals(expectedPosition, position, .0001)
                return null
            }
            frames += director.current
            if (delta != 0.0) {
                val sign = if (delta > 0) 1 else -1
                accumulator.record(delta, 200.0, sign, ready, time)
                lastPixels = accumulator.consume(sign, ready, time)
                assertTrue(approval.consume(sequence, lastPixels, time, ready, spaceClear))
                position += lastPixels
                expectedPosition += delta * 200
                assertEquals(expectedPosition, position, .0001)
                assertEquals(0f, accumulator.consume(sign, ready, time), 0f)
                assertFalse(approval.consume(sequence, lastPixels, time, ready, spaceClear))
                if (before.clipId == "walk_stop" && director.clipId == "stand") {
                    assertFalse(director.manifest.travelReady(director.clipId))
                    lastStoppedIntervalConsumed = true
                }
            }
            return delta
        }
        fun advanceTo(id: String) {
            repeat(80) { if (director.clipId == id) return; assertNotNull(tick()) }
            error("Fixture did not reach $id")
        }
    }

    private fun walking(): Host = Host(ClipDirector(manifest())).apply {
        assertTrue(director.request("walk"))
        advanceTo("walk")
        assertNotNull(tick())
        assertEquals(1, director.frameIndex)
    }

    @Test fun bubbleKeepsReminderRouteAndConsumesEveryRemainingWalkAndStopInterval() {
        val host = walking()
        host.bubble = true
        assertTrue(host.director.request("wave"))
        host.planBubble()
        assertEquals("wave", host.director.requestedClip)
        val before = host.position
        host.advanceTo("wave")
        assertTrue(host.lastStoppedIntervalConsumed)
        // walk intervals 1..3 plus all four stop intervals; standing adds no travel.
        assertEquals(-(.02 + .03 + .04 + .004 + .003 + .002 + .001) * 200, host.position - before, .0001)
        assertTrue(host.frames.contains(ClipFrame("walk", 2, "pet3d/walk/2.webp")))
        assertTrue(host.frames.contains(ClipFrame("walk", 3, "pet3d/walk/3.webp")))
        (0..3).forEach { assertTrue(host.frames.contains(ClipFrame("walk_stop", it, "pet3d/walk_stop/$it.webp"))) }
    }

    @Test fun bubbleDuringAlreadyReservedZeroTravelStartupAllowsItsRealReturnToWave() {
        val host = Host(ClipDirector(manifest(stationaryStartup = true)))
        host.director.request("walk")
        host.advanceTo("walk_start")
        assertFalse(host.director.manifest.travelReady("walk_start"))
        assertTrue(host.director.hasReservedTravelSequence)
        assertEquals(200.0, host.position, 0.0)
        host.bubble = true
        host.director.request("wave")
        host.planBubble()
        assertEquals("wave", host.director.requestedClip)
        host.advanceTo("wave")
        assertTrue(host.lastStoppedIntervalConsumed)
        assertFalse(host.director.hasReservedTravelSequence)
        assertEquals(200.0 - (.01 + .02 + .03 + .04 + .004 + .003 + .002 + .001) * 200, host.position, .0001)
    }

    @Test fun menuRequestsSafeStopOnlyForARoamingGoal() {
        val host = walking()
        host.bubble = true
        host.planBubble()
        assertEquals("stand", host.director.requestedClip)
        host.advanceTo("stand")
        assertTrue(host.lastStoppedIntervalConsumed)
        repeat(20) { assertNotNull(host.tick()); assertEquals("stand", host.director.clipId) }
    }

    @Test fun visibleBubbleRejectsNewWalkingBeforeLeavingStationaryPose() {
        val host = Host(ClipDirector(manifest()))
        repeat(3) { host.tick() }
        host.bubble = true
        host.director.request("walk")
        val before = host.director.current
        assertNull(host.tick())
        assertEquals(before, host.director.current)
        assertEquals("stand", host.director.requestedClip)
        assertEquals(200.0, host.position, 0.0)
        assertFalse(host.approval.consume(host.sequence, -20f, host.sequence * 42, true, true))
    }

    @Test fun obstacleFreezesPoseAndWindowTogetherThenResumesTheSameAuthoredInterval() {
        val host = walking()
        host.bubble = true
        host.director.request("wave")
        host.spaceClear = false
        val before = host.director.current
        val position = host.position
        repeat(6) { assertNull(host.tick()) }
        assertEquals(before, host.director.current)
        assertEquals(position, host.position, 0.0)
        host.spaceClear = true
        assertNotNull(host.tick())
        assertEquals(2, host.director.frameIndex)
        host.advanceTo("wave")
        assertTrue(host.lastStoppedIntervalConsumed)
    }

    @Test fun hiddenOrOtherwiseUnreadyHostCannotCommitARealStep() {
        val host = walking()
        host.bubble = true
        host.director.request("wave")
        host.ready = false
        val before = host.director.current
        val position = host.position
        assertNull(host.tick())
        assertEquals(before, host.director.current)
        assertEquals(position, host.position, 0.0)
    }

    @Test fun wholeRouteReservationNeverIssuesAStepPermit() {
        val permit = AuthoredStepApproval()
        assertTrue(OverlayTravelPolicy.mayReserve(true, false, false, true))
        assertFalse(permit.consume(1, -30f, 100, true, true))
        assertFalse(OverlayTravelPolicy.mayReserve(true, true, true, true))
        assertTrue(OverlayTravelPolicy.mayReserve(true, true, true, false))
    }

    @Test fun stepPermitRejectsWrongFrameDuplicateExpiryAndChangedSpaceOrVisibility() {
        val permit = AuthoredStepApproval(250)
        assertFalse(permit.consume(1, -2f, 100, true, true))
        assertTrue(permit.approve(1, -2f, 100, true))
        assertFalse(permit.consume(2, -2f, 100, true, true))
        assertFalse(permit.consume(1, -2f, 100, true, true))
        assertTrue(permit.approve(3, -2f, 100, true))
        assertFalse(permit.consume(3, -2f, 351, true, true))
        assertTrue(permit.approve(4, -2f, 100, true))
        assertFalse(permit.consume(4, -2f, 110, true, false))
        assertTrue(permit.approve(5, -2f, 100, true))
        assertFalse(permit.consume(5, -2f, 110, false, true))
        assertTrue(permit.approve(6, -2f, 100, true))
        assertTrue(permit.consume(6, -2f, 110, true, true))
        assertFalse(permit.consume(6, -2f, 110, true, true))
    }

    @Test fun rejectedOrInvalidStepApprovalClearsAnEarlierPermit() {
        val permit = AuthoredStepApproval()
        assertTrue(permit.approve(1, -2f, 100, true))
        assertFalse(permit.approve(2, -2f, 110, false))
        assertFalse(permit.consume(1, -2f, 110, true, true))
        assertFalse(permit.approve(3, Float.NaN, 110, true))
        assertFalse(permit.approve(4, 0f, 110, true))
        assertTrue(permit.approve(5, -2f, 110, true))
        permit.clear()
        assertFalse(permit.consume(5, -2f, 110, true, true))
    }
}
