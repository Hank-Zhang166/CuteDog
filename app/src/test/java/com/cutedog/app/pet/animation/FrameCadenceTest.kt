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
}
