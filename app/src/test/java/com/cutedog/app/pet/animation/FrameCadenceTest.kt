package com.cutedog.app.pet.animation

import org.junit.Assert.*
import org.junit.Test

class FrameCadenceTest {
    @Test fun authoredTwelveAndTwentyFourFpsHaveCorrectWallTimeAtSixtyHz() {
        val clock = FrameCadence()
        var slowFrames = 0
        repeat(60) { clock.add(1.0 / 60, 12); if (clock.due(12)) { slowFrames++; clock.consumed(12) } }
        assertEquals(12, slowFrames)
        var activeFrames = 0
        repeat(60) { clock.add(1.0 / 60, 24); if (clock.due(24)) { activeFrames++; clock.consumed(24) } }
        assertEquals(24, activeFrames)
        assertFalse(clock.hostTooSlow)
    }
    @Test fun thirtyHzHostStillShowsTwentyFourAuthoredFramesPerSecond() {
        val clock = FrameCadence(); var frames = 0
        repeat(30) { clock.add(1.0 / 30, 24); if (clock.due(24)) { frames++; clock.consumed(24) } }
        assertEquals(24, frames)
    }
    @Test fun missedDecodeFreezesTimeAndLowHostRateIsReported() {
        val clock = FrameCadence()
        repeat(10) { clock.add(.1, 24); clock.blocked(24) }
        assertTrue(clock.hostTooSlow)
        assertTrue(clock.due(24)); clock.consumed(24)
        assertFalse(clock.due(24))
        clock.reset(); assertFalse(clock.hostTooSlow)
        clock.add(Double.NaN, 24); assertFalse(clock.due(24))
    }

    private fun scheduledFrames(clock: FrameCadence, fps: Int, count: Int, startVsync: Int = 0, callbackWorkMs: Double = 0.0): List<Int> {
        var vsync = startVsync
        return (0 until count).map {
            val deadlineMs = vsync * (1000.0 / 60.0) + callbackWorkMs + clock.delayUntilNextFrameMillis(fps, (callbackWorkMs * 1_000_000).toLong())
            val next = maxOf(vsync + 1, kotlin.math.ceil(deadlineMs / (1000.0 / 60.0)).toInt())
            clock.add((next - vsync) / 60.0, fps)
            assertTrue("Scheduled callback must reach the authored frame boundary", clock.due(fps))
            clock.consumed(fps); vsync = next
            vsync
        }
    }

    @Test fun deadlineSchedulingPresents24FpsAsTwoAndThreeVsyncIntervalsAt60Hz() {
        val clock = FrameCadence()
        val frames = listOf(0) + scheduledFrames(clock, 24, 72)
        assertEquals(setOf(2, 3), frames.zipWithNext { first, second -> second - first }.toSet())
        assertEquals(180, frames.last()) // Three seconds, not a fixed 31/62ms timer pattern.
        assertFalse(clock.hostTooSlow)
    }

    @Test fun changingAuthoredFpsKeepsItsNextRealBoundaryWithoutFixedHostIntervals() {
        val clock = FrameCadence()
        val first = scheduledFrames(clock, 24, 24)
        val second = scheduledFrames(clock, 12, 12, first.last())
        assertEquals(60, first.last()); assertEquals(120, second.last())
        assertTrue((listOf(first.last()) + second).zipWithNext { a, b -> b - a }.all { it == 5 })
    }

    @Test fun pauseResetDropsAccumulatedTimeBeforeTheNextScheduledFrame() {
        val clock = FrameCadence()
        clock.add(.04, 24)
        assertEquals(1L, clock.delayUntilNextFrameMillis(24))
        clock.reset()
        assertEquals(41L, clock.delayUntilNextFrameMillis(24))
        assertEquals(listOf(3), scheduledFrames(clock, 24, 1))
    }

    @Test fun callbackWorkDoesNotShiftTheNextVsyncDeadline() {
        val expected = scheduledFrames(FrameCadence(), 24, 72)
        assertEquals(expected, scheduledFrames(FrameCadence(), 24, 72, callbackWorkMs = 6.0))
        assertEquals(expected, scheduledFrames(FrameCadence(), 24, 72, callbackWorkMs = 18.0))
    }
}
